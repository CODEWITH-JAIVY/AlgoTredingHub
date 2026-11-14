package com.algotradehub.backend.marketdata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketTickIngestionServiceTest {
    @Mock private MarketTickNormalizer normalizer;
    @Mock private MarketTickReceiptService receipts;
    @Mock private CandleAggregator aggregator;
    @Mock private MarketDataCandleWriter candleWriter;

    @Test
    void marksAcceptedTicksBufferedForRestartRecovery() {
        ProviderTick providerTick = providerTick();
        NormalizedTick tick = normalizedTick();
        when(normalizer.normalize(providerTick)).thenReturn(tick);
        when(receipts.claim(MarketTickIngestionService.eventKey(tick), tick)).thenReturn(true);
        when(aggregator.accept(tick)).thenReturn(new TickAcceptance(true, "ACCEPTED"));
        MarketTickIngestionService service = new MarketTickIngestionService(normalizer, receipts, aggregator, candleWriter);

        MarketTickIngestionService.TickIngestionResult result = service.ingest(providerTick);

        assertEquals(new MarketTickIngestionService.TickIngestionResult(true, false, "BUFFERED", 0), result);
        verify(receipts).markBuffered(MarketTickIngestionService.eventKey(tick));
    }

    @Test
    void recordsRejectedTicksForReconciliation() {
        ProviderTick providerTick = providerTick();
        NormalizedTick tick = normalizedTick();
        when(normalizer.normalize(providerTick)).thenReturn(tick);
        when(receipts.claim(MarketTickIngestionService.eventKey(tick), tick)).thenReturn(true);
        when(aggregator.accept(tick)).thenReturn(new TickAcceptance(false, "LATE_TICK_REQUIRES_RECONCILIATION"));
        MarketTickIngestionService service = new MarketTickIngestionService(normalizer, receipts, aggregator, candleWriter);

        MarketTickIngestionService.TickIngestionResult result = service.ingest(providerTick);

        assertEquals(new MarketTickIngestionService.TickIngestionResult(false, false,
                "LATE_TICK_REQUIRES_RECONCILIATION", 0), result);
        verify(receipts).markReconciliationRequired(MarketTickIngestionService.eventKey(tick),
                "LATE_TICK_REQUIRES_RECONCILIATION");
    }

    @Test
    void duplicateTicksDoNotEnterTheAggregatorAgain() {
        ProviderTick providerTick = providerTick();
        NormalizedTick tick = normalizedTick();
        when(normalizer.normalize(providerTick)).thenReturn(tick);
        when(receipts.claim(MarketTickIngestionService.eventKey(tick), tick)).thenReturn(false);
        MarketTickIngestionService service = new MarketTickIngestionService(normalizer, receipts, aggregator, candleWriter);

        MarketTickIngestionService.TickIngestionResult result = service.ingest(providerTick);

        assertEquals(new MarketTickIngestionService.TickIngestionResult(false, true, "DUPLICATE_TICK", 0), result);
        verifyNoInteractions(aggregator);
    }

    private static ProviderTick providerTick() {
        return new ProviderTick(MarketTickNormalizer.DEMO_SOURCE, "NSE", "DEMO-NSE-CASH-001", "DEMO-1",
                new BigDecimal("100.00"), 2, Instant.parse("2026-10-22T10:00:05Z"), 1);
    }

    private static NormalizedTick normalizedTick() {
        return new NormalizedTick(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                new BigDecimal("100.00"), 2, Instant.parse("2026-10-22T10:00:05Z"),
                Instant.parse("2026-10-22T10:00:05Z"), 1, MarketTickNormalizer.DEMO_SOURCE, true, "DEMO-1");
    }
}
