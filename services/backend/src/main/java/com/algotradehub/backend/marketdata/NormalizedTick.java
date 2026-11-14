package com.algotradehub.backend.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record NormalizedTick(UUID instrumentId, BigDecimal price, long tradeQuantity, Instant exchangeTime,
                             Instant receivedAt, long sequenceNumber, String sourceId, boolean synthetic,
                             String masterVersion) { }
