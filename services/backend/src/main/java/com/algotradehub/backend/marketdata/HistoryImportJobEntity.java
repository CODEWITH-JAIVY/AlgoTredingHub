package com.algotradehub.backend.marketdata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "history_import_jobs")
public class HistoryImportJobEntity {
    @Id private UUID id;
    @Column(name = "request_key", nullable = false, unique = true, length = 64) private String requestKey;
    @Column(name = "instrument_id", nullable = false) private UUID instrumentId;
    @Column(name = "source_id", nullable = false, length = 50) private String sourceId;
    @Column(name = "timeframe_seconds", nullable = false) private int timeframeSeconds;
    @Column(name = "range_start", nullable = false) private Instant rangeStart;
    @Column(name = "range_end", nullable = false) private Instant rangeEnd;
    @Column(name = "next_cursor", length = 512) private String nextCursor;
    @Column(nullable = false, length = 16) private String status;
    @Column(name = "imported_count", nullable = false) private long importedCount;
    @Column(name = "last_request_at") private Instant lastRequestAt;
    @Column(name = "last_error_code", length = 64) private String lastErrorCode;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected HistoryImportJobEntity() { }

    public HistoryImportJobEntity(UUID id, String requestKey, UUID instrumentId, String sourceId,
                                  Instant from, Instant to, Instant now) {
        this.id = id;
        this.requestKey = requestKey;
        this.instrumentId = instrumentId;
        this.sourceId = sourceId;
        this.timeframeSeconds = CandleAggregator.SUPPORTED_TIMEFRAME_SECONDS;
        this.rangeStart = from;
        this.rangeEnd = to;
        this.status = "PENDING";
        this.importedCount = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markRequested(Instant now) {
        status = "RUNNING";
        lastRequestAt = now;
        lastErrorCode = null;
        updatedAt = now;
    }
    public void advance(String cursor, long added, boolean complete, Instant now) {
        if (added < 0) throw new IllegalArgumentException("Imported count cannot be negative.");
        importedCount = Math.addExact(importedCount, added);
        nextCursor = cursor;
        if (complete) {
            status = "COMPLETE";
        } else {
            status = "RUNNING";
        }
        lastErrorCode = null;
        updatedAt = now;
    }
    public void markFailure(String errorCode, Instant now) {
        status = "RUNNING";
        lastErrorCode = errorCode;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getRequestKey() { return requestKey; }
    public UUID getInstrumentId() { return instrumentId; }
    public String getSourceId() { return sourceId; }
    public int getTimeframeSeconds() { return timeframeSeconds; }
    public Instant getRangeStart() { return rangeStart; }
    public Instant getRangeEnd() { return rangeEnd; }
    public String getNextCursor() { return nextCursor; }
    public String getStatus() { return status; }
    public long getImportedCount() { return importedCount; }
    public Instant getLastRequestAt() { return lastRequestAt; }
    public String getLastErrorCode() { return lastErrorCode; }
}
