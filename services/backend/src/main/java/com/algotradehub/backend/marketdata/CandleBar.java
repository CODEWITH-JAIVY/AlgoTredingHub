package com.algotradehub.backend.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CandleBar(UUID instrumentId, int timeframeSeconds, Instant bucketStart, BigDecimal open,
                        BigDecimal high, BigDecimal low, BigDecimal close, long volume, long tradeCount,
                        String sourceId, String quality, String masterVersion, boolean finalized,
                        Instant finalizedAt) { }
