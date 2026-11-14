package com.algotradehub.backend.marketdata;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class MarketTickReceiptService {
    private static final Duration DEDUPLICATION_RETENTION = Duration.ofDays(7);
    private static final List<MarketTickReceiptStatus> RECOVERABLE_STATUSES =
            List.of(MarketTickReceiptStatus.RECEIVED, MarketTickReceiptStatus.BUFFERED);
    private final MarketTickReceiptRepository receipts;
    private final MarketTickReceiptClaimWriter claimWriter;
    public MarketTickReceiptService(MarketTickReceiptRepository receipts, MarketTickReceiptClaimWriter claimWriter) {
        this.receipts = receipts;
        this.claimWriter = claimWriter;
    }

    public boolean claim(String eventKey, NormalizedTick tick) {
        try {
            claimWriter.insert(eventKey, tick);
            return true;
        } catch (DataIntegrityViolationException conflict) {
            if (receipts.existsById(eventKey)) return false;
            throw conflict;
        }
    }

    @Transactional
    public void markBuffered(String eventKey) {
        int updated = receipts.transitionFromStatus(eventKey, MarketTickReceiptStatus.RECEIVED,
                MarketTickReceiptStatus.BUFFERED, null);
        requireTransition(eventKey, MarketTickReceiptStatus.BUFFERED, null, updated);
    }

    @Transactional
    public void markReconciliationRequired(String eventKey, String outcomeCode) {
        if (outcomeCode == null || !outcomeCode.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("A bounded machine-readable reconciliation reason is required.");
        }
        int updated = receipts.requireReconciliation(eventKey, RECOVERABLE_STATUSES,
                MarketTickReceiptStatus.RECONCILIATION_REQUIRED, outcomeCode);
        requireTransition(eventKey, MarketTickReceiptStatus.RECONCILIATION_REQUIRED, outcomeCode, updated);
    }

    @Transactional
    public void markFinalized(CandleBar bar) {
        if (bar == null || !bar.finalized() || bar.timeframeSeconds() != CandleAggregator.SUPPORTED_TIMEFRAME_SECONDS) {
            throw new IllegalArgumentException("Only finalized supported candles can complete tick receipts.");
        }
        receipts.markCandleTicksFinalized(bar.instrumentId(), bar.sourceId(), bar.masterVersion(), bar.bucketStart(),
                bar.bucketStart().plusSeconds(CandleAggregator.SUPPORTED_TIMEFRAME_SECONDS),
                MarketTickReceiptStatus.BUFFERED, MarketTickReceiptStatus.FINALIZED);
    }

    @Transactional
    public void expireRecoveryWindow(Instant cutoff) {
        receipts.expireRecoveryWindow(cutoff, RECOVERABLE_STATUSES,
                MarketTickReceiptStatus.RECONCILIATION_REQUIRED, "RECOVERY_WINDOW_EXPIRED");
    }

    private void requireTransition(String eventKey, MarketTickReceiptStatus expectedStatus,
                                   String expectedOutcome, int updated) {
        if (updated == 1) return;
        MarketTickReceiptEntity existing = receipts.findById(eventKey)
                .orElseThrow(() -> new IllegalStateException("Market tick receipt disappeared before outcome was recorded."));
        if (existing.getProcessingStatus() != expectedStatus || !Objects.equals(existing.getOutcomeCode(), expectedOutcome)) {
            throw new IllegalStateException("Market tick receipt outcome could not be recorded safely.");
        }
    }

    @Scheduled(cron = "0 15 0 * * *", zone = "Asia/Kolkata")
    @Transactional
    public void removeExpiredReceipts() {
        receipts.deleteByReceivedAtBefore(Instant.now().minus(DEDUPLICATION_RETENTION));
    }
}
