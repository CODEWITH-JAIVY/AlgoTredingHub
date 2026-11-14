package com.algotradehub.backend.marketdata;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;

@Service
public class MarketDataSourceRateLimitService {
    private final MarketDataSourceRateLimitRepository limits;
    public MarketDataSourceRateLimitService(MarketDataSourceRateLimitRepository limits) { this.limits = limits; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryReserve(String sourceId, Instant now, Duration minimumInterval) {
        if (sourceId == null || sourceId.isBlank() || sourceId.length() > 50 || minimumInterval == null
                || minimumInterval.isNegative() || minimumInterval.isZero()) return false;
        limits.initializeIfAbsent(sourceId, now.minus(minimumInterval));
        MarketDataSourceRateLimitEntity state = limits.findBySourceIdForUpdate(sourceId).orElseThrow();
        if (now.isBefore(state.getLastRequestAt().plus(minimumInterval))) return false;
        state.recordRequest(now);
        limits.save(state);
        return true;
    }
}
