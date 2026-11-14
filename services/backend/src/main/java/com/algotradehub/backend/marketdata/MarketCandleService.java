package com.algotradehub.backend.marketdata;

import com.algotradehub.backend.common.ApiException;
import com.algotradehub.backend.orders.Instrument;
import com.algotradehub.backend.orders.InstrumentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MarketCandleService {
    private static final Duration MAX_RANGE = Duration.ofDays(7);
    private static final int MAX_BARS = 10_080;
    private static final Duration ANALYSIS_LOOKBACK = Duration.ofDays(350);
    private static final int MAX_ANALYSIS_BARS = 100_000;
    private static final int MAX_RETURNED_GAPS = 1_000;
    private final InstrumentRepository instruments;
    private final MarketCandleRepository candles;
    private final MarketDataGapRepository gaps;
    private final CandleAggregator aggregator;
    private final SyntheticDemoHistoryProvider demoHistory;

    public MarketCandleService(InstrumentRepository instruments, MarketCandleRepository candles,
                              MarketDataGapRepository gaps, CandleAggregator aggregator,
                              SyntheticDemoHistoryProvider demoHistory) {
        this.instruments = instruments;
        this.candles = candles;
        this.gaps = gaps;
        this.aggregator = aggregator;
        this.demoHistory = demoHistory;
    }

    public CandleSeries query(UUID instrumentId, String timeframe, Instant from, Instant to, String sourceId) {
        return query(instrumentId, timeframe, from, to, sourceId, MAX_RANGE, MAX_BARS, true);
    }

    /** Uses a bounded history window for indicator warm-up without widening the chart API limit. */
    public CandleSeries queryForAnalysis(UUID instrumentId, Instant to, String sourceId) {
        if (to == null) {
            throw reject(HttpStatus.BAD_REQUEST, "CANDLE_QUERY_REQUIRED", "An analysis time is required.");
        }
        Instant from;
        try {
            from = to.minus(ANALYSIS_LOOKBACK);
        } catch (DateTimeException exception) {
            throw reject(HttpStatus.BAD_REQUEST, "INVALID_CANDLE_RANGE", "The requested analysis time is outside the supported instant range.");
        }
        return query(instrumentId, "1m", from, to, sourceId, ANALYSIS_LOOKBACK, MAX_ANALYSIS_BARS, false);
    }

    private CandleSeries query(UUID instrumentId, String timeframe, Instant from, Instant to, String sourceId,
                               Duration maximumRange, int maximumBars, boolean includeCurrent) {
        if (instrumentId == null || from == null || to == null || timeframe == null) {
            throw reject(HttpStatus.BAD_REQUEST, "CANDLE_QUERY_REQUIRED", "Instrument, timeframe, from, and to are required.");
        }
        if (!"1m".equals(timeframe)) {
            throw reject(HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_TIMEFRAME", "Only finalized one-minute candles are supported.");
        }
        if (!to.isAfter(from) || Duration.between(from, to).compareTo(maximumRange) > 0) {
            throw reject(HttpStatus.BAD_REQUEST, "INVALID_CANDLE_RANGE", "The requested range is invalid or exceeds its bounded history window.");
        }
        Instrument instrument = instruments.findById(instrumentId).filter(Instrument::isActive)
                .orElseThrow(() -> reject(HttpStatus.NOT_FOUND, "INSTRUMENT_UNAVAILABLE", "Instrument is missing or inactive."));
        boolean exactSource = sourceId != null && !sourceId.isBlank();
        PageRequest candleLimit = PageRequest.of(0, maximumBars + 1);
        List<MarketCandleEntity> persisted;
        if (exactSource) {
            persisted = candles.findByInstrumentIdAndTimeframeSecondsAndBucketStartGreaterThanEqualAndBucketStartLessThanAndSourceIdOrderByBucketStartAsc(
                    instrumentId, CandleAggregator.SUPPORTED_TIMEFRAME_SECONDS, from, to, sourceId, candleLimit);
        } else {
            persisted = candles.findByInstrumentIdAndTimeframeSecondsAndBucketStartGreaterThanEqualAndBucketStartLessThanOrderByBucketStartAsc(
                    instrumentId, CandleAggregator.SUPPORTED_TIMEFRAME_SECONDS, from, to, candleLimit);
        }
        if (persisted.size() > maximumBars) {
            throw reject(HttpStatus.BAD_REQUEST, "CANDLE_LIMIT_EXCEEDED", "The requested range contains too many candles.");
        }
        Set<String> sources = persisted.stream().map(MarketCandleEntity::getSourceId).collect(Collectors.toSet());
        if (sources.size() > 1) {
            throw reject(HttpStatus.CONFLICT, "SOURCE_SELECTION_REQUIRED", "The range contains multiple data sources; request one source at a time.");
        }
        List<CandleBar> result = new ArrayList<>();
        if (!persisted.isEmpty()) {
            persisted.stream().map(this::toBar).forEach(result::add);
        } else if ((sourceId == null || "SYNTHETIC_DEMO".equals(sourceId)) && intersectsDemoRange(from, to)) {
            demoHistory.samples(instrumentId, instrument.getMockPrice(), instrument.getTickSize(), instrument.getMasterVersion()).stream()
                    .filter(bar -> !bar.bucketStart().isBefore(from) && bar.bucketStart().isBefore(to)).forEach(result::add);
        }
        if (includeCurrent) {
            CandleBar current = aggregator.current(instrumentId);
            if (current != null && (sourceId == null || sourceId.isBlank() || sourceId.equals(current.sourceId()))
                    && (result.isEmpty() || result.stream().allMatch(bar -> bar.sourceId().equals(current.sourceId())))
                    && !current.bucketStart().isBefore(from) && current.bucketStart().isBefore(to)
                    && result.stream().noneMatch(bar -> bar.bucketStart().equals(current.bucketStart()))) result.add(current);
        }
        result.sort((a, b) -> a.bucketStart().compareTo(b.bucketStart()));
        if (result.size() > maximumBars) throw reject(HttpStatus.BAD_REQUEST, "CANDLE_LIMIT_EXCEEDED", "The requested range contains too many candles.");
        PageRequest gapLimit = PageRequest.of(0, MAX_RETURNED_GAPS + 1);
        List<MarketDataGapEntity> gapRows;
        if (exactSource) {
            gapRows = gaps.findByInstrumentIdAndGapStartLessThanAndGapEndGreaterThanAndSourceIdOrderByGapStartAsc(
                    instrumentId, to, from, sourceId, gapLimit);
        } else {
            gapRows = gaps.findByInstrumentIdAndGapStartLessThanAndGapEndGreaterThanOrderByGapStartAsc(
                    instrumentId, to, from, gapLimit);
        }
        boolean gapsTruncated = gapRows.size() > MAX_RETURNED_GAPS;
        List<MarketDataGapEntity> matchingGaps = gapRows.subList(0, Math.min(gapRows.size(), MAX_RETURNED_GAPS));
        String resolvedSource;
        if (result.isEmpty()) {
            resolvedSource = "UNAVAILABLE";
        } else {
            resolvedSource = result.getFirst().sourceId();
        }
        boolean synthetic = !result.isEmpty() && result.stream().allMatch(bar -> "SYNTHETIC".equals(bar.quality()));
        String freshness;
        if (result.isEmpty()) {
            freshness = "NO_DATA";
        } else if (synthetic) {
            freshness = "SIMULATED";
        } else {
            freshness = freshness(result.getLast());
        }
        List<String> warnings = new ArrayList<>();
        if (result.isEmpty()) {
            warnings.add("NO_DATA_FOR_RANGE");
            warnings.add("LICENSED_SOURCE_NOT_CONFIGURED");
        }
        if (synthetic) warnings.add("SYNTHETIC_DATA_NOT_FOR_MARKET_DECISIONS");
        if (result.stream().map(CandleBar::masterVersion).distinct().count() > 1) warnings.add("INSTRUMENT_MASTER_VERSION_BOUNDARY");
        if (!matchingGaps.isEmpty()) warnings.add("DATA_GAPS_PRESENT");
        if (gapsTruncated) warnings.add("DATA_GAP_LIST_TRUNCATED");
        return new CandleSeries(instrumentId, timeframe, resolvedSource, synthetic, freshness, "FRESH".equals(freshness), "UTC",
                from, to, result.stream().map(this::toView).toList(), matchingGaps.stream().map(this::toGapView).toList(), warnings);
    }

    private boolean intersectsDemoRange(Instant from, Instant to) {
        Instant start = SyntheticDemoHistoryProvider.DEMO_DATE.atTime(9, 15).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
        Instant end = start.plus(Duration.ofMinutes(120));
        return from.isBefore(end) && to.isAfter(start);
    }

    private String freshness(CandleBar latest) {
        Instant staleBoundary = latest.bucketStart().plusSeconds(latest.timeframeSeconds()).plusSeconds(120);
        if (staleBoundary.isBefore(Instant.now())) {
            return "STALE";
        }
        return "FRESH";
    }

    private CandleBar toBar(MarketCandleEntity candle) {
        return new CandleBar(candle.getInstrumentId(), candle.getTimeframeSeconds(), candle.getBucketStart(), candle.getOpen(),
                candle.getHigh(), candle.getLow(), candle.getClose(), candle.getVolume(), candle.getTradeCount(),
                candle.getSourceId(), candle.getQuality(), candle.getMasterVersion(), true, candle.getFinalizedAt());
    }

    private CandleView toView(CandleBar bar) {
        return new CandleView(bar.bucketStart(), bar.open(), bar.high(), bar.low(), bar.close(), bar.volume(),
                bar.tradeCount(), bar.finalized(), bar.quality(), bar.masterVersion());
    }

    private GapView toGapView(MarketDataGapEntity gap) {
        return new GapView(gap.getGapStart(), gap.getGapEnd(), gap.getReason(), gap.getSourceId());
    }

    private ApiException reject(HttpStatus status, String code, String message) { return new ApiException(status, code, message); }

    public record CandleView(Instant time, java.math.BigDecimal open, java.math.BigDecimal high, java.math.BigDecimal low,
                             java.math.BigDecimal close, long volume, long tradeCount, boolean finalized,
                             String quality, String instrumentMasterVersion) { }
    public record GapView(Instant from, Instant to, String reason, String source) { }
    public record CandleSeries(UUID instrumentId, String timeframe, String source, boolean synthetic, String freshness,
                               boolean fresh, String timestampZone, Instant from, Instant to,
                               List<CandleView> candles, List<GapView> gaps, List<String> warnings) { }
}
