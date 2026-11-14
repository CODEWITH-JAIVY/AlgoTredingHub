package com.algotradehub.backend.alerts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alert_events")
public class AlertEventEntity {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "rule_id", nullable = false) private UUID ruleId;
    @Column(name = "instrument_id", nullable = false) private UUID instrumentId;
    @Column(length = 8) private String direction;
    @Column(name = "rule_type", nullable = false, length = 16) private String ruleType;
    @Column(name = "threshold_price", precision = 18, scale = 6) private BigDecimal thresholdPrice;
    @Column(name = "upper_price", precision = 18, scale = 6) private BigDecimal upperPrice;
    @Column(name = "volume_multiplier", precision = 4, scale = 2) private BigDecimal volumeMultiplier;
    @Column(name = "lookback_candles") private Integer lookbackCandles;
    @Column(name = "trigger_volume") private Long triggerVolume;
    @Column(name = "reference_average_volume", precision = 25, scale = 6) private BigDecimal referenceAverageVolume;
    @Column(name = "trigger_price", nullable = false, precision = 18, scale = 6) private BigDecimal triggerPrice;
    @Column(name = "candle_time", nullable = false) private Instant candleTime;
    @Column(name = "source_id", nullable = false, length = 50) private String sourceId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "read_at") private Instant readAt;

    protected AlertEventEntity() { }

    public AlertEventEntity(AlertRuleEntity rule, BigDecimal triggerPrice, Instant candleTime, String sourceId, Instant now) {
        this(rule, triggerPrice, candleTime, sourceId, null, null, now);
    }

    public AlertEventEntity(AlertRuleEntity rule, BigDecimal triggerPrice, Instant candleTime, String sourceId,
                            Long triggerVolume, BigDecimal referenceAverageVolume, Instant now) {
        id = UUID.randomUUID();
        userId = rule.getUserId();
        ruleId = rule.getId();
        instrumentId = rule.getInstrumentId();
        direction = rule.getDirection();
        ruleType = rule.getRuleType();
        thresholdPrice = rule.getThresholdPrice();
        upperPrice = rule.getUpperPrice();
        volumeMultiplier = rule.getVolumeMultiplier();
        lookbackCandles = rule.getLookbackCandles();
        this.triggerVolume = triggerVolume;
        this.referenceAverageVolume = referenceAverageVolume;
        this.triggerPrice = triggerPrice;
        this.candleTime = candleTime;
        this.sourceId = sourceId;
        createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getRuleId() { return ruleId; }
    public UUID getInstrumentId() { return instrumentId; }
    public String getRuleType() { return ruleType; }
    public String getDirection() { return direction; }
    public BigDecimal getThresholdPrice() { return thresholdPrice; }
    public BigDecimal getUpperPrice() { return upperPrice; }
    public BigDecimal getVolumeMultiplier() { return volumeMultiplier; }
    public Integer getLookbackCandles() { return lookbackCandles; }
    public Long getTriggerVolume() { return triggerVolume; }
    public BigDecimal getReferenceAverageVolume() { return referenceAverageVolume; }
    public BigDecimal getTriggerPrice() { return triggerPrice; }
    public Instant getCandleTime() { return candleTime; }
    public String getSourceId() { return sourceId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReadAt() { return readAt; }
    public void markRead(Instant now) { if (readAt == null) readAt = now; }
}
