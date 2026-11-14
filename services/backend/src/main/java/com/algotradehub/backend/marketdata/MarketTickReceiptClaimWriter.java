package com.algotradehub.backend.marketdata;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MarketTickReceiptClaimWriter {
    private final MarketTickReceiptRepository receipts;

    public MarketTickReceiptClaimWriter(MarketTickReceiptRepository receipts) {
        this.receipts = receipts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(String eventKey, NormalizedTick tick) {
        int inserted = receipts.insertClaim(eventKey, tick.instrumentId(), tick.sourceId(), tick.sequenceNumber(),
                tick.exchangeTime(), tick.receivedAt(), tick.price(), tick.tradeQuantity(), tick.masterVersion());
        if (inserted != 1) throw new IllegalStateException("Market tick receipt was not inserted.");
    }
}
