package com.algotradehub.backend.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class MarketTickIngestionService {
    private static final Logger log = LoggerFactory.getLogger(MarketTickIngestionService.class);
    private final MarketTickNormalizer normalizer;
    private final MarketTickReceiptService receipts;
    private final CandleAggregator aggregator;
    private final MarketDataCandleWriter candleWriter;

    public MarketTickIngestionService(MarketTickNormalizer normalizer, MarketTickReceiptService receipts,
                                     CandleAggregator aggregator, MarketDataCandleWriter candleWriter) {
        this.normalizer = normalizer;
        this.receipts = receipts;
        this.aggregator = aggregator;
        this.candleWriter = candleWriter;
    }

    public TickIngestionResult ingest(ProviderTick providerTick) {
        NormalizedTick tick = normalizer.normalize(providerTick);
        String eventKey = eventKey(tick);
        if (!receipts.claim(eventKey, tick)) return new TickIngestionResult(false, true, "DUPLICATE_TICK", 0);
        TickAcceptance accepted = aggregator.accept(tick);
        if (!accepted.accepted()) {
            log.warn("Market tick rejected after normalization; instrumentId={}, source={}, reason={}",
                    tick.instrumentId(), tick.sourceId(), accepted.reasonCode());
            receipts.markReconciliationRequired(eventKey, accepted.reasonCode());
            return new TickIngestionResult(false, false, accepted.reasonCode(), 0);
        }
        receipts.markBuffered(eventKey);
        return new TickIngestionResult(true, false, "BUFFERED", 0);
    }

    @Scheduled(fixedDelayString = "${app.market-data.finalize-delay-ms:1000}")
    public void finalizeExpiredCandles() {
        aggregator.finalizeEligible(Instant.now(), candleWriter::persistBatch);
    }

    static String eventKey(NormalizedTick tick) {
        String value = tick.sourceId() + '|' + tick.instrumentId() + '|' + tick.exchangeTime() + '|' + tick.sequenceNumber();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", impossible);
        }
    }

    public record TickIngestionResult(boolean accepted, boolean duplicate, String reasonCode, int finalizedCandles) { }
}
