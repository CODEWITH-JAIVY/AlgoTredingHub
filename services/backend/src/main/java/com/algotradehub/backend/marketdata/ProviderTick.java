package com.algotradehub.backend.marketdata;

import java.math.BigDecimal;
import java.time.Instant;

/** Provider-neutral input shape. Real providers remain disabled until data rights are approved. */
public record ProviderTick(String sourceId, String exchange, String providerToken, String instrumentMasterVersion,
                           BigDecimal price, long tradeQuantity, Instant exchangeTime, long sequenceNumber) { }
