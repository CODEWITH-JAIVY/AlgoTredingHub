package com.algotradehub.backend.orders;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class OrderEntity {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "broker_connection_id") private UUID brokerConnectionId;
    @Column(name = "client_order_id", nullable = false, length = 100) private String clientOrderId;
    @Column(name = "instrument_id", nullable = false) private UUID instrumentId;
    @Column(nullable = false, length = 8) private String side;
    @Column(name = "order_type", nullable = false, length = 16) private String orderType;
    @Column(nullable = false) private long quantity;
    @Column(name = "limit_price", precision = 18, scale = 4) private BigDecimal limitPrice;
    @Column(name = "trigger_price", precision = 18, scale = 4) private BigDecimal triggerPrice;
    @Column(name = "stop_loss_price", nullable = false, precision = 18, scale = 4) private BigDecimal stopLossPrice;
    @Column(name = "estimated_stop_risk", nullable = false, precision = 18, scale = 2) private BigDecimal estimatedStopRisk;
    @Column(nullable = false, length = 24) private String status;
    @Column(name = "broker_order_id", length = 120) private String brokerOrderId;
    @Column(nullable = false, length = 12) private String mode;
    @Column(name = "rejection_reason", length = 300) private String rejectionReason;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected OrderEntity() { }
    public OrderEntity(UUID id, UUID userId, String clientOrderId, OrderRequest r, String status, String brokerOrderId, BigDecimal estimatedStopRisk) {
        this.id = id;
        this.userId = userId;
        this.clientOrderId = r.clientOrderId();
        this.instrumentId = r.instrumentId();
        this.side = r.side().name();
        this.orderType = r.orderType().name();
        this.quantity = r.quantity();
        this.limitPrice = r.limitPrice();
        this.triggerPrice = r.triggerPrice();
        this.stopLossPrice = r.protectiveStopPrice();
        this.estimatedStopRisk = estimatedStopRisk;
        this.status = status;
        this.brokerOrderId = brokerOrderId;
        this.mode = "MOCK";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getClientOrderId() { return clientOrderId; }
    public UUID getInstrumentId() { return instrumentId; }
    public String getSide() { return side; }
    public String getOrderType() { return orderType; }
    public long getQuantity() { return quantity; }
    public BigDecimal getLimitPrice() { return limitPrice; }
    public BigDecimal getTriggerPrice() { return triggerPrice; }
    public BigDecimal getStopLossPrice() { return stopLossPrice; }
    public BigDecimal getEstimatedStopRisk() { return estimatedStopRisk; }
    public String getStatus() { return status; }
    public String getBrokerOrderId() { return brokerOrderId; }
    public String getMode() { return mode; }
    public Instant getCreatedAt() { return createdAt; }
    public void cancel(String brokerId) {
        this.brokerOrderId = brokerId;
        this.status = OrderStatus.CANCELLED.name();
        this.updatedAt = Instant.now();
    }
    public boolean matches(OrderRequest r) {
        return instrumentId.equals(r.instrumentId()) && side.equals(r.side().name()) && orderType.equals(r.orderType().name())
                && quantity == r.quantity() && equal(limitPrice, r.limitPrice()) && equal(triggerPrice, r.triggerPrice())
                && equal(stopLossPrice, r.protectiveStopPrice());
    }
    private boolean equal(BigDecimal a, BigDecimal b) {
        if (a == null) {
            return b == null;
        }
        return b != null && a.compareTo(b) == 0;
    }
}
