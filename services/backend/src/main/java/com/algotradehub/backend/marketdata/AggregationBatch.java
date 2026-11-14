package com.algotradehub.backend.marketdata;

import java.util.List;

public record AggregationBatch(List<CandleBar> finalizedCandles, List<MarketDataGap> gaps) {
    public AggregationBatch {
        finalizedCandles = List.copyOf(finalizedCandles);
        gaps = List.copyOf(gaps);
    }
    public static AggregationBatch empty() { return new AggregationBatch(List.of(), List.of()); }
}
