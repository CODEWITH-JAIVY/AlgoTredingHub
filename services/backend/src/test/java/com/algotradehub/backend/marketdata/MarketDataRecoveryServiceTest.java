package com.algotradehub.backend.marketdata;

import com.algotradehub.backend.orders.Instrument;
import com.algotradehub.backend.orders.InstrumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class MarketDataRecoveryServiceTest {
    @Mock private InstrumentRepository instruments;
    @Mock private MarketCandleRepository candles;
    @Mock private MarketTickReceiptRepository receipts;
    @Mock private MarketTickReceiptService receiptOutcomes;

    @Test void restoresFinalizedWatermarkBeforeReplayingRecentSameVersionTicks() {
        UUID instrumentId = UUID.randomUUID();
        Instrument instrument = instrument(instrumentId, "DEMO-1");
        Instant now = Instant.now();
        Instant currentBucket = now.atZone(ZoneId.of("Asia/Kolkata")).withSecond(0).withNano(0).toInstant();
        Instant previousBucket = currentBucket.minusSeconds(60);
        CandleBar previous = new CandleBar(instrumentId, 60, previousBucket, new BigDecimal("100.00"),
                new BigDecimal("101.00"), new BigDecimal("99.00"), new BigDecimal("100.50"),
                10, 1, MarketTickNormalizer.DEMO_SOURCE, "SYNTHETIC", "DEMO-1", true,
                previousBucket.plusSeconds(62));
        NormalizedTick recentTick = new NormalizedTick(instrumentId, new BigDecimal("102.00"), 2,
                currentBucket.plusSeconds(5), now, 7, MarketTickNormalizer.DEMO_SOURCE, true, "DEMO-1");
        MarketTickReceiptEntity matchingReceipt = receipt(instrumentId, "DEMO-1", recentTick);
        when(instruments.findByActiveTrueOrderByExchangeAscSymbolAsc()).thenReturn(List.of(instrument));
        when(candles.findFirstByInstrumentIdAndTimeframeSecondsAndSourceIdOrderByBucketStartDesc(
                instrumentId, CandleAggregator.SUPPORTED_TIMEFRAME_SECONDS, MarketTickNormalizer.DEMO_SOURCE))
                .thenReturn(Optional.of(new MarketCandleEntity(previous)));
        when(receipts.findByReceivedAtAfterAndProcessingStatusInOrderByExchangeTimeAscSequenceNumberAsc(
                any(Instant.class), eq(List.of(MarketTickReceiptStatus.RECEIVED, MarketTickReceiptStatus.BUFFERED))))
                .thenReturn(List.of(matchingReceipt));
        CandleAggregator aggregator = new CandleAggregator(new NseBseEquityFnoDemoCalendar(), Clock.fixed(now, ZoneOffset.UTC));
        MarketDataRecoveryService recovery = new MarketDataRecoveryService(instruments, candles, receipts, receiptOutcomes, aggregator);

        recovery.restoreRecentState();

        CandleBar current = aggregator.current(instrumentId);
        assertNotNull(current);
        assertEquals(currentBucket, current.bucketStart());
        assertEquals(new BigDecimal("102.00"), current.close());
        assertEquals("SYNTHETIC", current.quality());
        verify(receiptOutcomes).markBuffered(matchingReceipt.getEventKey());
    }

    @Test void skipsRecentReceiptsFromAnOldInstrumentMaster() {
        UUID instrumentId = UUID.randomUUID();
        Instrument instrument = instrument(instrumentId, "DEMO-1");
        Instant now = Instant.now();
        Instant bucket = now.atZone(ZoneId.of("Asia/Kolkata")).withSecond(0).withNano(0).toInstant();
        NormalizedTick staleTick = new NormalizedTick(instrumentId, new BigDecimal("102.00"), 2,
                bucket.plusSeconds(5), now, 7, MarketTickNormalizer.DEMO_SOURCE, true, "OLD-MASTER");
        MarketTickReceiptEntity staleReceipt = receipt(instrumentId, "OLD-MASTER", staleTick);
        when(instruments.findByActiveTrueOrderByExchangeAscSymbolAsc()).thenReturn(List.of(instrument));
        when(candles.findFirstByInstrumentIdAndTimeframeSecondsAndSourceIdOrderByBucketStartDesc(
                instrumentId, CandleAggregator.SUPPORTED_TIMEFRAME_SECONDS, MarketTickNormalizer.DEMO_SOURCE))
                .thenReturn(Optional.empty());
        when(receipts.findByReceivedAtAfterAndProcessingStatusInOrderByExchangeTimeAscSequenceNumberAsc(
                any(Instant.class), eq(List.of(MarketTickReceiptStatus.RECEIVED, MarketTickReceiptStatus.BUFFERED))))
                .thenReturn(List.of(staleReceipt));
        CandleAggregator aggregator = new CandleAggregator(new NseBseEquityFnoDemoCalendar(), Clock.fixed(now, ZoneOffset.UTC));
        MarketDataRecoveryService recovery = new MarketDataRecoveryService(instruments, candles, receipts, receiptOutcomes, aggregator);

        recovery.restoreRecentState();

        assertNull(aggregator.current(instrumentId));
        verify(receiptOutcomes).markReconciliationRequired(staleReceipt.getEventKey(), "RECOVERY_MASTER_VERSION_MISMATCH");
    }

    private Instrument instrument(UUID id, String masterVersion) {
        return new Instrument(id, "DEMO-RELIANCE", "NSE", "CASH", "DEMO-NSE-CASH-001", "EQUITY",
                1, new BigDecimal("0.05"), new BigDecimal("100.00"), masterVersion);
    }

    private MarketTickReceiptEntity receipt(UUID instrumentId, String masterVersion, NormalizedTick tick) {
        MarketTickReceiptEntity receipt = mock(MarketTickReceiptEntity.class);
        when(receipt.getEventKey()).thenReturn(UUID.randomUUID().toString());
        when(receipt.getInstrumentId()).thenReturn(instrumentId);
        when(receipt.getSourceId()).thenReturn(tick.sourceId());
        when(receipt.getMasterVersion()).thenReturn(masterVersion);
        org.mockito.Mockito.lenient().when(receipt.toNormalizedTick()).thenReturn(tick);
        return receipt;
    }
}
