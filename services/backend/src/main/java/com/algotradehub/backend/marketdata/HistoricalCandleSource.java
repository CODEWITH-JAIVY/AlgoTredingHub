package com.algotradehub.backend.marketdata;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Implement only after the source contract explicitly permits these storage/display uses. */
public interface HistoricalCandleSource {
    String sourceId();
    boolean enabled();
    MarketDataRights rights();
    Duration minimumRequestInterval();
    int maxPageSize();

    /**
     * Fetch one cursor page. A final page must set rangeComplete only after verifying full requested-range coverage;
     * exhaustion alone does not prove that no provider data was skipped.
     */
    HistoricalCandlePage fetchPage(UUID instrumentId, String instrumentMasterVersion, Instant from,
                                   Instant to, String cursor, int pageSize);
}
