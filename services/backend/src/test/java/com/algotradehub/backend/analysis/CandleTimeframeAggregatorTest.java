package com.algotradehub.backend.analysis;

import com.algotradehub.backend.marketdata.MarketCandleService;
import com.algotradehub.backend.marketdata.NseBseEquityFnoDemoCalendar;
import com.algotradehub.backend.marketdata.SyntheticDemoHistoryProvider;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandleTimeframeAggregatorTest {
    private static final UUID ID = UUID.fromString("7b774ec2-a516-4a29-b2b4-3945256c7c2a");
    private static final Instant START = SyntheticDemoHistoryProvider.DEMO_DATE.atTime(LocalTime.of(9, 15))
            .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Kolkata");
    private final CandleTimeframeAggregator aggregator = new CandleTimeframeAggregator(new NseBseEquityFnoDemoCalendar());
    private final SyntheticDemoHistoryProvider provider = new SyntheticDemoHistoryProvider();

    @Test
    void aggregatesOnlyFullExchangeAlignedBars() {
        MarketCandleService.CandleSeries source = seriesFromBars(provider.samples(ID, new BigDecimal("100.00"),
                new BigDecimal("0.05"), "DEMO-1"));

        CandleTimeframeAggregator.AggregatedSeries five = aggregator.aggregate(source, AnalysisTimeframe.M5);
        CandleTimeframeAggregator.AggregatedSeries fifteen = aggregator.aggregate(source, AnalysisTimeframe.M15);
        CandleTimeframeAggregator.AggregatedSeries hourly = aggregator.aggregate(source, AnalysisTimeframe.H1);
        CandleTimeframeAggregator.AggregatedSeries daily = aggregator.aggregate(source, AnalysisTimeframe.D1);

        assertEquals(24, five.candles().size());
        assertEquals(8, fifteen.candles().size());
        assertEquals(2, hourly.candles().size());
        assertTrue(five.complete());
        assertTrue(fifteen.complete());
        assertTrue(hourly.complete());
        assertTrue(daily.candles().isEmpty());
        assertEquals(START, five.candles().getFirst().timestamp());
        assertEquals(START.plusSeconds(23 * 300L), five.candles().getLast().timestamp());
    }

    @Test
    void dropsAnIncompleteBucketAndMarksTheSeriesIncomplete() {
        List<MarketCandleService.CandleView> candles = provider.samples(ID, new BigDecimal("100.00"),
                        new BigDecimal("0.05"), "DEMO-1").stream()
                .filter(candle -> !candle.bucketStart().equals(START.plus(Duration.ofMinutes(2))))
                .map(candle -> new MarketCandleService.CandleView(candle.bucketStart(), candle.open(), candle.high(),
                        candle.low(), candle.close(), candle.volume(), candle.tradeCount(), candle.finalized(),
                        candle.quality(), candle.masterVersion())).toList();

        CandleTimeframeAggregator.AggregatedSeries result = aggregator.aggregate(seriesFromViews(candles), AnalysisTimeframe.M5);

        assertEquals(23, result.candles().size());
        assertFalse(result.complete());
        assertFalse(result.candles().stream().anyMatch(candle -> candle.timestamp().equals(START)));
    }

    @Test
    void recognizesPublishedHolidayBetweenTwoAdjacentTradingSessions() {
        MarketCandleService.CandleSeries source = providerSeriesForDates(
                LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 21));

        CandleTimeframeAggregator.AggregatedSeries daily = aggregator.aggregate(source, AnalysisTimeframe.D1);

        assertEquals(2, daily.candles().size());
        assertTrue(daily.complete());
    }

    @Test
    void detectsAnEntireMissingRegularTradingSessionAcrossDates() {
        MarketCandleService.CandleSeries source = providerSeriesForDates(
                LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 22));

        CandleTimeframeAggregator.AggregatedSeries daily = aggregator.aggregate(source, AnalysisTimeframe.D1);

        assertEquals(2, daily.candles().size());
        assertFalse(daily.complete());
    }

    @Test
    void failsClosedWhenAnalysisWindowCrossesAnUnmodeledSpecialSession() {
        MarketCandleService.CandleSeries source = providerSeriesForDates(
                LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 9));

        CandleTimeframeAggregator.AggregatedSeries daily = aggregator.aggregate(source, AnalysisTimeframe.D1);

        assertEquals(2, daily.candles().size());
        assertFalse(daily.complete());
    }

    @Test
    void ignoresTheExpectedShortFinalHourlyBucketAndCurrentPartialBucket() {
        LocalDate date = LocalDate.of(2026, 10, 21);
        MarketCandleService.CandleSeries fullSession = providerSeriesForDates(date);
        CandleTimeframeAggregator.AggregatedSeries hourly = aggregator.aggregate(fullSession, AnalysisTimeframe.H1);
        assertEquals(6, hourly.candles().size());
        assertTrue(hourly.complete());

        Instant start = date.atTime(9, 15).atZone(EXCHANGE_ZONE).toInstant();
        List<MarketCandleService.CandleView> partial = fullSession.candles().stream().limit(4).toList();
        MarketCandleService.CandleSeries inProgress = new MarketCandleService.CandleSeries(ID, "1m", "BROKER_FEED",
                false, "FRESH", true, "UTC", start, start.plusSeconds(4 * 60), partial, List.of(), List.of());
        CandleTimeframeAggregator.AggregatedSeries fiveMinute = aggregator.aggregate(inProgress, AnalysisTimeframe.M5);
        assertTrue(fiveMinute.candles().isEmpty());
        assertTrue(fiveMinute.complete());

        List<MarketCandleService.CandleView> missingMaturedMinute = partial.stream()
                .filter(candle -> !candle.time().equals(start.plusSeconds(60))).toList();
        MarketCandleService.CandleSeries brokenInProgress = new MarketCandleService.CandleSeries(ID, "1m", "BROKER_FEED",
                false, "FRESH", true, "UTC", start, start.plusSeconds(4 * 60), missingMaturedMinute,
                List.of(), List.of());
        assertFalse(aggregator.aggregate(brokenInProgress, AnalysisTimeframe.M5).complete());
    }

    private MarketCandleService.CandleSeries seriesFromBars(List<com.algotradehub.backend.marketdata.CandleBar> bars) {
        List<MarketCandleService.CandleView> candles = bars.stream().map(candle -> new MarketCandleService.CandleView(
                candle.bucketStart(), candle.open(), candle.high(), candle.low(), candle.close(), candle.volume(),
                candle.tradeCount(), candle.finalized(), candle.quality(), candle.masterVersion())).toList();
        return seriesFromViews(candles);
    }

    private MarketCandleService.CandleSeries seriesFromViews(List<MarketCandleService.CandleView> candles) {
        Instant end = START.plus(Duration.ofMinutes(120));
        return new MarketCandleService.CandleSeries(ID, "1m", "SYNTHETIC_DEMO", true, "SIMULATED", false,
                "UTC", START, end, candles, List.of(), List.of());
    }

    private MarketCandleService.CandleSeries providerSeriesForDates(LocalDate... dates) {
        List<MarketCandleService.CandleView> candles = new java.util.ArrayList<>();
        for (LocalDate date : dates) {
            Instant start = date.atTime(9, 15).atZone(EXCHANGE_ZONE).toInstant();
            for (int minute = 0; minute < 375; minute++) {
                BigDecimal price = new BigDecimal("100.00");
                candles.add(new MarketCandleService.CandleView(start.plusSeconds(minute * 60L), price, price, price,
                        price, 10, 1, true, "PROVIDER", "MASTER-1"));
            }
        }
        Instant from = candles.getFirst().time();
        Instant to = candles.getLast().time().plusSeconds(60);
        return new MarketCandleService.CandleSeries(ID, "1m", "BROKER_FEED", false, "FRESH", true,
                "UTC", from, to, candles, List.of(), List.of());
    }
}
