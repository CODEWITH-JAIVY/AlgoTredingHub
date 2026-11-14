package com.algotradehub.backend.marketdata;

import com.algotradehub.backend.alerts.PriceAlertEvaluator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MarketDataCandleWriter {
    private final MarketCandleRepository candles;
    private final MarketDataGapRepository gaps;
    private final PriceAlertEvaluator alerts;
    private final MarketTickReceiptService receipts;
    public MarketDataCandleWriter(MarketCandleRepository candles, MarketDataGapRepository gaps, PriceAlertEvaluator alerts,
                                  MarketTickReceiptService receipts) {
        this.candles = candles;
        this.gaps = gaps;
        this.alerts = alerts;
        this.receipts = receipts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistBatch(AggregationBatch batch) {
        for (CandleBar bar : batch.finalizedCandles()) {
            if (!candles.existsByInstrumentIdAndTimeframeSecondsAndBucketStartAndSourceId(
                    bar.instrumentId(), bar.timeframeSeconds(), bar.bucketStart(), bar.sourceId())) {
                candles.save(new MarketCandleEntity(bar));
                alerts.evaluate(bar);
            }
            receipts.markFinalized(bar);
        }
        for (MarketDataGap gap : batch.gaps()) {
            if (!gaps.existsByInstrumentIdAndSourceIdAndGapStartAndGapEnd(
                    gap.instrumentId(), gap.sourceId(), gap.gapStart(), gap.gapEnd())) {
                gaps.save(new MarketDataGapEntity(gap));
            }
        }
    }
}
