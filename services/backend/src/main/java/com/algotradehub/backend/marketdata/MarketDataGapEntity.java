package com.algotradehub.backend.marketdata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "market_data_gaps", indexes = @Index(name = "ix_market_gaps_range", columnList = "instrument_id,gap_start,gap_end"),
       uniqueConstraints = @UniqueConstraint(name = "uq_market_gap", columnNames = {"instrument_id", "source_id", "gap_start", "gap_end"}))
public class MarketDataGapEntity {
    @Id private UUID id;
    @Column(name = "instrument_id", nullable = false) private UUID instrumentId;
    @Column(name = "source_id", nullable = false, length = 50) private String sourceId;
    @Column(name = "gap_start", nullable = false) private Instant gapStart;
    @Column(name = "gap_end", nullable = false) private Instant gapEnd;
    @Column(nullable = false, length = 32) private String reason;
    @Column(name = "detected_at", nullable = false) private Instant detectedAt;

    protected MarketDataGapEntity() { }
    public MarketDataGapEntity(MarketDataGap gap) {
        id = UUID.randomUUID();
        instrumentId = gap.instrumentId();
        sourceId = gap.sourceId();
        gapStart = gap.gapStart();
        gapEnd = gap.gapEnd();
        reason = gap.reason();
        detectedAt = gap.detectedAt();
    }

    public UUID getInstrumentId() { return instrumentId; }
    public String getSourceId() { return sourceId; }
    public Instant getGapStart() { return gapStart; }
    public Instant getGapEnd() { return gapEnd; }
    public String getReason() { return reason; }
    public Instant getDetectedAt() { return detectedAt; }
}
