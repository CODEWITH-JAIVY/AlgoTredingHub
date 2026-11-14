package com.algotradehub.backend.analysis;

import com.algotradehub.backend.common.ApiException;
import com.algotradehub.backend.marketdata.MarketCandleService;
import com.algotradehub.backend.marketdata.NseBseEquityFnoDemoCalendar;
import com.algotradehub.backend.marketdata.SyntheticDemoHistoryProvider;
import com.algotradehub.backend.orders.Instrument;
import com.algotradehub.backend.orders.InstrumentRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisServiceTest {
    private static final UUID ID = UUID.fromString("7b774ec2-a516-4a29-b2b4-3945256c7c2a");
    private static final Instant START = SyntheticDemoHistoryProvider.DEMO_DATE.atTime(LocalTime.of(9, 15))
            .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
    private static final Instant END = START.plus(Duration.ofMinutes(120));
    private static final Instant PROVIDER_START = Instant.parse("2026-10-21T03:45:00Z");
    private static final Instant PROVIDER_END = PROVIDER_START.plus(Duration.ofMinutes(120));
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final MarketCandleService marketCandles = mock(MarketCandleService.class);
    private final AnalysisClient client = mock(AnalysisClient.class);
    private final CandleTimeframeAggregator timeframeAggregator =
            new CandleTimeframeAggregator(new NseBseEquityFnoDemoCalendar());
    private final Instrument instrument = new Instrument(ID, "DEMO-NSE-001", "NSE", "CASH", "DEMO-ALPHA", "EQUITY", 1,
            new BigDecimal("0.05"), new BigDecimal("100.00"), "DEMO-1");
    private final SyntheticDemoHistoryProvider demoHistory = new SyntheticDemoHistoryProvider();
    private final AnalysisService service = new AnalysisService(instruments, marketCandles,
            timeframeAggregator, client, closedGate());

    @Test
    void internalPayloadKeepsSyntheticRightsFalseAndSuppressesEngineOutput() {
        when(instruments.findById(ID)).thenReturn(Optional.of(instrument));
        when(marketCandles.query(ID, "1m", START, END, "SYNTHETIC_DEMO")).thenReturn(demoSeries());
        when(client.analyze(org.mockito.ArgumentMatchers.anyMap(), eq("correlation-1"))).thenReturn(engineResponse());

        AnalysisService.AnalysisView result = service.analyze(ID, "5m", START, END, "SYNTHETIC_DEMO", "correlation-1");

        ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(client).analyze(payloadCaptor.capture(), eq("correlation-1"));
        Map<?, ?> payload = payloadCaptor.getValue();
        Map<?, ?> timeframes = (Map<?, ?>) payload.get("timeframes");
        Map<?, ?> fiveMinute = (Map<?, ?>) timeframes.get("5m");
        Map<?, ?> fifteenMinute = (Map<?, ?>) timeframes.get("15m");
        Map<?, ?> hourly = (Map<?, ?>) timeframes.get("1h");
        assertEquals(24, ((List<?>) fiveMinute.get("candles")).size());
        assertEquals(8, ((List<?>) fifteenMinute.get("candles")).size());
        assertEquals(2, ((List<?>) hourly.get("candles")).size());
        assertEquals(false, fiveMinute.get("storage_allowed"));
        assertEquals(false, fiveMinute.get("display_allowed"));
        assertEquals(false, fiveMinute.get("derived_analytics_allowed"));
        assertEquals("SIMULATED", fiveMinute.get("freshness"));
        assertEquals("SUPPRESSED", result.signalStatus());
        assertEquals("NEUTRAL", result.bias());
        assertFalse(result.actionable());
        assertEquals(0, result.zoneCount());
        assertEquals(24, result.candleCount());
        assertEquals(new BigDecimal("32.123456"), result.indicators().rsi14());
        assertEquals(new BigDecimal("1.250000"), result.indicators().latestVolumeRatio());
    }

    @Test
    void unconfirmedNonSyntheticSourceIsRejectedBeforeHistoryRead() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.analyze(ID, "5m", START, END, "BROKER_FEED", "correlation-1"));

        assertEquals("ANALYSIS_RIGHTS_UNCONFIRMED", error.code());
        verify(marketCandles, org.mockito.Mockito.never()).queryForAnalysis(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(client, org.mockito.Mockito.never()).analyze(org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void providerAnalysisRequiresExactSourceAndProviderQuality() {
        when(instruments.findById(ID)).thenReturn(Optional.of(instrument));
        MarketCandleService.CandleSeries wrongSource = providerSeries("OTHER_FEED", "PROVIDER");
        when(marketCandles.queryForAnalysis(ID, PROVIDER_END, "OTHER_FEED")).thenReturn(wrongSource);
        AnalysisService allowedForBroker = service(providerGate("BROKER_FEED", true, true));

        ApiException sourceError = assertThrows(ApiException.class,
                () -> allowedForBroker.analyze(ID, "5m", PROVIDER_START, PROVIDER_END, "OTHER_FEED", "correlation-1"));
        assertEquals("ANALYSIS_RIGHTS_UNCONFIRMED", sourceError.code());

        MarketCandleService.CandleSeries mixedQuality = providerSeries("BROKER_FEED", "SYNTHETIC");
        when(marketCandles.queryForAnalysis(ID, PROVIDER_END, "BROKER_FEED")).thenReturn(mixedQuality);
        ApiException qualityError = assertThrows(ApiException.class,
                () -> allowedForBroker.analyze(ID, "5m", PROVIDER_START, PROVIDER_END, "BROKER_FEED", "correlation-1"));
        assertEquals("ANALYSIS_DATA_QUALITY_UNAVAILABLE", qualityError.code());
        verify(client, org.mockito.Mockito.never()).analyze(org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void providerPayloadCarriesSeparateRightsAndCorporateActionGate() {
        when(instruments.findById(ID)).thenReturn(Optional.of(instrument));
        when(marketCandles.queryForAnalysis(ID, PROVIDER_END, "BROKER_FEED"))
                .thenReturn(providerSeries("BROKER_FEED", "PROVIDER"));
        when(client.analyze(org.mockito.ArgumentMatchers.anyMap(), eq("correlation-1")))
                .thenReturn(providerEngineResponse());
        AnalysisService rightsClearedButActionsUnknown = service(providerGate("BROKER_FEED", false, true));

        AnalysisService.AnalysisView withheld = rightsClearedButActionsUnknown.analyze(
                ID, "5m", PROVIDER_START, PROVIDER_END, "BROKER_FEED", "correlation-1");

        ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(client).analyze(payloadCaptor.capture(), eq("correlation-1"));
        Map<?, ?> timeframes = (Map<?, ?>) payloadCaptor.getValue().get("timeframes");
        Map<?, ?> fiveMinute = (Map<?, ?>) timeframes.get("5m");
        assertEquals(true, fiveMinute.get("storage_allowed"));
        assertEquals(true, fiveMinute.get("cache_allowed"));
        assertEquals(true, fiveMinute.get("display_allowed"));
        assertEquals(true, fiveMinute.get("derived_analytics_allowed"));
        assertEquals(true, fiveMinute.get("corporate_action_warning"));
        assertFalse(withheld.actionable());
        assertEquals("SUPPRESSED", withheld.signalStatus());
        assertEquals("NEUTRAL", withheld.bias());
        assertEquals(0, withheld.zoneCount());
    }

    @Test
    void rightsClearedProviderStillNeedsCompleteHigherTimeframeHistory() {
        when(instruments.findById(ID)).thenReturn(Optional.of(instrument));
        when(marketCandles.queryForAnalysis(ID, PROVIDER_END, "BROKER_FEED"))
                .thenReturn(providerSeries("BROKER_FEED", "PROVIDER"));
        when(client.analyze(org.mockito.ArgumentMatchers.anyMap(), eq("correlation-1")))
                .thenReturn(providerEngineResponse());
        AnalysisService fullyCleared = service(providerGate("BROKER_FEED", true, true));

        AnalysisService.AnalysisView result = fullyCleared.analyze(ID, "5m", PROVIDER_START, PROVIDER_END, "BROKER_FEED", "correlation-1");

        assertEquals("SUPPRESSED", result.signalStatus());
        assertEquals("NEUTRAL", result.bias());
        assertEquals(0, result.zoneCount());
        assertFalse(result.actionable());
        assertEquals(true, result.warnings().contains("INCOMPLETE_CANDLE_SERIES"));
    }

    @Test
    void sourceGapsCannotBeOverriddenByAnActionableEngineResponse() {
        when(instruments.findById(ID)).thenReturn(Optional.of(instrument));
        MarketCandleService.CandleSeries clean = providerSeries("BROKER_FEED", "PROVIDER");
        MarketCandleService.CandleSeries withGap = new MarketCandleService.CandleSeries(clean.instrumentId(),
                clean.timeframe(), clean.source(), clean.synthetic(), clean.freshness(), clean.fresh(),
                clean.timestampZone(), clean.from(), clean.to(), clean.candles(),
                List.of(new MarketCandleService.GapView(START.plusSeconds(60), START.plusSeconds(120),
                        "MISSING_CANDLE", "BROKER_FEED")), List.of("DATA_GAPS_PRESENT"));
        when(marketCandles.queryForAnalysis(ID, PROVIDER_END, "BROKER_FEED")).thenReturn(withGap);
        when(client.analyze(org.mockito.ArgumentMatchers.anyMap(), eq("correlation-1")))
                .thenReturn(providerEngineResponse());
        AnalysisService fullyCleared = service(providerGate("BROKER_FEED", true, true));

        AnalysisService.AnalysisView result = fullyCleared.analyze(ID, "5m", PROVIDER_START, PROVIDER_END, "BROKER_FEED", "correlation-1");

        assertFalse(result.actionable());
        assertFalse(result.complete());
        assertEquals("SUPPRESSED", result.signalStatus());
        ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(client).analyze(payloadCaptor.capture(), eq("correlation-1"));
        Map<?, ?> timeframes = (Map<?, ?>) payloadCaptor.getValue().get("timeframes");
        Map<?, ?> fiveMinute = (Map<?, ?>) timeframes.get("5m");
        assertEquals(1, ((List<?>) fiveMinute.get("gaps")).size());
        assertEquals(true, result.warnings().contains("INCOMPLETE_CANDLE_SERIES"));
    }

    @Test
    void staleSourceCannotBeMadeFreshByAnAnalysisResponse() {
        when(instruments.findById(ID)).thenReturn(Optional.of(instrument));
        MarketCandleService.CandleSeries fresh = providerSeries("BROKER_FEED", "PROVIDER");
        MarketCandleService.CandleSeries stale = new MarketCandleService.CandleSeries(fresh.instrumentId(),
                fresh.timeframe(), fresh.source(), fresh.synthetic(), "STALE", false, fresh.timestampZone(),
                fresh.from(), fresh.to(), fresh.candles(), List.of(), List.of());
        when(marketCandles.queryForAnalysis(ID, PROVIDER_END, "BROKER_FEED")).thenReturn(stale);
        when(client.analyze(org.mockito.ArgumentMatchers.anyMap(), eq("correlation-1")))
                .thenReturn(providerEngineResponse());
        AnalysisService fullyCleared = service(providerGate("BROKER_FEED", true, true));

        AnalysisService.AnalysisView result = fullyCleared.analyze(ID, "5m", PROVIDER_START, PROVIDER_END, "BROKER_FEED", "correlation-1");

        assertFalse(result.actionable());
        assertEquals("STALE", result.freshness());
        assertEquals("SUPPRESSED", result.signalStatus());
        assertEquals(true, result.warnings().contains("STALE_DATA"));
        assertEquals("Directional analysis is withheld because a data-quality or safety check did not pass.",
                result.summaryEnglish().getFirst());
    }

    @Test
    void everyProviderRightsFlagIsRequiredAndSourceMatchIsExact() {
        boolean[][] missingOneRight = {
                {false, true, true, true}, {true, false, true, true},
                {true, true, false, true}, {true, true, true, false},
        };
        for (boolean[] rights : missingOneRight) {
            AnalysisMarketDataGate gate = new AnalysisMarketDataGate("BROKER_FEED", rights[0], rights[1],
                    rights[2], rights[3], true);
            assertFalse(gate.permitsProviderAnalysis("BROKER_FEED"));
        }
        AnalysisMarketDataGate gate = providerGate("BROKER_FEED", true, true);
        assertFalse(gate.permitsProviderAnalysis("broker_feed"));
        assertFalse(gate.permitsProviderAnalysis("OTHER_FEED"));
    }

    @Test
    void rejectsUnknownTimeframeBeforeQueryingMarketData() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.analyze(ID, "1m", START, END, "SYNTHETIC_DEMO", "correlation-1"));

        assertEquals("UNSUPPORTED_ANALYSIS_TIMEFRAME", error.code());
        verify(marketCandles, org.mockito.Mockito.never()).query(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(marketCandles, org.mockito.Mockito.never()).queryForAnalysis(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rejectsAnInvertedProviderRangeBeforeCheckingRightsOrReadingHistory() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.analyze(ID, "5m", END, END, "BROKER_FEED", "correlation-1"));

        assertEquals("INVALID_ANALYSIS_RANGE", error.code());
        verify(marketCandles, org.mockito.Mockito.never()).queryForAnalysis(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    private MarketCandleService.CandleSeries demoSeries() {
        List<MarketCandleService.CandleView> candles = demoHistory.samples(ID, instrument.getMockPrice(),
                        instrument.getTickSize(), instrument.getMasterVersion()).stream()
                .map(candle -> new MarketCandleService.CandleView(candle.bucketStart(), candle.open(), candle.high(),
                        candle.low(), candle.close(), candle.volume(), candle.tradeCount(), candle.finalized(),
                        candle.quality(), candle.masterVersion())).toList();
        return new MarketCandleService.CandleSeries(ID, "1m", "SYNTHETIC_DEMO", true, "SIMULATED", false,
                "UTC", START, END, candles, List.of(), List.of());
    }

    private AnalysisEngineResponse engineResponse() {
        return new AnalysisEngineResponse(ID, "5m", START.plus(Duration.ofMinutes(115)), "SYNTHETIC_DEMO", "STALE",
                true, "AVAILABLE", true, "BULLISH", "analysis-v1", List.of("SYNTHETIC_DATA_NOT_FOR_MARKET_DECISIONS"),
                List.of(Map.of("side", "DEMAND")),
                new AnalysisEngineResponse.Indicators(new BigDecimal("100.00"), new BigDecimal("99.00"), null,
                        null, new BigDecimal("32.123456"), new BigDecimal("0.25"), new BigDecimal("0.20"),
                        new BigDecimal("0.05"), new BigDecimal("1.10"), new BigDecimal("800"),
                        new BigDecimal("1.250000")),
                new AnalysisEngineResponse.Summary(List.of("Synthetic."), List.of("सिंथेटिक।")));
    }

    private AnalysisEngineResponse providerEngineResponse() {
        AnalysisEngineResponse response = engineResponse();
        return new AnalysisEngineResponse(response.instrumentId(), response.requestedTimeframe(), response.dataTimestamp(),
                "BROKER_FEED", "FRESH", true, "AVAILABLE", true, "BULLISH", response.algorithmVersion(),
                List.of(), response.zones(), response.indicators(), response.summary());
    }

    private MarketCandleService.CandleSeries providerSeries(String source, String quality) {
        Duration shift = Duration.between(START, PROVIDER_START);
        List<MarketCandleService.CandleView> candles = demoSeries().candles().stream()
                .map(candle -> new MarketCandleService.CandleView(candle.time(), candle.open(), candle.high(), candle.low(),
                        candle.close(), candle.volume(), candle.tradeCount(), candle.finalized(), quality,
                        candle.instrumentMasterVersion())).toList();
        List<MarketCandleService.CandleView> shifted = candles.stream()
                .map(candle -> new MarketCandleService.CandleView(candle.time().plus(shift), candle.open(), candle.high(),
                        candle.low(), candle.close(), candle.volume(), candle.tradeCount(), candle.finalized(),
                        candle.quality(), candle.instrumentMasterVersion())).toList();
        return new MarketCandleService.CandleSeries(ID, "1m", source, false, "FRESH", true, "UTC", PROVIDER_START, PROVIDER_END,
                shifted, List.of(), List.of());
    }

    private AnalysisService service(AnalysisMarketDataGate gate) {
        return new AnalysisService(instruments, marketCandles, timeframeAggregator, client, gate);
    }

    private AnalysisMarketDataGate closedGate() {
        return new AnalysisMarketDataGate("", false, false, false, false, false);
    }

    private AnalysisMarketDataGate providerGate(String source, boolean corporateActionsVerified, boolean rightsConfirmed) {
        return new AnalysisMarketDataGate(source, rightsConfirmed, rightsConfirmed, rightsConfirmed,
                rightsConfirmed, corporateActionsVerified);
    }
}
