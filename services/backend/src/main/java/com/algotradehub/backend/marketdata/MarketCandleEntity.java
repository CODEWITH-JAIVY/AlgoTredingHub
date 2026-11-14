package com.algotradehub.backend.marketdata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "market_candles", indexes = @Index(name = "ix_market_candles_range", columnList = "instrument_id,timeframe_seconds,bucket_start"),
       uniqueConstraints = @UniqueConstraint(name = "uq_market_candle_source", columnNames = {"instrument_id", "timeframe_seconds", "bucket_start", "source_id"}))
public class MarketCandleEntity {
    @Id private UUID id;
    @Column(name = "instrument_id", nullable = false) private UUID instrumentId;
    @Column(name = "timeframe_seconds", nullable = false) private int timeframeSeconds;
    @Column(name = "bucket_start", nullable = false) private Instant bucketStart;
    @Column(name = "open_price", nullable = false, precision = 18, scale = 6) private BigDecimal open;
    @Column(name = "high_price", nullable = false, precision = 18, scale = 6) private BigDecimal high;
    @Column(name = "low_price", nullable = false, precision = 18, scale = 6) private BigDecimal low;
    @Column(name = "close_price", nullable = false, precision = 18, scale = 6) private BigDecimal close;
    @Column(nullable = false) private long volume;
    @Column(name = "trade_count", nullable = false) private long tradeCount;
    @Column(name = "source_id", nullable = false, length = 50) private String sourceId;
    @Column(nullable = false, length = 24) private String quality;
    @Column(name = "master_version", nullable = false, length = 64) private String masterVersion;
    @Column(name = "finalized_at", nullable = false) private Instant finalizedAt;

    protected MarketCandleEntity() { }

    public MarketCandleEntity(CandleBar bar) {
        if (!bar.finalized()) throw new IllegalArgumentException("Only finalized candles can be persisted.");
        id = UUID.randomUUID();
        instrumentId = bar.instrumentId();
        timeframeSeconds = bar.timeframeSeconds();
        bucketStart = bar.bucketStart();
        open = bar.open();
        high = bar.high();
        low = bar.low();
        close = bar.close();
        volume = bar.volume();
        tradeCount = bar.tradeCount();
        sourceId = bar.sourceId();
        quality = bar.quality();
        masterVersion = bar.masterVersion();
        finalizedAt = bar.finalizedAt();
    }

    public UUID getId() { return id; }
    public UUID getInstrumentId() { return instrumentId; }
    public int getTimeframeSeconds() { return timeframeSeconds; }
    public Instant getBucketStart() { return bucketStart; }
    public BigDecimal getOpen() { return open; }
    public BigDecimal getHigh() { return high; }
    public BigDecimal getLow() { return low; }
    public BigDecimal getClose() { return close; }
    public long getVolume() { return volume; }
    public long getTradeCount() { return tradeCount; }
    public String getSourceId() { return sourceId; }
    public String getQuality() { return quality; }
    public String getMasterVersion() { return masterVersion; }
    public Instant getFinalizedAt() { return finalizedAt; }
}
