package com.algotradehub.backend.analysis;

import com.algotradehub.backend.marketdata.MarketCandleService;
import com.algotradehub.backend.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public class CandleTimeframeAggregator {
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalTime SESSION_START = LocalTime.of(9, 15);
    private static final LocalTime SESSION_END = LocalTime.of(15, 30);
    private static final int MINUTE_SECONDS = 60;
    private final MarketSessionCalendar sessions;

    public CandleTimeframeAggregator(MarketSessionCalendar sessions) {
        this.sessions = sessions;
    }

    public AggregatedSeries aggregate(MarketCandleService.CandleSeries source, AnalysisTimeframe timeframe) {
        List<MarketCandleService.CandleView> ordered = source.candles().stream()
                .sorted(Comparator.comparing(MarketCandleService.CandleView::time)).toList();
        if (ordered.isEmpty()) return new AggregatedSeries(List.of(), source.source(), "", false);

        String latestMasterVersion = ordered.getLast().instrumentMasterVersion();
        boolean masterBoundary = ordered.stream()
                .anyMatch(candle -> !latestMasterVersion.equals(candle.instrumentMasterVersion()));
        Map<Instant, List<MarketCandleService.CandleView>> buckets = new TreeMap<>();
        boolean invalidBars = false;
        for (MarketCandleService.CandleView candle : ordered) {
            if (!candle.finalized() || !latestMasterVersion.equals(candle.instrumentMasterVersion())) {
                invalidBars = true;
                continue;
            }
            LocalDate tradingDate = candle.time().atZone(EXCHANGE_ZONE).toLocalDate();
            if (!source.synthetic() && (!sessions.isCalendarDateSupported(tradingDate)
                    || !sessions.isRegularTradingMinute(candle.time()))) {
                invalidBars = true;
                continue;
            }
            Instant bucketStart = bucketStart(candle.time(), timeframe);
            if (bucketStart == null) continue;
            buckets.computeIfAbsent(bucketStart, ignored -> new ArrayList<>()).add(candle);
        }

        List<AggregatedCandle> result = new ArrayList<>();
        boolean incompleteBucket = false;
        Instant latestBucket = buckets.keySet().stream().max(Instant::compareTo).orElseThrow();
        for (Map.Entry<Instant, List<MarketCandleService.CandleView>> entry : buckets.entrySet()) {
            List<MarketCandleService.CandleView> bars = entry.getValue();
            bars.sort(Comparator.comparing(MarketCandleService.CandleView::time));
            int expectedMinutes = expectedMinutes(entry.getKey(), timeframe);
            if (expectedMinutes == 0) continue;
            boolean trailingInProgress = entry.getKey().equals(latestBucket)
                    && entry.getKey().isBefore(source.to())
                    && entry.getKey().plus(Duration.ofMinutes(expectedMinutes)).isAfter(source.to());
            if (trailingInProgress) {
                int elapsedMinutes = (int) Duration.between(entry.getKey(), source.to()).toMinutes();
                if (elapsedMinutes > 0 && !isCompleteBucket(entry.getKey(), bars, latestMasterVersion, elapsedMinutes)) {
                    incompleteBucket = true;
                }
                continue;
            }
            if (!isCompleteBucket(entry.getKey(), bars, latestMasterVersion, expectedMinutes)) {
                incompleteBucket = true;
                continue;
            }
            result.add(toCandle(entry.getKey(), bars));
        }
        boolean missingBucket = containsSameSessionGap(result, timeframe);
        boolean missingSession = !source.synthetic() && containsMissingTradingSession(result);
        boolean calendarCoverageComplete = source.synthetic() || calendarSupportsRange(source);
        boolean complete = source.gaps().isEmpty() && !masterBoundary && !invalidBars
                && !incompleteBucket && !missingBucket && !missingSession && calendarCoverageComplete;
        return new AggregatedSeries(List.copyOf(result), source.source(), latestMasterVersion, complete);
    }

    private Instant bucketStart(Instant time, AnalysisTimeframe timeframe) {
        var local = time.atZone(EXCHANGE_ZONE);
        if (local.getSecond() != 0 || local.getNano() != 0) return null;
        LocalTime localTime = local.toLocalTime();
        int minute = localTime.getHour() * 60 + localTime.getMinute();
        int sessionMinute = minute - SESSION_START.getHour() * 60 - SESSION_START.getMinute();
        if (sessionMinute < 0 || sessionMinute >= 375) return null;
        if (timeframe == AnalysisTimeframe.D1) {
            return local.toLocalDate().atTime(SESSION_START).atZone(EXCHANGE_ZONE).toInstant();
        }
        int alignedMinute = sessionMinute / timeframe.minutes() * timeframe.minutes();
        return local.toLocalDate().atTime(SESSION_START).plusMinutes(alignedMinute)
                .atZone(EXCHANGE_ZONE).toInstant();
    }

    private boolean isCompleteBucket(Instant start, List<MarketCandleService.CandleView> bars,
                                     String masterVersion, int expectedMinutes) {
        if (bars.size() != expectedMinutes) return false;
        for (int index = 0; index < expectedMinutes; index++) {
            MarketCandleService.CandleView candle = bars.get(index);
            if (!candle.finalized() || !masterVersion.equals(candle.instrumentMasterVersion())
                    || !candle.time().equals(start.plusSeconds((long) index * MINUTE_SECONDS))) return false;
        }
        return true;
    }

    private boolean containsSameSessionGap(List<AggregatedCandle> candles, AnalysisTimeframe timeframe) {
        for (int index = 1; index < candles.size(); index++) {
            Instant previous = candles.get(index - 1).timestamp();
            Instant current = candles.get(index).timestamp();
            LocalDate previousDate = previous.atZone(EXCHANGE_ZONE).toLocalDate();
            LocalDate currentDate = current.atZone(EXCHANGE_ZONE).toLocalDate();
            if (previousDate.equals(currentDate) && current.isAfter(previous.plus(timeframe.duration()))) return true;
        }
        return false;
    }

    private boolean containsMissingTradingSession(List<AggregatedCandle> candles) {
        for (int index = 1; index < candles.size(); index++) {
            LocalDate previousDate = candles.get(index - 1).timestamp().atZone(EXCHANGE_ZONE).toLocalDate();
            LocalDate currentDate = candles.get(index).timestamp().atZone(EXCHANGE_ZONE).toLocalDate();
            if (previousDate.equals(currentDate)) continue;
            if (sessions.nextRegularTradingDate(previousDate).filter(currentDate::equals).isEmpty()) return true;
        }
        return false;
    }

    private boolean calendarSupportsRange(MarketCandleService.CandleSeries source) {
        if (source.from() == null || source.to() == null || !source.to().isAfter(source.from())) return false;
        LocalDate date = source.from().atZone(EXCHANGE_ZONE).toLocalDate();
        LocalDate lastDate = source.to().minusNanos(1).atZone(EXCHANGE_ZONE).toLocalDate();
        while (!date.isAfter(lastDate)) {
            if (!sessions.isCalendarDateSupported(date)) return false;
            date = date.plusDays(1);
        }
        return true;
    }

    private int expectedMinutes(Instant start, AnalysisTimeframe timeframe) {
        LocalTime startTime = start.atZone(EXCHANGE_ZONE).toLocalTime();
        if (timeframe == AnalysisTimeframe.H1 && startTime.equals(LocalTime.of(15, 15))) return 0;
        if (timeframe == AnalysisTimeframe.D1) {
            return (int) Duration.between(SESSION_START, SESSION_END).toMinutes();
        }
        return timeframe.minutes();
    }

    private AggregatedCandle toCandle(Instant start, List<MarketCandleService.CandleView> bars) {
        BigDecimal high = bars.stream().map(MarketCandleService.CandleView::high).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal low = bars.stream().map(MarketCandleService.CandleView::low).min(BigDecimal::compareTo).orElseThrow();
        long volume = 0;
        for (MarketCandleService.CandleView bar : bars) volume = Math.addExact(volume, bar.volume());
        return new AggregatedCandle(start, bars.getFirst().open(), high, low, bars.getLast().close(), volume);
    }

    public record AggregatedCandle(Instant timestamp, BigDecimal open, BigDecimal high,
                                   BigDecimal low, BigDecimal close, long volume) { }

    public record AggregatedSeries(List<AggregatedCandle> candles, String source,
                                   String masterVersion, boolean complete) { }
}
