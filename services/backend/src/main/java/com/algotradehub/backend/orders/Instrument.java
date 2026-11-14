package com.algotradehub.backend.orders;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "instruments")
public class Instrument {
    @Id private UUID id;
    @Column(name = "provider_token", nullable = false, length = 120) private String providerToken;
    @Column(nullable = false, length = 8) private String exchange;
    @Column(nullable = false, length = 24) private String segment;
    @Column(nullable = false, length = 100) private String symbol;
    @Column(name = "instrument_type", nullable = false, length = 16) private String instrumentType;
    @Column(name = "expiry_date") private LocalDate expiryDate;
    @Column(name = "strike_price", precision = 18, scale = 4) private BigDecimal strikePrice;
    @Column(name = "option_type", length = 2) private String optionType;
    @Column(name = "lot_size", nullable = false) private int lotSize;
    @Column(name = "tick_size", nullable = false, precision = 12, scale = 6) private BigDecimal tickSize;
    @Column(name = "mock_price", nullable = false, precision = 18, scale = 4) private BigDecimal mockPrice;
    @Column(nullable = false) private boolean synthetic;
    @Column(nullable = false) private boolean active;
    @Column(name = "master_version", nullable = false, length = 64) private String masterVersion;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected Instrument() { }
    public Instrument(UUID id, String providerToken, String exchange, String segment, String symbol, String instrumentType,
                      int lotSize, BigDecimal tickSize, BigDecimal mockPrice, String masterVersion) {
        this.id = id;
        this.providerToken = providerToken;
        this.exchange = exchange;
        this.segment = segment;
        this.symbol = symbol;
        this.instrumentType = instrumentType;
        this.lotSize = lotSize;
        this.tickSize = tickSize;
        this.mockPrice = mockPrice;
        this.masterVersion = masterVersion;
        this.synthetic = true;
        this.active = true;
        this.updatedAt = Instant.now();
    }
    public UUID getId() { return id; }
    public String getProviderToken() { return providerToken; }
    public String getExchange() { return exchange; }
    public String getSegment() { return segment; }
    public String getSymbol() { return symbol; }
    public String getInstrumentType() { return instrumentType; }
    public int getLotSize() { return lotSize; }
    public BigDecimal getTickSize() { return tickSize; }
    public BigDecimal getMockPrice() { return mockPrice; }
    public boolean isSynthetic() { return synthetic; }
    public boolean isActive() { return active; }
    public String getMasterVersion() { return masterVersion; }
    public Instant getUpdatedAt() { return updatedAt; }
}
