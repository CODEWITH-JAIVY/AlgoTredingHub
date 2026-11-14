package com.algotradehub.backend.alerts;

import com.algotradehub.backend.marketdata.CandleBar;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.time.Instant;

@Component
public class AlertMarketDataGate {
    private final boolean enabled;
    private final String sourceId;
    private final boolean storageRights;
    private final boolean derivedRights;
    private final boolean displayRights;
    private final long maxAgeSeconds;

    public AlertMarketDataGate(@Value("${app.alerts.market-data-enabled:false}") boolean enabled,
                               @Value("${app.alerts.source-id:}") String sourceId,
                               @Value("${app.alerts.storage-rights-confirmed:false}") boolean storageRights,
                               @Value("${app.alerts.derived-analytics-rights-confirmed:false}") boolean derivedRights,
                               @Value("${app.alerts.end-user-display-rights-confirmed:false}") boolean displayRights,
                               @Value("${app.alerts.max-candle-age-seconds:120}") long maxAgeSeconds) {
        this.enabled = enabled;
        if (sourceId == null) {
            this.sourceId = "";
        } else {
            this.sourceId = sourceId.trim();
        }
        this.storageRights = storageRights;
        this.derivedRights = derivedRights;
        this.displayRights = displayRights;
        this.maxAgeSeconds = Math.max(1, Math.min(maxAgeSeconds, 3600));
    }

    public String configurationPauseReason() {
        if (!enabled || !StringUtils.hasText(sourceId) || !storageRights || !derivedRights || !displayRights) {
            return "LICENSED_SOURCE_NOT_CONFIRMED";
        }
        return null;
    }

    public String suppressionReason(CandleBar candle, Instant now) {
        String configReason = configurationPauseReason();
        if (configReason != null) return configReason;
        if (candle == null || !candle.finalized() || candle.finalizedAt() == null) return "INCOMPLETE_CANDLE";
        if (candle.timeframeSeconds() != 60) return "UNSUPPORTED_TIMEFRAME";
        if (!sourceId.equals(candle.sourceId()) || !"PROVIDER".equals(candle.quality())) return "UNAPPROVED_OR_SYNTHETIC_SOURCE";
        Instant closeTime = candle.bucketStart().plusSeconds(candle.timeframeSeconds());
        if (closeTime.isAfter(now.plusSeconds(2)) || candle.finalizedAt().isAfter(now.plusSeconds(2))) return "FUTURE_CANDLE";
        if (closeTime.plusSeconds(maxAgeSeconds).isBefore(now)) return "STALE_DATA";
        return null;
    }
}
