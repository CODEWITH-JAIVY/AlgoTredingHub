package com.algotradehub.backend.analysis;

import com.algotradehub.backend.common.ApiException;
import com.algotradehub.backend.marketdata.MarketCandleService;
import com.algotradehub.backend.orders.Instrument;
import com.algotradehub.backend.orders.InstrumentRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AnalysisService {
    private static final String SYNTHETIC_SOURCE = "SYNTHETIC_DEMO";
    private static final int MAX_ENGINE_CANDLES = 5_000;
    private final InstrumentRepository instruments;
    private final MarketCandleService marketCandles;
    private final CandleTimeframeAggregator aggregator;
    private final AnalysisClient analysisClient;
    private final AnalysisMarketDataGate dataRights;

    public AnalysisService(InstrumentRepository instruments, MarketCandleService marketCandles,
                           CandleTimeframeAggregator aggregator, AnalysisClient analysisClient,
                           AnalysisMarketDataGate dataRights) {
        this.instruments = instruments;
        this.marketCandles = marketCandles;
        this.aggregator = aggregator;
        this.analysisClient = analysisClient;
        this.dataRights = dataRights;
    }

    public AnalysisView analyze(UUID instrumentId, String timeframeCode, Instant from, Instant to,
                                String source, String correlationId) {
        AnalysisTimeframe requested = parseTimeframe(timeframeCode);
        if (from == null || to == null || !to.isAfter(from)) {
            throw reject(HttpStatus.BAD_REQUEST, "INVALID_ANALYSIS_RANGE", "The analysis range must be positive.");
        }
        boolean requestedSynthetic = SYNTHETIC_SOURCE.equals(source);
        if (!requestedSynthetic && !dataRights.permitsProviderAnalysis(source)) {
            throw reject(HttpStatus.UNPROCESSABLE_CONTENT, "ANALYSIS_RIGHTS_UNCONFIRMED",
                    "Analysis is disabled until the configured source and its storage, cache, display, and derived-use rights are confirmed.");
        }
        Instrument instrument = instruments.findById(instrumentId).filter(Instrument::isActive)
                .orElseThrow(() -> reject(HttpStatus.NOT_FOUND, "INSTRUMENT_UNAVAILABLE", "Instrument is missing or inactive."));
        MarketCandleService.CandleSeries sourceSeries;
        if (requestedSynthetic) {
            sourceSeries = marketCandles.query(instrumentId, "1m", from, to, source);
        } else {
            sourceSeries = marketCandles.queryForAnalysis(instrumentId, to, source);
        }
        if (sourceSeries.candles().isEmpty()) {
            throw reject(HttpStatus.UNPROCESSABLE_CONTENT, "ANALYSIS_DATA_UNAVAILABLE", "No finalized candles are available for analysis.");
        }
        boolean synthetic = sourceSeries.synthetic();
        if (synthetic && !SYNTHETIC_SOURCE.equals(sourceSeries.source())) {
            throw reject(HttpStatus.UNPROCESSABLE_CONTENT, "ANALYSIS_DATA_QUALITY_UNAVAILABLE",
                    "Synthetic candle quality does not match its declared source.");
        }
        if (!synthetic && !dataRights.permitsProviderAnalysis(sourceSeries.source())) {
            throw reject(HttpStatus.UNPROCESSABLE_CONTENT, "ANALYSIS_RIGHTS_UNCONFIRMED",
                    "Analysis is disabled until the configured source and its storage, cache, display, and derived-use rights are confirmed.");
        }
        if (!synthetic && sourceSeries.candles().stream().anyMatch(candle -> !"PROVIDER".equals(candle.quality()))) {
            throw reject(HttpStatus.UNPROCESSABLE_CONTENT, "ANALYSIS_DATA_QUALITY_UNAVAILABLE",
                    "Provider analysis accepts only provider-quality candles.");
        }

        Map<String, Object> timeframes = new LinkedHashMap<>();
        int requestedCount = 0;
        boolean requestedComplete = false;
        boolean allTimeframesComplete = true;
        for (AnalysisTimeframe timeframe : AnalysisTimeframe.values()) {
            CandleTimeframeAggregator.AggregatedSeries series = aggregator.aggregate(sourceSeries, timeframe);
            if (series.candles().isEmpty()) {
                allTimeframesComplete = false;
                continue;
            }
            if (timeframe == requested) {
                requestedCount = Math.min(series.candles().size(), MAX_ENGINE_CANDLES);
                requestedComplete = series.complete();
            }
            allTimeframesComplete &= series.complete();
            timeframes.put(timeframe.code(), toSeriesPayload(sourceSeries, series, !synthetic));
        }
        allTimeframesComplete &= timeframes.size() == AnalysisTimeframe.values().length;
        if (requestedCount == 0) {
            throw reject(HttpStatus.UNPROCESSABLE_CONTENT, "ANALYSIS_TIMEFRAME_UNAVAILABLE",
                    "There is not enough complete candle history for this timeframe.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instrument_id", instrumentId.toString());
        payload.put("tick_size", instrument.getTickSize().toPlainString());
        payload.put("requested_timeframe", requested.code());
        payload.put("timeframes", timeframes);
        AnalysisEngineResponse result = analysisClient.analyze(payload, correlationId);
        if (!instrumentId.equals(result.instrumentId()) || !requested.code().equals(result.requestedTimeframe())
                || !sourceSeries.source().equals(result.sourceId())) throw analysisUnavailable();
        boolean corporateActionsVerified = dataRights.corporateActionsVerified()
                && !sourceSeries.warnings().contains("CORPORATE_ACTIONS_UNVERIFIED");
        return toView(result, requested, requestedCount, synthetic, sourceSeries.fresh(), requestedComplete,
                allTimeframesComplete, corporateActionsVerified);
    }

    private Map<String, Object> toSeriesPayload(MarketCandleService.CandleSeries source,
                                                CandleTimeframeAggregator.AggregatedSeries series,
                                                boolean providerRightsConfirmed) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source_id", series.source());
        result.put("master_version", series.masterVersion());
        result.put("freshness", source.freshness());
        result.put("complete", series.complete());
        result.put("storage_allowed", providerRightsConfirmed);
        result.put("cache_allowed", providerRightsConfirmed);
        result.put("display_allowed", providerRightsConfirmed);
        result.put("derived_analytics_allowed", providerRightsConfirmed);
        result.put("corporate_action_warning", providerRightsConfirmed
                && (!dataRights.corporateActionsVerified()
                || source.warnings().contains("CORPORATE_ACTIONS_UNVERIFIED")));
        result.put("gaps", source.gaps().stream().limit(1000).map(gap -> Map.of(
                "start", gap.from().toString(), "end", gap.to().toString())).toList());
        List<Map<String, Object>> candles = new ArrayList<>();
        int firstIndex = Math.max(0, series.candles().size() - MAX_ENGINE_CANDLES);
        for (CandleTimeframeAggregator.AggregatedCandle candle : series.candles().subList(firstIndex, series.candles().size())) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("timestamp", candle.timestamp().toString());
            value.put("open", candle.open().toPlainString());
            value.put("high", candle.high().toPlainString());
            value.put("low", candle.low().toPlainString());
            value.put("close", candle.close().toPlainString());
            value.put("volume", candle.volume());
            value.put("finalized", true);
            candles.add(value);
        }
        result.put("candles", candles);
        return result;
    }

    private AnalysisView toView(AnalysisEngineResponse response, AnalysisTimeframe timeframe,
                                int candleCount, boolean synthetic, boolean sourceFresh, boolean requestedComplete,
                                boolean allTimeframesComplete, boolean corporateActionsVerified) {
        boolean eligible = !synthetic && sourceFresh && "FRESH".equals(response.freshness()) && response.complete()
                && requestedComplete && allTimeframesComplete && corporateActionsVerified
                && response.actionable() && "AVAILABLE".equals(response.signalStatus());
        List<String> warnings = new ArrayList<>();
        if (response.warnings() != null) {
            warnings.addAll(response.warnings().stream()
                    .filter(value -> value != null && value.matches("[A-Z0-9_]{1,80}")).limit(30).toList());
        }
        if (!synthetic && !sourceFresh && !warnings.contains("STALE_DATA")) warnings.add("STALE_DATA");
        if (!requestedComplete || !allTimeframesComplete) {
            if (!warnings.contains("INCOMPLETE_CANDLE_SERIES")) warnings.add("INCOMPLETE_CANDLE_SERIES");
        }
        if (!synthetic && !corporateActionsVerified && !warnings.contains("CORPORATE_ACTIONS_UNVERIFIED")) {
            warnings.add("CORPORATE_ACTIONS_UNVERIFIED");
        }
        warnings = warnings.stream().distinct().limit(30).toList();
        List<String> english = List.of();
        List<String> hindi = List.of();
        if (eligible && response.summary() != null) {
            if (response.summary().english() != null) english = response.summary().english().stream().limit(6).toList();
            if (response.summary().hindi() != null) hindi = response.summary().hindi().stream().limit(6).toList();
        } else if (synthetic) {
            english = List.of("These candles are simulated, so directional analysis is withheld.",
                    "Indicator values describe a development fixture, not current market data.");
            hindi = List.of("Yeh candles simulated hain, isliye directional analysis nahi dikhaya ja raha hai.",
                    "Indicator values sirf development sample hain, current market data nahi.");
        } else {
            english = List.of("Directional analysis is withheld because a data-quality or safety check did not pass.",
                    "Review the source, freshness, completeness, and corporate-action status before using these values.");
            hindi = List.of("Data-quality ya safety check pass nahi hua, isliye directional analysis roka gaya hai.",
                    "In values ka use karne se pehle source, freshness, completeness aur corporate-action status dekhein.");
        }
        String signalStatus = "SUPPRESSED";
        String bias = "NEUTRAL";
        int zoneCount = 0;
        if (eligible) {
            signalStatus = "AVAILABLE";
            bias = response.bias();
            if (response.zones() != null) zoneCount = response.zones().size();
        }
        String freshness = response.freshness();
        if (!sourceFresh) freshness = "STALE";
        if (synthetic) freshness = "SIMULATED";
        return new AnalysisView(response.instrumentId(), timeframe.code(), response.sourceId(), freshness,
                response.dataTimestamp(), response.algorithmVersion(), response.complete() && requestedComplete,
                signalStatus, eligible, bias, candleCount, zoneCount,
                response.indicators(), warnings, english, hindi);
    }

    private AnalysisTimeframe parseTimeframe(String code) {
        try {
            return AnalysisTimeframe.fromCode(code);
        } catch (IllegalArgumentException exception) {
            throw reject(HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_ANALYSIS_TIMEFRAME", "Choose 5m, 15m, 1h, or 1d.");
        }
    }

    private ApiException analysisUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ANALYSIS_UNAVAILABLE",
                "Analysis is temporarily unavailable. Try again later.");
    }

    private ApiException reject(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message);
    }

    public record AnalysisView(UUID instrumentId, String timeframe, String source, String freshness,
                               Instant dataTimestamp, String algorithmVersion, boolean complete,
                               String signalStatus, boolean actionable, String bias, int candleCount,
                               int zoneCount, AnalysisEngineResponse.Indicators indicators,
                               List<String> warnings, List<String> summaryEnglish,
                               List<String> summaryHindi) { }
}
