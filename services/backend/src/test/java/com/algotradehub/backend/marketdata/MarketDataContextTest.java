package com.algotradehub.backend.marketdata;

import com.algotradehub.backend.auth.RegistrationGateRepository;
import com.algotradehub.backend.orders.InstrumentRepository;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.ActiveProfiles;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class MarketDataContextTest {
    @Autowired private InstrumentRepository instruments;
    @Autowired private MarketCandleService candleService;
    @Autowired private MarketDataController controller;
    @Autowired private RegistrationGateRepository registrationGates;
    @Autowired private MarketCandleRepository candles;
    @Autowired private MarketDataGapRepository gaps;
    @Autowired private MarketTickReceiptRepository tickReceipts;
    @Autowired private MarketTickReceiptService tickReceiptService;

    @Test void testProfileStartsWithSyntheticMasterAndClearlyLabeledCandles() {
        var instrument = instruments.findByActiveTrueOrderByExchangeAscSymbolAsc().getFirst();
        var status = controller.dataStatus();
        assertFalse(status.licensedSourceConfigured());
        assertTrue(status.sources().stream().allMatch(MarketDataController.SourceStatus::synthetic));
        Instant from = SyntheticDemoHistoryProvider.DEMO_DATE.atTime(9, 15).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
        var series = candleService.query(instrument.getId(), "1m", from, from.plusSeconds(120 * 60), null);
        assertEquals("SYNTHETIC_DEMO", series.source());
        assertEquals("SIMULATED", series.freshness());
        assertEquals(120, series.candles().size());
    }

    @Test @Transactional void singleUserRegistrationGateStartsOpenAndCanBeLocked() {
        var gate = registrationGates.findByIdForUpdate((short) 1).orElseThrow();
        assertFalse(gate.isInitialSignupComplete());
    }

    @Test @Transactional void boundedCandleAndGapQueriesFilterExactSourceInTheDatabase() {
        var instrument = instruments.findByActiveTrueOrderByExchangeAscSymbolAsc().getFirst();
        Instant from = Instant.parse("2026-10-20T03:45:00Z");
        Instant to = from.plusSeconds(60);
        Instant now = Instant.parse("2026-10-20T03:46:00Z");
        var sourceACandle = new CandleBar(instrument.getId(), 60, from, new BigDecimal("100.00"),
                new BigDecimal("100.10"), new BigDecimal("99.95"), new BigDecimal("100.05"),
                10, 2, "SOURCE_A", "PROVIDER", instrument.getMasterVersion(), true, now);
        var sourceBCandle = new CandleBar(instrument.getId(), 60, from, new BigDecimal("101.00"),
                new BigDecimal("101.10"), new BigDecimal("100.95"), new BigDecimal("101.05"),
                12, 3, "SOURCE_B", "PROVIDER", instrument.getMasterVersion(), true, now);
        candles.saveAll(java.util.List.of(new MarketCandleEntity(sourceACandle), new MarketCandleEntity(sourceBCandle)));
        gaps.save(new MarketDataGapEntity(new MarketDataGap(instrument.getId(), "SOURCE_A", from.minusSeconds(60),
                to, "MISSING_INTERVAL", now)));
        gaps.save(new MarketDataGapEntity(new MarketDataGap(instrument.getId(), "SOURCE_B", from.minusSeconds(60),
                to, "MISSING_INTERVAL", now)));

        var series = candleService.query(instrument.getId(), "1m", from, to, "SOURCE_A");

        assertEquals("SOURCE_A", series.source());
        assertEquals(1, series.candles().size());
        assertEquals(new BigDecimal("100.05"), series.candles().getFirst().close());
        assertEquals(1, series.gaps().size());
        assertEquals("SOURCE_A", series.gaps().getFirst().source());
    }

    @Test @Transactional void tickReceiptMovesThroughBufferedAndFinalizedStatesWithItsCandle() {
        var instrument = instruments.findByActiveTrueOrderByExchangeAscSymbolAsc().getFirst();
        Instant now = Instant.now();
        Instant bucketStart = now.minusSeconds(120).atZone(ZoneId.of("Asia/Kolkata"))
                .withSecond(0).withNano(0).toInstant();
        String eventKey = UUID.randomUUID().toString().replace("-", "").repeat(2);
        NormalizedTick tick = new NormalizedTick(instrument.getId(), new BigDecimal("100.00"), 2,
                bucketStart.plusSeconds(5), now, 1, MarketTickNormalizer.DEMO_SOURCE, true,
                instrument.getMasterVersion());
        assertTrue(tickReceiptService.claim(eventKey, tick));
        assertFalse(tickReceiptService.claim(eventKey, tick));
        tickReceiptService.markBuffered(eventKey);
        assertEquals(MarketTickReceiptStatus.BUFFERED,
                tickReceipts.findById(eventKey).orElseThrow().getProcessingStatus());

        BigDecimal price = new BigDecimal("100.00");
        CandleBar finalizedBar = new CandleBar(instrument.getId(), CandleAggregator.SUPPORTED_TIMEFRAME_SECONDS,
                bucketStart, price, price, price, price, 2, 1, MarketTickNormalizer.DEMO_SOURCE,
                "SYNTHETIC", instrument.getMasterVersion(), true, now);
        tickReceiptService.markFinalized(finalizedBar);

        assertEquals(MarketTickReceiptStatus.FINALIZED,
                tickReceipts.findById(eventKey).orElseThrow().getProcessingStatus());
    }
}
