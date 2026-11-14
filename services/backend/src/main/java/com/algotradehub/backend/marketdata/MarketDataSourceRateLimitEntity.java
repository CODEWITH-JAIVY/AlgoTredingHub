package com.algotradehub.backend.marketdata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "market_data_source_rate_limits")
public class MarketDataSourceRateLimitEntity {
    @Id @Column(name = "source_id", length = 50) private String sourceId;
    @Column(name = "last_request_at", nullable = false) private Instant lastRequestAt;
    protected MarketDataSourceRateLimitEntity() { }
    public MarketDataSourceRateLimitEntity(String sourceId, Instant lastRequestAt) {
        this.sourceId = sourceId;
        this.lastRequestAt = lastRequestAt;
    }
    public Instant getLastRequestAt() { return lastRequestAt; }
    public void recordRequest(Instant requestedAt) { lastRequestAt = requestedAt; }
}
