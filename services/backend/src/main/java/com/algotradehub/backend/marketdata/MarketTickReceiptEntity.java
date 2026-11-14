package com.algotradehub.backend.marketdata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "market_tick_receipts")
public class MarketTickReceiptEntity {
    @Id @Column(name = "event_key", length = 64) private String eventKey;
    @Column(name = "instrument_id", nullable = false) private UUID instrumentId;
    @Column(name = "source_id", nullable = false, length = 50) private String sourceId;
    @Column(name = "sequence_number", nullable = false) private long sequenceNumber;
    @Column(name = "exchange_time", nullable = false) private Instant exchangeTime;
    @Column(name = "received_at", nullable = false) private Instant receivedAt;
    @Column(nullable = false, precision = 18, scale = 6) private BigDecimal price;
    @Column(name = "trade_quantity", nullable = false) private long tradeQuantity;
    @Column(name = "instrument_master_version", nullable = false, length = 64) private String masterVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 32)
    private MarketTickReceiptStatus processingStatus;
    @Column(name = "outcome_code", length = 64) private String outcomeCode;
    protected MarketTickReceiptEntity() { }

    public String getEventKey() { return eventKey; }
    public UUID getInstrumentId() { return instrumentId; }
    public String getSourceId() { return sourceId; }
    public long getSequenceNumber() { return sequenceNumber; }
    public Instant getExchangeTime() { return exchangeTime; }
    public Instant getReceivedAt() { return receivedAt; }
    public BigDecimal getPrice() { return price; }
    public long getTradeQuantity() { return tradeQuantity; }
    public String getMasterVersion() { return masterVersion; }
    public MarketTickReceiptStatus getProcessingStatus() { return processingStatus; }
    public String getOutcomeCode() { return outcomeCode; }
    public NormalizedTick toNormalizedTick() {
        return new NormalizedTick(instrumentId, price, tradeQuantity, exchangeTime, receivedAt, sequenceNumber,
                sourceId, "SYNTHETIC_DEMO".equals(sourceId), masterVersion);
    }
}
