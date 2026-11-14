package com.algotradehub.backend.marketdata;

import java.time.Instant;
import java.util.UUID;

public record MarketDataGap(UUID instrumentId, String sourceId, Instant gapStart, Instant gapEnd,
                            String reason, Instant detectedAt) { }
