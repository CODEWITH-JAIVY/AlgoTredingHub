package com.algotradehub.backend.risk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "daily_risk_state")
public class DailyRiskState {
    @Id @Column(name = "user_id") private UUID userId;
    @Column(name = "session_date", nullable = false) private LocalDate sessionDate;
    @Column(name = "realized_loss", nullable = false, precision = 18, scale = 2) private BigDecimal realizedLoss;
    @Column(name = "accepted_order_entries", nullable = false) private int acceptedOrderEntries;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected DailyRiskState() { }
    public DailyRiskState(UUID userId, LocalDate sessionDate) {
        this.userId = userId;
        this.sessionDate = sessionDate;
        this.realizedLoss = BigDecimal.ZERO;
        this.acceptedOrderEntries = 0;
        this.updatedAt = Instant.now();
    }
    public LocalDate getSessionDate() { return sessionDate; }
    public BigDecimal getRealizedLoss() { return realizedLoss; }
    public int getAcceptedOrderEntries() { return acceptedOrderEntries; }
    public void recordAcceptedOrderEntry() {
        if (acceptedOrderEntries >= 100) throw new IllegalStateException("Daily order-entry counter is exhausted.");
        acceptedOrderEntries++;
        this.updatedAt = Instant.now();
    }
    public void reset(LocalDate today) {
        this.sessionDate = today;
        this.realizedLoss = BigDecimal.ZERO;
        this.acceptedOrderEntries = 0;
        this.updatedAt = Instant.now();
    }
}
