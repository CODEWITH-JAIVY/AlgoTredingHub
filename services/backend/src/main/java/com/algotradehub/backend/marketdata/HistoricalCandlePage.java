package com.algotradehub.backend.marketdata;

import java.util.List;

/** One provider page; rangeComplete means the whole requested interval has been checked for coverage. */
public record HistoricalCandlePage(List<CandleBar> candles, String nextCursor, boolean exhausted, boolean rangeComplete) {
    public HistoricalCandlePage {
        if (candles == null) {
            throw new IllegalArgumentException("Provider candle list is required.");
        }
        candles = List.copyOf(candles);
    }
}
