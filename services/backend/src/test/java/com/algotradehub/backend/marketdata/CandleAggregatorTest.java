package com.algotradehub.backend.marketdata;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandleAggregatorTest {
    private static final UUID INSTRUMENT = UUID.fromString("7b774ec2-a516-4a29-b2b4-3945256c7c2a");
    private static final Instant START = Instant.parse("2026-10-20T03:45:00Z");
    private CandleAggregator aggregator() { return new CandleAggregator(new NseBseEquityFnoDemoCalendar(), Clock.fixed(START, ZoneOffset.UTC)); }

    @Test void aggregatesOutOfOrderTicksByExchangeTimeAndKeepsCurrentBarUnfinalized() {
        CandleAggregator aggregator = aggregator();
        assertTrue(aggregator.accept(tick(20, 2, "100.00", 2)).accepted());
        assertTrue(aggregator.accept(tick(10, 1, "99.00", 1)).accepted());
        assertTrue(aggregator.accept(tick(40, 3, "101.00", 3)).accepted());
        CandleBar current = aggregator.current(INSTRUMENT);
        assertNotNull(current);
        assertFalse(current.finalized());
        assertEquals(0, current.open().compareTo(new BigDecimal("99.00")));
        assertEquals(0, current.close().compareTo(new BigDecimal("101.00")));
        AggregationBatch batch = aggregator.advanceWatermark(START.plusSeconds(62));
        assertEquals(1, batch.finalizedCandles().size());
        CandleBar candle = batch.finalizedCandles().getFirst();
        assertTrue(candle.finalized());
        assertEquals(0, candle.high().compareTo(new BigDecimal("101.00")));
        assertEquals(0, candle.low().compareTo(new BigDecimal("99.00")));
        assertEquals(6, candle.volume());
        assertEquals(3, candle.tradeCount());
        assertNull(aggregator.current(INSTRUMENT));
    }

    @Test void detectsMissingMinuteWithoutInventingAnEmptyCandle() {
        CandleAggregator aggregator = aggregator();
        aggregator.accept(tick(1, 1, "100.00", 1));
        aggregator.accept(tick(Instant.parse("2026-10-20T03:47:10Z"), 2, "100.10", 1));
        AggregationBatch batch = aggregator.advanceWatermark(Instant.parse("2026-10-20T03:48:02Z"));
        assertEquals(2, batch.finalizedCandles().size());
        assertEquals(1, batch.gaps().size());
        assertEquals(Instant.parse("2026-10-20T03:46:00Z"), batch.gaps().getFirst().gapStart());
        assertEquals(Instant.parse("2026-10-20T03:47:00Z"), batch.gaps().getFirst().gapEnd());
    }

    @Test void rejectsLateTicksAfterFinalizationAndDoesNotRewriteClosedBars() {
        CandleAggregator aggregator = aggregator();
        aggregator.accept(tick(1, 1, "100.00", 1));
        aggregator.advanceWatermark(START.plusSeconds(62));
        TickAcceptance late = aggregator.accept(tick(50, 2, "110.00", 100));
        assertFalse(late.accepted());
        assertEquals("LATE_TICK_REQUIRES_RECONCILIATION", late.reasonCode());
    }

    @Test void rejectsSourceSwitchInAnOpenCandleAndProtectsVolumeOverflow() {
        CandleAggregator aggregator = aggregator();
        aggregator.accept(tick(1, 1, "100.00", 1));
        NormalizedTick changedSource = new NormalizedTick(INSTRUMENT, new BigDecimal("100.05"), 1,
                START.plusSeconds(20), START.plusSeconds(20), 2, "OTHER_SOURCE", false, "DEMO-1");
        assertEquals("CANDLE_SOURCE_OR_MASTER_CHANGED", aggregator.accept(changedSource).reasonCode());
        CandleAggregator overflow = aggregator();
        overflow.accept(tick(1, Long.MAX_VALUE, "100.00", 1));
        assertEquals("CANDLE_VOLUME_OVERFLOW", overflow.accept(tick(2, 1, "100.05", 2)).reasonCode());
    }

    @Test void minuteBucketsAreConsistentAcrossUtcAndIndiaOffset() {
        assertEquals(Instant.parse("2026-10-20T03:45:00Z"), CandleAggregator.bucketStart(Instant.parse("2026-10-20T03:45:59.999Z")));
        assertEquals(CandleAggregator.bucketStart(Instant.parse("2026-10-20T03:45:00Z")),
                CandleAggregator.bucketStart(Instant.parse("2026-10-20T09:15:42+05:30")));
    }

    @Test void receiptKeyIsStableForDuplicateDelivery() {
        NormalizedTick tick = tick(4, 2, "100.00", 1);
        assertEquals(MarketTickIngestionService.eventKey(tick), MarketTickIngestionService.eventKey(tick));
    }

    @Test void databaseFailureLeavesClosedBarBufferedForRetry() {
        CandleAggregator aggregator = aggregator();
        aggregator.accept(tick(1, 1, "100.00", 1));
        Instant watermark = START.plusSeconds(62);
        AggregationBatch failed = aggregator.finalizeEligible(watermark, batch -> { throw new IllegalStateException("database unavailable"); });
        assertTrue(failed.finalizedCandles().isEmpty());
        assertNotNull(aggregator.current(INSTRUMENT));
        AggregationBatch retried = aggregator.finalizeEligible(watermark, batch -> { });
        assertEquals(1, retried.finalizedCandles().size());
        assertNull(aggregator.current(INSTRUMENT));
    }

    private NormalizedTick tick(int seconds, long volume, String price, long sequence) {
        return tick(START.plusSeconds(seconds), sequence, price, volume);
    }
    private NormalizedTick tick(Instant time, long sequence, String price, long volume) {
        return new NormalizedTick(INSTRUMENT, new BigDecimal(price), volume, time, time, sequence,
                "SYNTHETIC_DEMO", true, "DEMO-1");
    }
}
