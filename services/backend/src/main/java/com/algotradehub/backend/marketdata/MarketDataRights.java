package com.algotradehub.backend.marketdata;

public record MarketDataRights(boolean storage, boolean caching, boolean endUserDisplay,
                               boolean redistribution, boolean derivedAnalytics) {
    public boolean permitsHistoricalImportAndDisplay() { return storage && endUserDisplay; }
    public static MarketDataRights unconfirmed() { return new MarketDataRights(false, false, false, false, false); }
}
