package com.algotradehub.backend.marketdata;

import com.algotradehub.backend.orders.Instrument;
import com.algotradehub.backend.orders.InstrumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class MarketDataRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(MarketDataRecoveryService.class);
    private static final Duration RECOVERY_WINDOW = Duration.ofMinutes(8);
    private final InstrumentRepository instruments;
    private final MarketCandleRepository candles;
    private final MarketTickReceiptRepository receipts;
    private final MarketTickReceiptService receiptOutcomes;
    private final CandleAggregator aggregator;

    public MarketDataRecoveryService(InstrumentRepository instruments, MarketCandleRepository candles,
                                     MarketTickReceiptRepository receipts, MarketTickReceiptService receiptOutcomes,
                                     CandleAggregator aggregator) {
        this.instruments = instruments;
        this.candles = candles;
        this.receipts = receipts;
        this.receiptOutcomes = receiptOutcomes;
        this.aggregator = aggregator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreRecentState() {
        var active = instruments.findByActiveTrueOrderByExchangeAscSymbolAsc();
        Map<UUID, Instrument> activeById = new HashMap<>();
        for (Instrument instrument : active) {
            activeById.put(instrument.getId(), instrument);
            candles.findFirstByInstrumentIdAndTimeframeSecondsAndSourceIdOrderByBucketStartDesc(
                    instrument.getId(), CandleAggregator.SUPPORTED_TIMEFRAME_SECONDS, MarketTickNormalizer.DEMO_SOURCE)
                    .map(MarketDataRecoveryService::toBar)
                    .ifPresent(aggregator::restoreLastFinalized);
        }
        Instant cutoff = Instant.now().minus(RECOVERY_WINDOW);
        List<MarketTickReceiptStatus> recoverable = List.of(MarketTickReceiptStatus.RECEIVED, MarketTickReceiptStatus.BUFFERED);
        receiptOutcomes.expireRecoveryWindow(cutoff);
        var recent = receipts.findByReceivedAtAfterAndProcessingStatusInOrderByExchangeTimeAscSequenceNumberAsc(cutoff, recoverable);
        int buffered = 0;
        int reconciliations = 0;
        for (MarketTickReceiptEntity receipt : recent) {
            Instrument instrument = activeById.get(receipt.getInstrumentId());
            String reconciliationReason = null;
            if (instrument == null) {
                reconciliationReason = "RECOVERY_INSTRUMENT_NOT_ACTIVE";
            } else if (!instrument.isSynthetic()) {
                reconciliationReason = "RECOVERY_INSTRUMENT_NOT_SYNTHETIC";
            } else if (!MarketTickNormalizer.DEMO_SOURCE.equals(receipt.getSourceId())) {
                reconciliationReason = "RECOVERY_SOURCE_UNSUPPORTED";
            } else if (!instrument.getMasterVersion().equals(receipt.getMasterVersion())) {
                reconciliationReason = "RECOVERY_MASTER_VERSION_MISMATCH";
            }
            if (reconciliationReason != null) {
                receiptOutcomes.markReconciliationRequired(receipt.getEventKey(), reconciliationReason);
                reconciliations++;
                continue;
            }
            TickAcceptance acceptance = aggregator.accept(receipt.toNormalizedTick());
            if (acceptance.accepted()) {
                receiptOutcomes.markBuffered(receipt.getEventKey());
                buffered++;
            } else {
                receiptOutcomes.markReconciliationRequired(receipt.getEventKey(), "RECOVERY_" + acceptance.reasonCode());
                reconciliations++;
            }
        }
        if (!recent.isEmpty()) log.info("Recent market-data state recovery completed; receiptCount={}, bufferedTickCount={}, reconciliationCount={}",
                recent.size(), buffered, reconciliations);
    }

    private static CandleBar toBar(MarketCandleEntity candle) {
        return new CandleBar(candle.getInstrumentId(), candle.getTimeframeSeconds(), candle.getBucketStart(), candle.getOpen(),
                candle.getHigh(), candle.getLow(), candle.getClose(), candle.getVolume(), candle.getTradeCount(),
                candle.getSourceId(), candle.getQuality(), candle.getMasterVersion(), true, candle.getFinalizedAt());
    }
}
