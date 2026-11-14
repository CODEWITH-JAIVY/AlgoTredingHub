package com.algotradehub.backend.marketdata;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CandleAggregator {
    private static final Logger log = LoggerFactory.getLogger(CandleAggregator.class);
    public static final int SUPPORTED_TIMEFRAME_SECONDS = 60;
    private static final Duration EVENT_TIME_GRACE = Duration.ofSeconds(2);
    private static final Duration PERSISTENCE_WARNING_INTERVAL = Duration.ofSeconds(30);
    private static final Duration BAR_LENGTH = Duration.ofSeconds(SUPPORTED_TIMEFRAME_SECONDS);
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Kolkata");
    private static final int MAX_PENDING_MINUTES_PER_INSTRUMENT = 10;
    private final Map<UUID, InstrumentBuffer> buffers = new ConcurrentHashMap<>();
    private final MarketSessionCalendar sessions;
    private final Clock clock;

    @Autowired
    public CandleAggregator(MarketSessionCalendar sessions) { this(sessions, Clock.systemUTC()); }

    CandleAggregator(MarketSessionCalendar sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    public TickAcceptance accept(NormalizedTick tick) {
        Instant bucket = bucketStart(tick.exchangeTime());
        InstrumentBuffer buffer = buffers.computeIfAbsent(tick.instrumentId(), ignored -> new InstrumentBuffer());
        synchronized (buffer) {
            if (buffer.lastFinalized != null && (!buffer.lastFinalized.sourceId().equals(tick.sourceId())
                    || !buffer.lastFinalized.masterVersion().equals(tick.masterVersion()))) {
                return new TickAcceptance(false, "CANDLE_SOURCE_OR_MASTER_CHANGED");
            }
            if (buffer.lastFinalized != null && !bucket.isAfter(buffer.lastFinalized.bucketStart())) {
                return new TickAcceptance(false, "LATE_TICK_REQUIRES_RECONCILIATION");
            }
            MutableCandle candle = buffer.pending.get(bucket);
            if (candle == null && buffer.pending.size() >= MAX_PENDING_MINUTES_PER_INSTRUMENT) {
                return new TickAcceptance(false, "PENDING_CANDLE_LIMIT");
            }
            if (candle == null) {
                candle = new MutableCandle(tick, bucket);
                buffer.pending.put(bucket, candle);
            } else if (!candle.sourceId.equals(tick.sourceId()) || !candle.masterVersion.equals(tick.masterVersion())) {
                return new TickAcceptance(false, "CANDLE_SOURCE_OR_MASTER_CHANGED");
            } else {
                try {
                    candle.add(tick);
                } catch (ArithmeticException exception) {
                    return new TickAcceptance(false, "CANDLE_VOLUME_OVERFLOW");
                }
            }
            return new TickAcceptance(true, "ACCEPTED");
        }
    }

    public AggregationBatch advanceWatermark() { return advanceWatermark(clock.instant()); }

    public AggregationBatch advanceWatermark(Instant watermark) {
        return finalizeEligible(watermark, ignored -> { });
    }

    /** The persistence callback runs while each instrument buffer is locked. A failed write leaves its bars pending. */
    public AggregationBatch finalizeEligible(Instant watermark, java.util.function.Consumer<AggregationBatch> persist) {
        List<CandleBar> persisted = new ArrayList<>();
        List<MarketDataGap> persistedGaps = new ArrayList<>();
        for (var entry : buffers.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            InstrumentBuffer buffer = entry.getValue();
            synchronized (buffer) {
                AggregationBatch batch = eligibleBatch(entry.getKey(), buffer, watermark);
                if (batch.finalizedCandles().isEmpty()) continue;
                try {
                    persist.accept(batch);
                } catch (RuntimeException failure) {
                    if (buffer.lastPersistenceWarning == null || !watermark.isBefore(buffer.lastPersistenceWarning.plus(PERSISTENCE_WARNING_INTERVAL))) {
                        log.warn("Candle batch persistence failed and remains buffered; instrumentId={}, errorType={}",
                                entry.getKey(), failure.getClass().getSimpleName());
                        buffer.lastPersistenceWarning = watermark;
                    }
                    continue;
                }
                buffer.lastPersistenceWarning = null;
                for (CandleBar bar : batch.finalizedCandles()) {
                    buffer.pending.remove(bar.bucketStart());
                    buffer.lastFinalized = bar;
                }
                persisted.addAll(batch.finalizedCandles());
                persistedGaps.addAll(batch.gaps());
            }
        }
        return new AggregationBatch(persisted, persistedGaps);
    }

    public void restoreLastFinalized(CandleBar bar) {
        if (bar == null || !bar.finalized()) throw new IllegalArgumentException("Only a finalized candle can restore the watermark.");
        InstrumentBuffer buffer = buffers.computeIfAbsent(bar.instrumentId(), ignored -> new InstrumentBuffer());
        synchronized (buffer) {
            if (!buffer.pending.isEmpty()) throw new IllegalStateException("Cannot restore a watermark after tick ingestion has started.");
            if (buffer.lastFinalized == null || bar.bucketStart().isAfter(buffer.lastFinalized.bucketStart())) buffer.lastFinalized = bar;
        }
    }

    private AggregationBatch eligibleBatch(UUID instrumentId, InstrumentBuffer buffer, Instant watermark) {
        List<CandleBar> closed = new ArrayList<>();
        List<MarketDataGap> gaps = new ArrayList<>();
        CandleBar previous = buffer.lastFinalized;
        for (var entry : buffer.pending.entrySet()) {
            Instant eligibleAt = entry.getKey().plus(BAR_LENGTH).plus(EVENT_TIME_GRACE);
            if (watermark.isBefore(eligibleAt)) break;
            CandleBar finalized = entry.getValue().toFinalized(eligibleAt);
            if (previous != null) {
                MarketDataGap gap = detectSameSessionGap(previous, finalized, watermark);
                if (gap != null) gaps.add(gap);
            }
            closed.add(finalized);
            previous = finalized;
        }
        return new AggregationBatch(closed, gaps);
    }

    public CandleBar current(UUID instrumentId) {
        InstrumentBuffer buffer = buffers.get(instrumentId);
        if (buffer == null) return null;
        synchronized (buffer) {
            if (buffer.pending.isEmpty()) return null;
            return buffer.pending.lastEntry().getValue().toCurrent();
        }
    }

    static Instant bucketStart(Instant eventTime) {
        var localMinute = eventTime.atZone(EXCHANGE_ZONE).withSecond(0).withNano(0);
        return localMinute.toInstant();
    }

    private MarketDataGap detectSameSessionGap(CandleBar previous, CandleBar current, Instant detectedAt) {
        Instant gapStart = previous.bucketStart().plus(BAR_LENGTH);
        if (!current.bucketStart().isAfter(gapStart) || !sessions.isSameTradingDate(previous.bucketStart(), current.bucketStart())) return null;
        return new MarketDataGap(current.instrumentId(), current.sourceId(), gapStart, current.bucketStart(), "MISSING_TICKS", detectedAt);
    }

    private static final class InstrumentBuffer {
        private final TreeMap<Instant, MutableCandle> pending = new TreeMap<>();
        private CandleBar lastFinalized;
        private Instant lastPersistenceWarning;
    }

    private static final class MutableCandle {
        private final UUID instrumentId;
        private final Instant bucketStart;
        private final String sourceId;
        private final String quality;
        private final String masterVersion;
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private Instant firstTime;
        private Instant lastTime;
        private long firstSequence;
        private long lastSequence;
        private long volume;
        private long tradeCount;

        private MutableCandle(NormalizedTick tick, Instant bucketStart) {
            this.instrumentId = tick.instrumentId();
            this.bucketStart = bucketStart;
            this.sourceId = tick.sourceId();
            if (tick.synthetic()) {
                this.quality = "SYNTHETIC";
            } else {
                this.quality = "PROVIDER";
            }
            this.masterVersion = tick.masterVersion();
            this.open = tick.price();
            this.high = tick.price();
            this.low = tick.price();
            this.close = tick.price();
            this.firstTime = tick.exchangeTime();
            this.lastTime = tick.exchangeTime();
            this.firstSequence = tick.sequenceNumber();
            this.lastSequence = tick.sequenceNumber();
            this.volume = tick.tradeQuantity();
            this.tradeCount = 1;
        }

        private void add(NormalizedTick tick) {
            if (tick.price().compareTo(high) > 0) high = tick.price();
            if (tick.price().compareTo(low) < 0) low = tick.price();
            if (tick.exchangeTime().isBefore(firstTime) || (tick.exchangeTime().equals(firstTime) && tick.sequenceNumber() < firstSequence)) {
                firstTime = tick.exchangeTime();
                firstSequence = tick.sequenceNumber();
                open = tick.price();
            }
            if (tick.exchangeTime().isAfter(lastTime) || (tick.exchangeTime().equals(lastTime) && tick.sequenceNumber() > lastSequence)) {
                lastTime = tick.exchangeTime();
                lastSequence = tick.sequenceNumber();
                close = tick.price();
            }
            volume = Math.addExact(volume, tick.tradeQuantity());
            tradeCount = Math.addExact(tradeCount, 1);
        }

        private CandleBar toCurrent() {
            return new CandleBar(instrumentId, SUPPORTED_TIMEFRAME_SECONDS, bucketStart, open, high, low, close,
                    volume, tradeCount, sourceId, quality, masterVersion, false, null);
        }

        private CandleBar toFinalized(Instant finalizedAt) {
            return new CandleBar(instrumentId, SUPPORTED_TIMEFRAME_SECONDS, bucketStart, open, high, low, close,
                    volume, tradeCount, sourceId, quality, masterVersion, true, finalizedAt);
        }
    }
}
