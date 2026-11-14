package com.algotradehub.backend.alerts;

import com.algotradehub.backend.marketdata.CandleBar;
import com.algotradehub.backend.marketdata.MarketCandleEntity;
import com.algotradehub.backend.marketdata.MarketCandleRepository;
import com.algotradehub.backend.marketdata.MarketSessionCalendar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceAlertEvaluatorTest {
    private static final UUID INSTRUMENT = UUID.fromString("86b8c400-dcac-46d0-b465-29e12ca80103");
    private static final UUID USER = UUID.fromString("ab4f1e73-16bf-4cbd-8f32-22ef85a80c0e");
    private static final Instant NOW = Instant.parse("2026-10-22T04:01:02Z");
    @Mock private AlertRuleRepository rules;
    @Mock private AlertEventRepository events;
    @Mock private MarketCandleRepository candles;
    @Mock private MarketSessionCalendar sessions;
    private AlertRuleEntity rule;

    @BeforeEach void setUp() {
        rule = new AlertRuleEntity(UUID.randomUUID(), USER, INSTRUMENT, "ABOVE", new BigDecimal("100.00"),
                "WAITING_FOR_DATA", "WAITING_FOR_FRESH_CANDLE", NOW.minusSeconds(300));
        when(rules.lockActiveForInstrument(INSTRUMENT)).thenAnswer(ignored -> List.of(rule));
    }

    @Test void syntheticCandlesPauseRulesWithoutCreatingNotifications() {
        PriceAlertEvaluator evaluator = evaluator(new AlertMarketDataGate(true, "LICENSED_FEED", true, true, true, 120));
        evaluator.evaluate(candle("SYNTHETIC_DEMO", "SYNTHETIC", NOW.minusSeconds(62), NOW));

        assertEquals("PAUSED", rule.getState());
        assertEquals("UNAPPROVED_OR_SYNTHETIC_SOURCE", rule.getReasonCode());
        verify(events, never()).save(any(AlertEventEntity.class));
    }

    @Test void missingRightsKeepNewRulesPaused() {
        AlertMarketDataGate gate = new AlertMarketDataGate(true, "LICENSED_FEED", true, true, false, 120);
        assertEquals("LICENSED_SOURCE_NOT_CONFIRMED", gate.configurationPauseReason());
        PriceAlertEvaluator evaluator = evaluator(gate);
        evaluator.evaluate(candle("LICENSED_FEED", "PROVIDER", NOW.minusSeconds(62), NOW));

        assertEquals("PAUSED", rule.getState());
        assertEquals("LICENSED_SOURCE_NOT_CONFIRMED", rule.getReasonCode());
        verify(events, never()).save(any(AlertEventEntity.class));
    }

    @Test void missingStorageRightsKeepNewRulesPaused() {
        AlertMarketDataGate gate = new AlertMarketDataGate(true, "LICENSED_FEED", false, true, true, 120);
        assertEquals("LICENSED_SOURCE_NOT_CONFIRMED", gate.configurationPauseReason());
        PriceAlertEvaluator evaluator = evaluator(gate);
        evaluator.evaluate(candle("LICENSED_FEED", "PROVIDER", NOW.minusSeconds(62), NOW));

        assertEquals("PAUSED", rule.getState());
        assertEquals("LICENSED_SOURCE_NOT_CONFIRMED", rule.getReasonCode());
        verify(events, never()).save(any(AlertEventEntity.class));
    }

    @Test void nonOneMinuteCandlesPauseRulesWithoutCreatingNotifications() {
        PriceAlertEvaluator evaluator = evaluator(new AlertMarketDataGate(true, "LICENSED_FEED", true, true, true, 120));
        CandleBar fiveMinute = new CandleBar(INSTRUMENT, 300, Instant.parse("2026-10-22T04:00:00Z"),
                new BigDecimal("99.90"), new BigDecimal("100.50"), new BigDecimal("99.50"),
                new BigDecimal("100.20"), 1000, 12, "LICENSED_FEED", "PROVIDER", "MASTER-1", true, NOW);

        evaluator.evaluate(fiveMinute);

        assertEquals("PAUSED", rule.getState());
        assertEquals("UNSUPPORTED_TIMEFRAME", rule.getReasonCode());
        verify(events, never()).save(any(AlertEventEntity.class));
    }

    @Test void staleProviderCandlesAreSuppressedAndClearTheOldBaseline() {
        rule.armAt(new BigDecimal("99.00"), Instant.parse("2026-10-22T03:49:00Z"), "MASTER-1", NOW.minusSeconds(120));
        PriceAlertEvaluator evaluator = evaluator(new AlertMarketDataGate(true, "LICENSED_FEED", true, true, true, 120));
        evaluator.evaluate(candle("LICENSED_FEED", "PROVIDER", Instant.parse("2026-10-22T03:50:00Z"),
                Instant.parse("2026-10-22T03:51:00Z")));

        assertEquals("PAUSED", rule.getState());
        assertEquals("STALE_DATA", rule.getReasonCode());
        assertNull(rule.getBaselinePrice());
        verify(events, never()).save(any(AlertEventEntity.class));
    }

    @Test void alertTriggersOnceOnlyOnAContinuousFreshApprovedFinalizedCandle() {
        Instant previousCandle = Instant.parse("2026-10-22T03:59:00Z");
        rule.armAt(new BigDecimal("99.00"), previousCandle, "MASTER-1", NOW.minusSeconds(60));
        PriceAlertEvaluator evaluator = evaluator(new AlertMarketDataGate(true, "LICENSED_FEED", true, true, true, 120));

        evaluator.evaluate(candle("LICENSED_FEED", "PROVIDER", Instant.parse("2026-10-22T04:00:00Z"), NOW));

        assertEquals("TRIGGERED", rule.getState());
        assertFalse(rule.isActive());
        assertEquals(NOW, rule.getTriggeredAt());
        ArgumentCaptor<AlertEventEntity> event = ArgumentCaptor.forClass(AlertEventEntity.class);
        verify(events).save(event.capture());
        assertEquals(new BigDecimal("100.00"), event.getValue().getThresholdPrice());
        assertEquals(Instant.parse("2026-10-22T04:00:00Z"), event.getValue().getCandleTime());
        assertEquals("LICENSED_FEED", event.getValue().getSourceId());
    }

    @Test void gapsAndMasterVersionChangesResetTheBaselineWithoutInventingCrossings() {
        rule.armAt(new BigDecimal("99.00"), Instant.parse("2026-10-22T03:58:00Z"), "MASTER-0", NOW.minusSeconds(120));
        PriceAlertEvaluator evaluator = evaluator(new AlertMarketDataGate(true, "LICENSED_FEED", true, true, true, 120));

        evaluator.evaluate(candle("LICENSED_FEED", "PROVIDER", Instant.parse("2026-10-22T04:00:00Z"), NOW));

        assertEquals("ARMED", rule.getState());
        assertEquals(new BigDecimal("100.20"), rule.getBaselinePrice());
        assertEquals("MASTER-1", rule.getBaselineMasterVersion());
        verify(events, never()).save(any(AlertEventEntity.class));
    }

    @Test void zoneTouchRequiresEnteringTheConfiguredBandFromOutsideAndTriggersOnce() {
        rule = new AlertRuleEntity(UUID.randomUUID(), USER, INSTRUMENT, "ZONE_TOUCH", null,
                new BigDecimal("100.00"), new BigDecimal("105.00"), "ARMED", null, NOW.minusSeconds(60));
        rule.armAt(new BigDecimal("99.00"), Instant.parse("2026-10-22T03:59:00Z"), "MASTER-1", NOW.minusSeconds(60));
        PriceAlertEvaluator evaluator = evaluator(new AlertMarketDataGate(true, "LICENSED_FEED", true, true, true, 120));

        evaluator.evaluate(candle("LICENSED_FEED", "PROVIDER", Instant.parse("2026-10-22T04:00:00Z"), NOW));

        assertEquals("TRIGGERED", rule.getState());
        assertFalse(rule.isActive());
        ArgumentCaptor<AlertEventEntity> event = ArgumentCaptor.forClass(AlertEventEntity.class);
        verify(events).save(event.capture());
        assertEquals("ZONE_TOUCH", event.getValue().getRuleType());
        assertEquals(new BigDecimal("100.00"), event.getValue().getThresholdPrice());
        assertEquals(new BigDecimal("105.00"), event.getValue().getUpperPrice());
        assertEquals(new BigDecimal("100.20"), event.getValue().getTriggerPrice());
    }

    @Test void zoneAlreadyContainingTheBaselineMustBeLeftAndReenteredBeforeItCanTrigger() {
        rule = new AlertRuleEntity(UUID.randomUUID(), USER, INSTRUMENT, "ZONE_TOUCH", null,
                new BigDecimal("99.00"), new BigDecimal("101.00"), "ARMED", null, NOW.minusSeconds(120));
        rule.armAt(new BigDecimal("100.00"), Instant.parse("2026-10-22T03:59:00Z"), "MASTER-1", NOW.minusSeconds(120));
        PriceAlertEvaluator evaluator = evaluator(new AlertMarketDataGate(true, "LICENSED_FEED", true, true, true, 120));

        evaluator.evaluate(candle("LICENSED_FEED", "PROVIDER", Instant.parse("2026-10-22T04:00:00Z"), NOW));

        assertEquals("ARMED", rule.getState());
        verify(events, never()).save(any(AlertEventEntity.class));
    }

    @Test void volumeSpikeUsesOnlyThePreviousTwentyConsecutiveProviderCandles() {
        rule = volumeRule();
        Instant bucket = Instant.parse("2026-10-22T04:00:00Z");
        CandleBar current = volumeCandle(bucket, 200);
        when(sessions.isRegularTradingMinute(any())).thenReturn(true);
        when(candles.findTop20ByInstrumentIdAndTimeframeSecondsAndSourceIdAndBucketStartBeforeOrderByBucketStartDesc(
                eq(INSTRUMENT), eq(60), eq("LICENSED_FEED"), eq(bucket))).thenReturn(history(bucket, 100, "MASTER-1"));

        evaluator(new AlertMarketDataGate(true, "LICENSED_FEED", true, true, true, 120)).evaluate(current);

        assertEquals("TRIGGERED", rule.getState());
        ArgumentCaptor<AlertEventEntity> event = ArgumentCaptor.forClass(AlertEventEntity.class);
        verify(events).save(event.capture());
        assertEquals("VOLUME_SPIKE", event.getValue().getRuleType());
        assertEquals(200L, event.getValue().getTriggerVolume());
        assertEquals(new BigDecimal("100.000000"), event.getValue().getReferenceAverageVolume());
        assertEquals(new BigDecimal("2.00"), event.getValue().getVolumeMultiplier());
        assertEquals(20, event.getValue().getLookbackCandles());
    }

    @Test void volumeSpikeWaitsWhenHistoryHasGapsOrInstrumentMasterChanges() {
        rule = volumeRule();
        Instant bucket = Instant.parse("2026-10-22T04:00:00Z");
        List<MarketCandleEntity> broken = new java.util.ArrayList<>(history(bucket, 100, "MASTER-1"));
        broken.set(7, history(bucket, 100, "MASTER-0").get(7));
        when(sessions.isRegularTradingMinute(any())).thenReturn(true);
        when(candles.findTop20ByInstrumentIdAndTimeframeSecondsAndSourceIdAndBucketStartBeforeOrderByBucketStartDesc(
                eq(INSTRUMENT), eq(60), eq("LICENSED_FEED"), eq(bucket))).thenReturn(broken);

        evaluator(new AlertMarketDataGate(true, "LICENSED_FEED", true, true, true, 120)).evaluate(volumeCandle(bucket, 500));

        assertEquals("WAITING_FOR_DATA", rule.getState());
        assertEquals("DISCONTINUOUS_VOLUME_HISTORY", rule.getReasonCode());
        verify(events, never()).save(any(AlertEventEntity.class));
    }

    @Test void volumeSpikeWaitsAfterAMissingMinuteInTheBaseline() {
        rule = volumeRule();
        Instant bucket = Instant.parse("2026-10-22T04:00:00Z");
        List<MarketCandleEntity> broken = new java.util.ArrayList<>(history(bucket, 100, "MASTER-1"));
        Instant wrongBucket = bucket.minusSeconds(60L * 8 + 10);
        CandleBar wrongTime = new CandleBar(INSTRUMENT, 60, wrongBucket, new BigDecimal("99.90"), new BigDecimal("100.50"),
                new BigDecimal("99.50"), new BigDecimal("100.20"), 100, 12, "LICENSED_FEED", "PROVIDER",
                "MASTER-1", true, wrongBucket.plusSeconds(60));
        broken.set(7, new MarketCandleEntity(wrongTime));
        when(sessions.isRegularTradingMinute(any())).thenReturn(true);
        when(candles.findTop20ByInstrumentIdAndTimeframeSecondsAndSourceIdAndBucketStartBeforeOrderByBucketStartDesc(
                eq(INSTRUMENT), eq(60), eq("LICENSED_FEED"), eq(bucket))).thenReturn(broken);

        evaluator(new AlertMarketDataGate(true, "LICENSED_FEED", true, true, true, 120)).evaluate(volumeCandle(bucket, 500));

        assertEquals("WAITING_FOR_DATA", rule.getState());
        assertEquals("DISCONTINUOUS_VOLUME_HISTORY", rule.getReasonCode());
        verify(events, never()).save(any(AlertEventEntity.class));
    }

    @Test void volumeBelowTheMultiplierThresholdArmsWithoutCreatingAnEvent() {
        rule = volumeRule();
        Instant bucket = Instant.parse("2026-10-22T04:00:00Z");
        when(sessions.isRegularTradingMinute(any())).thenReturn(true);
        when(candles.findTop20ByInstrumentIdAndTimeframeSecondsAndSourceIdAndBucketStartBeforeOrderByBucketStartDesc(
                eq(INSTRUMENT), eq(60), eq("LICENSED_FEED"), eq(bucket))).thenReturn(history(bucket, 100, "MASTER-1"));

        evaluator(new AlertMarketDataGate(true, "LICENSED_FEED", true, true, true, 120)).evaluate(volumeCandle(bucket, 199));

        assertEquals("ARMED", rule.getState());
        assertNull(rule.getReasonCode());
        verify(events, never()).save(any(AlertEventEntity.class));
    }

    @Test void volumeSpikeWaitsForEnoughHistoryAndNeverDividesByZero() {
        rule = volumeRule();
        Instant bucket = Instant.parse("2026-10-22T04:00:00Z");
        when(sessions.isRegularTradingMinute(any())).thenReturn(true);
        when(candles.findTop20ByInstrumentIdAndTimeframeSecondsAndSourceIdAndBucketStartBeforeOrderByBucketStartDesc(
                eq(INSTRUMENT), eq(60), eq("LICENSED_FEED"), eq(bucket))).thenReturn(List.of());

        evaluator(new AlertMarketDataGate(true, "LICENSED_FEED", true, true, true, 120)).evaluate(volumeCandle(bucket, 500));

        assertEquals("WAITING_FOR_DATA", rule.getState());
        assertEquals("INSUFFICIENT_VOLUME_HISTORY", rule.getReasonCode());
        verify(events, never()).save(any(AlertEventEntity.class));
    }

    @Test void zeroVolumeBaselineDoesNotCreateAnInfiniteOrFalseSpike() {
        rule = volumeRule();
        Instant bucket = Instant.parse("2026-10-22T04:00:00Z");
        when(sessions.isRegularTradingMinute(any())).thenReturn(true);
        when(candles.findTop20ByInstrumentIdAndTimeframeSecondsAndSourceIdAndBucketStartBeforeOrderByBucketStartDesc(
                eq(INSTRUMENT), eq(60), eq("LICENSED_FEED"), eq(bucket))).thenReturn(history(bucket, 0, "MASTER-1"));

        evaluator(new AlertMarketDataGate(true, "LICENSED_FEED", true, true, true, 120)).evaluate(volumeCandle(bucket, 1_000_000));

        assertEquals("WAITING_FOR_DATA", rule.getState());
        assertEquals("ZERO_VOLUME_BASELINE", rule.getReasonCode());
        verify(events, never()).save(any(AlertEventEntity.class));
    }

    @Test void volumeSpikeRejectsCandlesOutsideTheSupportedRegularSession() {
        rule = volumeRule();
        when(sessions.isRegularTradingMinute(any())).thenReturn(false);

        evaluator(new AlertMarketDataGate(true, "LICENSED_FEED", true, true, true, 120))
                .evaluate(volumeCandle(Instant.parse("2026-10-22T04:00:00Z"), 2_000));

        assertEquals("WAITING_FOR_DATA", rule.getState());
        assertEquals("OUTSIDE_SUPPORTED_SESSION_OR_INVALID_VOLUME", rule.getReasonCode());
        org.mockito.Mockito.verifyNoInteractions(candles);
        verify(events, never()).save(any(AlertEventEntity.class));
    }

    private PriceAlertEvaluator evaluator(AlertMarketDataGate gate) {
        return new PriceAlertEvaluator(rules, events, gate, candles, sessions, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AlertRuleEntity volumeRule() {
        return new AlertRuleEntity(UUID.randomUUID(), USER, INSTRUMENT, "VOLUME_SPIKE", null, null, null,
                new BigDecimal("2.00"), 20, "WAITING_FOR_DATA", "WAITING_FOR_FRESH_CANDLE", NOW.minusSeconds(60));
    }

    private List<MarketCandleEntity> history(Instant currentBucket, long volume, String masterVersion) {
        return java.util.stream.IntStream.rangeClosed(1, 20).mapToObj(index -> {
            Instant bucket = currentBucket.minusSeconds(60L * index);
            CandleBar bar = new CandleBar(INSTRUMENT, 60, bucket, new BigDecimal("99.90"), new BigDecimal("100.50"),
                    new BigDecimal("99.50"), new BigDecimal("100.20"), volume, 12, "LICENSED_FEED", "PROVIDER",
                    masterVersion, true, bucket.plusSeconds(60));
            return new MarketCandleEntity(bar);
        }).toList();
    }

    private CandleBar volumeCandle(Instant bucket, long volume) {
        return new CandleBar(INSTRUMENT, 60, bucket, new BigDecimal("99.90"), new BigDecimal("100.50"),
                new BigDecimal("99.50"), new BigDecimal("100.20"), volume, 12, "LICENSED_FEED", "PROVIDER", "MASTER-1", true, NOW);
    }

    private CandleBar candle(String source, String quality, Instant bucket, Instant finalizedAt) {
        return new CandleBar(INSTRUMENT, 60, bucket, new BigDecimal("99.90"), new BigDecimal("100.50"),
                new BigDecimal("99.50"), new BigDecimal("100.20"), 1000, 12, source, quality, "MASTER-1", true, finalizedAt);
    }
}
