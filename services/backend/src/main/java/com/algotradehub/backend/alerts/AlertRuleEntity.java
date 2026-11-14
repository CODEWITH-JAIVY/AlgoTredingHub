package com.algotradehub.backend.alerts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alert_rules")
public class AlertRuleEntity {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "instrument_id", nullable = false) private UUID instrumentId;
    @Column(length = 8) private String direction;
    @Column(name = "rule_type", nullable = false, length = 16) private String ruleType;
    @Column(name = "threshold_price", precision = 18, scale = 6) private BigDecimal thresholdPrice;
    @Column(name = "upper_price", precision = 18, scale = 6) private BigDecimal upperPrice;
    @Column(name = "volume_multiplier", precision = 4, scale = 2) private BigDecimal volumeMultiplier;
    @Column(name = "lookback_candles") private Integer lookbackCandles;
    @Column(nullable = false) private boolean active;
    @Column(nullable = false, length = 32) private String state;
    @Column(name = "reason_code", length = 64) private String reasonCode;
    @Column(name = "baseline_price", precision = 18, scale = 6) private BigDecimal baselinePrice;
    @Column(name = "baseline_candle_time") private Instant baselineCandleTime;
    @Column(name = "baseline_master_version", length = 64) private String baselineMasterVersion;
    @Column(name = "triggered_at") private Instant triggeredAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected AlertRuleEntity() { }

    public AlertRuleEntity(UUID id, UUID userId, UUID instrumentId, String ruleType, String direction,
                           BigDecimal thresholdPrice, BigDecimal upperPrice, String initialState,
                           String reasonCode, Instant now) {
        this(id, userId, instrumentId, ruleType, direction, thresholdPrice, upperPrice, null, null,
                initialState, reasonCode, now);
    }

    public AlertRuleEntity(UUID id, UUID userId, UUID instrumentId, String ruleType, String direction,
                           BigDecimal thresholdPrice, BigDecimal upperPrice, BigDecimal volumeMultiplier,
                           Integer lookbackCandles, String initialState, String reasonCode, Instant now) {
        this.id = id;
        this.userId = userId;
        this.instrumentId = instrumentId;
        this.ruleType = ruleType;
        this.direction = direction;
        this.thresholdPrice = thresholdPrice;
        this.upperPrice = upperPrice;
        this.volumeMultiplier = volumeMultiplier;
        this.lookbackCandles = lookbackCandles;
        this.active = true;
        this.state = initialState;
        this.reasonCode = reasonCode;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public AlertRuleEntity(UUID id, UUID userId, UUID instrumentId, String direction, BigDecimal thresholdPrice,
                           String initialState, String reasonCode, Instant now) {
        this(id, userId, instrumentId, "PRICE_CROSSING", direction, thresholdPrice, null, initialState, reasonCode, now);
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getInstrumentId() { return instrumentId; }
    public String getRuleType() { return ruleType; }
    public String getDirection() { return direction; }
    public BigDecimal getThresholdPrice() { return thresholdPrice; }
    public BigDecimal getUpperPrice() { return upperPrice; }
    public BigDecimal getVolumeMultiplier() { return volumeMultiplier; }
    public Integer getLookbackCandles() { return lookbackCandles; }
    public boolean isActive() { return active; }
    public String getState() { return state; }
    public String getReasonCode() { return reasonCode; }
    public BigDecimal getBaselinePrice() { return baselinePrice; }
    public Instant getBaselineCandleTime() { return baselineCandleTime; }
    public String getBaselineMasterVersion() { return baselineMasterVersion; }
    public Instant getTriggeredAt() { return triggeredAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void pause(String reason, Instant now) {
        if (!"TRIGGERED".equals(state)) {
            state = "PAUSED";
            reasonCode = reason;
            baselinePrice = null;
            baselineCandleTime = null;
            baselineMasterVersion = null;
            updatedAt = now;
        }
    }

    public void armAt(BigDecimal close, Instant candleTime, String masterVersion, Instant now) {
        if (!"TRIGGERED".equals(state)) {
            baselinePrice = close;
            baselineCandleTime = candleTime;
            baselineMasterVersion = masterVersion;
            state = "ARMED";
            reasonCode = null;
            updatedAt = now;
        }
    }

    public void advance(BigDecimal close, Instant candleTime, String masterVersion, Instant now) {
        baselinePrice = close;
        baselineCandleTime = candleTime;
        baselineMasterVersion = masterVersion;
        state = "ARMED";
        reasonCode = null;
        updatedAt = now;
    }

    public void waitForData(String reason, Instant now) {
        if (!"TRIGGERED".equals(state)) {
            state = "WAITING_FOR_DATA";
            reasonCode = reason;
            updatedAt = now;
        }
    }

    public void armVolume(Instant now) {
        if (!"TRIGGERED".equals(state)) {
            state = "ARMED";
            reasonCode = null;
            updatedAt = now;
        }
    }

    public void trigger(Instant now) {
        active = false;
        state = "TRIGGERED";
        reasonCode = null;
        triggeredAt = now;
        updatedAt = now;
    }
}
