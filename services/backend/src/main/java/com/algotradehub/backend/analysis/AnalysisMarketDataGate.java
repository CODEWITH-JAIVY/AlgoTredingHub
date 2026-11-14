package com.algotradehub.backend.analysis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AnalysisMarketDataGate {
    private final String providerSourceId;
    private final boolean storageRights;
    private final boolean cacheRights;
    private final boolean displayRights;
    private final boolean derivedAnalyticsRights;
    private final boolean corporateActionsVerified;

    public AnalysisMarketDataGate(@Value("${app.analysis.provider-source-id:}") String providerSourceId,
                                  @Value("${app.analysis.provider-storage-rights-confirmed:false}") boolean storageRights,
                                  @Value("${app.analysis.provider-cache-rights-confirmed:false}") boolean cacheRights,
                                  @Value("${app.analysis.provider-display-rights-confirmed:false}") boolean displayRights,
                                  @Value("${app.analysis.provider-derived-rights-confirmed:false}") boolean derivedAnalyticsRights,
                                  @Value("${app.analysis.corporate-actions-verified:false}") boolean corporateActionsVerified) {
        String configuredProviderSourceId = "";
        if (providerSourceId != null) configuredProviderSourceId = providerSourceId.trim();
        this.providerSourceId = configuredProviderSourceId;
        this.storageRights = storageRights;
        this.cacheRights = cacheRights;
        this.displayRights = displayRights;
        this.derivedAnalyticsRights = derivedAnalyticsRights;
        this.corporateActionsVerified = corporateActionsVerified;
    }

    public boolean permitsProviderAnalysis(String sourceId) {
        return sourceId != null && !providerSourceId.isEmpty() && providerSourceId.equals(sourceId)
                && storageRights && cacheRights && displayRights && derivedAnalyticsRights;
    }

    public boolean corporateActionsVerified() {
        return corporateActionsVerified;
    }
}
