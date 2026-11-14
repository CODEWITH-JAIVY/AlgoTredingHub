package com.algotradehub.backend.alerts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class AlertViews {
    private AlertViews() { }

    public record Rule(UUID id, UUID instrumentId, String ruleType, String direction, BigDecimal price,
                       BigDecimal upperPrice, BigDecimal volumeMultiplier, Integer lookbackCandles,
                       String state, String reasonCode, Instant createdAt, Instant triggeredAt) {
        public static Rule from(AlertRuleEntity rule) {
            return new Rule(rule.getId(), rule.getInstrumentId(), rule.getRuleType(), rule.getDirection(),
                    rule.getThresholdPrice(), rule.getUpperPrice(), rule.getVolumeMultiplier(), rule.getLookbackCandles(),
                    rule.getState(), rule.getReasonCode(),
                    rule.getCreatedAt(), rule.getTriggeredAt());
        }
    }

    public record Event(UUID id, UUID ruleId, UUID instrumentId, String ruleType, String direction,
                        BigDecimal thresholdPrice, BigDecimal upperPrice, BigDecimal triggerPrice,
                        BigDecimal volumeMultiplier, Integer lookbackCandles, Long triggerVolume,
                        BigDecimal referenceAverageVolume,
                        Instant candleTime, String sourceId, Instant createdAt, Instant readAt) {
        public static Event from(AlertEventEntity event) {
            return new Event(event.getId(), event.getRuleId(), event.getInstrumentId(), event.getRuleType(),
                    event.getDirection(), event.getThresholdPrice(), event.getUpperPrice(), event.getTriggerPrice(),
                    event.getVolumeMultiplier(), event.getLookbackCandles(), event.getTriggerVolume(), event.getReferenceAverageVolume(),
                    event.getCandleTime(), event.getSourceId(), event.getCreatedAt(), event.getReadAt());
        }
    }
}
