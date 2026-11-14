package com.algotradehub.backend.alerts;

import com.algotradehub.backend.marketdata.CandleBar;
import com.algotradehub.backend.marketdata.MarketCandleEntity;
import com.algotradehub.backend.marketdata.MarketCandleRepository;
import com.algotradehub.backend.marketdata.MarketSessionCalendar;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;

@Service
public class PriceAlertEvaluator {
    private static final int VOLUME_LOOKBACK_CANDLES = 20;
    private static final int ONE_MINUTE_SECONDS = 60;
    private final AlertRuleRepository rules;
    private final AlertEventRepository events;
    private final AlertMarketDataGate dataGate;
    private final MarketCandleRepository candles;
    private final MarketSessionCalendar sessions;
    private final Clock clock;

    public PriceAlertEvaluator(AlertRuleRepository rules, AlertEventRepository events, AlertMarketDataGate dataGate,
                               MarketCandleRepository candles, MarketSessionCalendar sessions, Clock clock) {
        this.rules = rules;
        this.events = events;
        this.dataGate = dataGate;
        this.candles = candles;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional
    public void evaluate(CandleBar candle) {
        var activeRules = rules.lockActiveForInstrument(candle.instrumentId());
        if (activeRules.isEmpty()) return;
        Instant now = clock.instant();
        String suppression = dataGate.suppressionReason(candle, now);
        for (AlertRuleEntity rule : activeRules) {
            if (suppression != null) {
                if (!"PAUSED".equals(rule.getState()) || !suppression.equals(rule.getReasonCode())) {
                    rule.pause(suppression, now);
                    rules.save(rule);
                }
                continue;
            }
            if ("VOLUME_SPIKE".equals(rule.getRuleType())) {
                evaluateVolumeSpike(rule, candle, now);
                continue;
            }
            boolean continuous = rule.getBaselineCandleTime() != null
                    && rule.getBaselineCandleTime().plusSeconds(candle.timeframeSeconds()).equals(candle.bucketStart())
                    && candle.masterVersion().equals(rule.getBaselineMasterVersion());
            if (!continuous || rule.getBaselinePrice() == null) {
                rule.armAt(candle.close(), candle.bucketStart(), candle.masterVersion(), now);
                rules.save(rule);
                continue;
            }
            boolean crossedAbove = "PRICE_CROSSING".equals(rule.getRuleType()) && "ABOVE".equals(rule.getDirection())
                    && rule.getBaselinePrice().compareTo(rule.getThresholdPrice()) < 0
                    && candle.high().compareTo(rule.getThresholdPrice()) >= 0;
            boolean crossedBelow = "PRICE_CROSSING".equals(rule.getRuleType()) && "BELOW".equals(rule.getDirection())
                    && rule.getBaselinePrice().compareTo(rule.getThresholdPrice()) > 0
                    && candle.low().compareTo(rule.getThresholdPrice()) <= 0;
            boolean touchedZone = "ZONE_TOUCH".equals(rule.getRuleType())
                    && (rule.getBaselinePrice().compareTo(rule.getThresholdPrice()) < 0
                    || rule.getBaselinePrice().compareTo(rule.getUpperPrice()) > 0)
                    && candle.high().compareTo(rule.getThresholdPrice()) >= 0
                    && candle.low().compareTo(rule.getUpperPrice()) <= 0;
            if (crossedAbove || crossedBelow || touchedZone) {
                BigDecimal triggerPrice = rule.getThresholdPrice();
                if (touchedZone) {
                    triggerPrice = candle.close();
                }
                events.save(new AlertEventEntity(rule, triggerPrice, candle.bucketStart(), candle.sourceId(), now));
                rule.trigger(now);
            } else {
                rule.advance(candle.close(), candle.bucketStart(), candle.masterVersion(), now);
            }
            rules.save(rule);
        }
    }

    private void evaluateVolumeSpike(AlertRuleEntity rule, CandleBar candle, Instant now) {
        if (candle.volume() < 0 || !sessions.isRegularTradingMinute(candle.bucketStart())) {
            rule.waitForData("OUTSIDE_SUPPORTED_SESSION_OR_INVALID_VOLUME", now);
            rules.save(rule);
            return;
        }
        var history = candles.findTop20ByInstrumentIdAndTimeframeSecondsAndSourceIdAndBucketStartBeforeOrderByBucketStartDesc(
                candle.instrumentId(), ONE_MINUTE_SECONDS, candle.sourceId(), candle.bucketStart());
        String invalidReason = validateVolumeHistory(history, candle, now);
        if (invalidReason != null) {
            rule.waitForData(invalidReason, now);
            rules.save(rule);
            return;
        }
        BigDecimal totalVolume = history.stream().map(item -> BigDecimal.valueOf(item.getVolume()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal averageVolume = totalVolume.divide(BigDecimal.valueOf(VOLUME_LOOKBACK_CANDLES), 6, RoundingMode.HALF_UP);
        if (averageVolume.signum() <= 0) {
            rule.waitForData("ZERO_VOLUME_BASELINE", now);
            rules.save(rule);
            return;
        }
        if (rule.getVolumeMultiplier() == null || rule.getLookbackCandles() == null
                || rule.getLookbackCandles() != VOLUME_LOOKBACK_CANDLES) {
            rule.waitForData("INVALID_VOLUME_RULE", now);
            rules.save(rule);
            return;
        }
        BigDecimal requiredVolume = averageVolume.multiply(rule.getVolumeMultiplier());
        if (BigDecimal.valueOf(candle.volume()).compareTo(requiredVolume) >= 0) {
            events.save(new AlertEventEntity(rule, candle.close(), candle.bucketStart(), candle.sourceId(),
                    candle.volume(), averageVolume, now));
            rule.trigger(now);
        } else {
            rule.armVolume(now);
        }
        rules.save(rule);
    }

    private String validateVolumeHistory(java.util.List<MarketCandleEntity> history, CandleBar current, Instant now) {
        if (history.size() != VOLUME_LOOKBACK_CANDLES) return "INSUFFICIENT_VOLUME_HISTORY";
        Instant expected = current.bucketStart().minusSeconds(ONE_MINUTE_SECONDS);
        for (MarketCandleEntity item : history) {
            if (!item.getBucketStart().equals(expected)
                    || item.getTimeframeSeconds() != ONE_MINUTE_SECONDS
                    || !current.sourceId().equals(item.getSourceId())
                    || !current.masterVersion().equals(item.getMasterVersion())
                    || !"PROVIDER".equals(item.getQuality())
                    || item.getFinalizedAt().isAfter(now.plusSeconds(2))
                    || item.getVolume() < 0
                    || !sessions.isRegularTradingMinute(item.getBucketStart())) {
                return "DISCONTINUOUS_VOLUME_HISTORY";
            }
            expected = expected.minusSeconds(ONE_MINUTE_SECONDS);
        }
        return null;
    }
}
