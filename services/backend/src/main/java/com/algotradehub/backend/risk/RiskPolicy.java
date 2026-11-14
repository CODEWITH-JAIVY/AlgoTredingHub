package com.algotradehub.backend.risk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "risk_policies")
public class RiskPolicy {
    @Id @Column(name = "user_id") private UUID userId;
    @Column(name = "max_quantity", nullable = false) private long maxQuantity;
    @Column(name = "max_order_notional", nullable = false, precision = 18, scale = 2) private BigDecimal maxOrderNotional;
    @Column(name = "max_open_orders", nullable = false) private int maxOpenOrders;
    @Column(name = "max_daily_loss", nullable = false, precision = 18, scale = 2) private BigDecimal maxDailyLoss;
    @Column(name = "max_trade_loss", nullable = false, precision = 18, scale = 2) private BigDecimal maxTradeLoss;
    @Column(name = "max_orders_per_day", nullable = false) private int maxOrdersPerDay;
    @Column(name = "no_new_orders_after", nullable = false) private LocalTime noNewOrdersAfter;
    @Column(name = "no_orders_first_minutes", nullable = false) private int noOrdersFirstMinutes;
    @Column(name = "pending_max_quantity") private Long pendingMaxQuantity;
    @Column(name = "pending_max_order_notional", precision = 18, scale = 2) private BigDecimal pendingMaxOrderNotional;
    @Column(name = "pending_max_open_orders") private Integer pendingMaxOpenOrders;
    @Column(name = "pending_max_daily_loss", precision = 18, scale = 2) private BigDecimal pendingMaxDailyLoss;
    @Column(name = "pending_max_trade_loss", precision = 18, scale = 2) private BigDecimal pendingMaxTradeLoss;
    @Column(name = "pending_max_orders_per_day") private Integer pendingMaxOrdersPerDay;
    @Column(name = "pending_no_new_orders_after") private LocalTime pendingNoNewOrdersAfter;
    @Column(name = "pending_no_orders_first_minutes") private Integer pendingNoOrdersFirstMinutes;
    @Column(name = "pending_effective_on") private LocalDate pendingEffectiveOn;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected RiskPolicy() { }
    public RiskPolicy(UUID userId) {
        this.userId = userId;
        this.maxQuantity = 100;
        this.maxOrderNotional = new BigDecimal("100000.00");
        this.maxOpenOrders = 5;
        this.maxDailyLoss = new BigDecimal("5000.00");
        this.maxTradeLoss = new BigDecimal("1000.00");
        this.maxOrdersPerDay = 10;
        this.noNewOrdersAfter = LocalTime.of(15, 0);
        this.noOrdersFirstMinutes = 5;
        this.updatedAt = Instant.now();
    }
    public UUID getUserId() { return userId; }
    public long getMaxQuantity() { return maxQuantity; }
    public BigDecimal getMaxOrderNotional() { return maxOrderNotional; }
    public int getMaxOpenOrders() { return maxOpenOrders; }
    public BigDecimal getMaxDailyLoss() { return maxDailyLoss; }
    public Instant getUpdatedAt() { return updatedAt; }
    public BigDecimal getMaxTradeLoss() { return maxTradeLoss; }
    public int getMaxOrdersPerDay() { return maxOrdersPerDay; }
    public LocalTime getNoNewOrdersAfter() { return noNewOrdersAfter; }
    public int getNoOrdersFirstMinutes() { return noOrdersFirstMinutes; }
    public PendingLimits getPendingLimits() {
        if (pendingEffectiveOn == null) return null;
        return new PendingLimits(pendingMaxQuantity, pendingMaxOrderNotional, pendingMaxOpenOrders,
                pendingMaxDailyLoss, pendingMaxTradeLoss, pendingMaxOrdersPerDay,
                pendingNoNewOrdersAfter, pendingNoOrdersFirstMinutes, pendingEffectiveOn);
    }

    public boolean applyPendingLimits(LocalDate currentSessionDate) {
        if (pendingEffectiveOn == null || pendingEffectiveOn.isAfter(currentSessionDate)) return false;
        if (pendingMaxQuantity != null) maxQuantity = pendingMaxQuantity;
        if (pendingMaxOrderNotional != null) maxOrderNotional = pendingMaxOrderNotional;
        if (pendingMaxOpenOrders != null) maxOpenOrders = pendingMaxOpenOrders;
        if (pendingMaxDailyLoss != null) maxDailyLoss = pendingMaxDailyLoss;
        if (pendingMaxTradeLoss != null) maxTradeLoss = pendingMaxTradeLoss;
        if (pendingMaxOrdersPerDay != null) maxOrdersPerDay = pendingMaxOrdersPerDay;
        if (pendingNoNewOrdersAfter != null) noNewOrdersAfter = pendingNoNewOrdersAfter;
        if (pendingNoOrdersFirstMinutes != null) noOrdersFirstMinutes = pendingNoOrdersFirstMinutes;
        clearPendingLimits();
        this.updatedAt = Instant.now();
        return true;
    }

    public void update(long quantity, BigDecimal notional, int openOrders, BigDecimal dailyLoss, BigDecimal tradeLoss,
                       int ordersPerDay, LocalTime noOrdersAfter, int noOrdersFirstMinutes,
                       LocalDate currentSessionDate, LocalDate effectiveDate) {
        if (ordersPerDay < 1 || ordersPerDay > 100 || noOrdersAfter == null
                || noOrdersFirstMinutes < 0 || noOrdersFirstMinutes > 120
                || noOrdersAfter.isAfter(LocalTime.of(15, 30))
                || noOrdersAfter.getSecond() != 0 || noOrdersAfter.getNano() != 0
                || !noOrdersAfter.isAfter(LocalTime.of(9, 15).plusMinutes(noOrdersFirstMinutes))) {
            throw new IllegalArgumentException("Daily discipline settings are outside supported trading bounds.");
        }
        if (effectiveDate == null || !effectiveDate.isAfter(currentSessionDate)) {
            throw new IllegalArgumentException("A relaxed risk limit must take effect after the current session.");
        }
        applyPendingLimits(currentSessionDate);
        clearPendingLimits();
        if (quantity > maxQuantity) pendingMaxQuantity = quantity;
        else maxQuantity = quantity;
        if (notional.compareTo(maxOrderNotional) > 0) pendingMaxOrderNotional = notional;
        else maxOrderNotional = notional;
        if (openOrders > maxOpenOrders) pendingMaxOpenOrders = openOrders;
        else maxOpenOrders = openOrders;
        if (dailyLoss.compareTo(maxDailyLoss) > 0) pendingMaxDailyLoss = dailyLoss;
        else maxDailyLoss = dailyLoss;
        if (tradeLoss.compareTo(maxTradeLoss) > 0) pendingMaxTradeLoss = tradeLoss;
        else maxTradeLoss = tradeLoss;
        if (ordersPerDay > maxOrdersPerDay) pendingMaxOrdersPerDay = ordersPerDay;
        else maxOrdersPerDay = ordersPerDay;
        if (noOrdersAfter.isAfter(noNewOrdersAfter)) pendingNoNewOrdersAfter = noOrdersAfter;
        else noNewOrdersAfter = noOrdersAfter;
        if (noOrdersFirstMinutes < this.noOrdersFirstMinutes) pendingNoOrdersFirstMinutes = noOrdersFirstMinutes;
        else this.noOrdersFirstMinutes = noOrdersFirstMinutes;
        if (hasPendingValue()) pendingEffectiveOn = effectiveDate;
        this.updatedAt = Instant.now();
    }

    private boolean hasPendingValue() {
        return pendingMaxQuantity != null || pendingMaxOrderNotional != null || pendingMaxOpenOrders != null
                || pendingMaxDailyLoss != null || pendingMaxTradeLoss != null || pendingMaxOrdersPerDay != null
                || pendingNoNewOrdersAfter != null || pendingNoOrdersFirstMinutes != null;
    }

    private void clearPendingLimits() {
        pendingMaxQuantity = null;
        pendingMaxOrderNotional = null;
        pendingMaxOpenOrders = null;
        pendingMaxDailyLoss = null;
        pendingMaxTradeLoss = null;
        pendingMaxOrdersPerDay = null;
        pendingNoNewOrdersAfter = null;
        pendingNoOrdersFirstMinutes = null;
        pendingEffectiveOn = null;
    }

    public record PendingLimits(Long maxQuantity, BigDecimal maxOrderNotional, Integer maxOpenOrders,
                                BigDecimal maxDailyLoss, BigDecimal maxTradeLoss, Integer maxOrdersPerDay,
                                LocalTime noNewOrdersAfter, Integer noOrdersFirstMinutes, LocalDate effectiveOn) { }
}
