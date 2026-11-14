package com.algotradehub.backend.marketdata;

import com.algotradehub.backend.common.ApiException;
import com.algotradehub.backend.orders.Instrument;
import com.algotradehub.backend.orders.InstrumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketCandleServiceTest {
    private static final UUID ID = UUID.fromString("7b774ec2-a516-4a29-b2b4-3945256c7c2a");
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final MarketCandleRepository candles = mock(MarketCandleRepository.class);
    private final MarketDataGapRepository gaps = mock(MarketDataGapRepository.class);
    private final CandleAggregator aggregator = mock(CandleAggregator.class);
    private final Instrument instrument = new Instrument(ID, "DEMO-NSE-001", "NSE", "CASH", "DEMO-ALPHA", "EQUITY", 1,
            new BigDecimal("0.05"), new BigDecimal("100.00"), "DEMO-1");
    private final MarketCandleService service = new MarketCandleService(instruments, candles, gaps, aggregator, new SyntheticDemoHistoryProvider());

    @Test void demoHistoryIsDeterministicAndNeverMarkedFresh() {
        when(instruments.findById(ID)).thenReturn(Optional.of(instrument));
        when(candles.findByInstrumentIdAndTimeframeSecondsAndBucketStartGreaterThanEqualAndBucketStartLessThanOrderByBucketStartAsc(
                ID, 60, demoStart(), demoEnd(), PageRequest.of(0, 10_081))).thenReturn(List.of());
        when(gaps.findByInstrumentIdAndGapStartLessThanAndGapEndGreaterThanOrderByGapStartAsc(
                ID, demoEnd(), demoStart(), PageRequest.of(0, 1_001))).thenReturn(List.of());
        MarketCandleService.CandleSeries result = service.query(ID, "1m", demoStart(), demoEnd(), null);
        assertEquals("SYNTHETIC_DEMO", result.source());
        assertEquals("SIMULATED", result.freshness());
        assertFalse(result.fresh());
        assertEquals(120, result.candles().size());
        assertEquals(result.candles(), service.query(ID, "1m", demoStart(), demoEnd(), null).candles());
    }

    @Test void missingRangeIsExplicitlyUnavailableAndNotLabeledSynthetic() {
        when(instruments.findById(ID)).thenReturn(Optional.of(instrument));
        Instant from = Instant.parse("2026-10-20T03:45:00Z");
        Instant to = from.plusSeconds(60);
        when(candles.findByInstrumentIdAndTimeframeSecondsAndBucketStartGreaterThanEqualAndBucketStartLessThanOrderByBucketStartAsc(
                ID, 60, from, to, PageRequest.of(0, 10_081))).thenReturn(List.of());
        when(gaps.findByInstrumentIdAndGapStartLessThanAndGapEndGreaterThanOrderByGapStartAsc(
                ID, to, from, PageRequest.of(0, 1_001))).thenReturn(List.of());
        MarketCandleService.CandleSeries result = service.query(ID, "1m", from, to, null);
        assertEquals("UNAVAILABLE", result.source());
        assertEquals("NO_DATA", result.freshness());
        assertFalse(result.synthetic());
        assertFalse(result.fresh());
        assertEquals(List.of("NO_DATA_FOR_RANGE", "LICENSED_SOURCE_NOT_CONFIGURED"), result.warnings());
    }

    @Test void rejectsUnsupportedIntervalsAndOversizedOrInvertedRanges() {
        assertCode("UNSUPPORTED_TIMEFRAME", () -> service.query(ID, "5m", demoStart(), demoEnd(), null));
        assertCode("INVALID_CANDLE_RANGE", () -> service.query(ID, "1m", demoEnd(), demoStart(), null));
        assertCode("INVALID_CANDLE_RANGE", () -> service.query(ID, "1m", demoStart(), demoStart().plusSeconds(604_801), null));
    }

    @Test void analysisHistoryUsesItsOwnBoundedWindowAndOmitsAnUnfinalizedCurrentBar() {
        when(instruments.findById(ID)).thenReturn(Optional.of(instrument));
        Instant to = Instant.parse("2026-10-22T10:00:00Z");
        Instant from = to.minusSeconds(350L * 24 * 60 * 60);
        when(candles.findByInstrumentIdAndTimeframeSecondsAndBucketStartGreaterThanEqualAndBucketStartLessThanAndSourceIdOrderByBucketStartAsc(
                ID, 60, from, to, "BROKER_FEED", PageRequest.of(0, 100_001))).thenReturn(List.of());
        when(gaps.findByInstrumentIdAndGapStartLessThanAndGapEndGreaterThanAndSourceIdOrderByGapStartAsc(
                ID, to, from, "BROKER_FEED", PageRequest.of(0, 1_001))).thenReturn(List.of());

        MarketCandleService.CandleSeries result = service.queryForAnalysis(ID, to, "BROKER_FEED");

        assertEquals(from, result.from());
        assertEquals(to, result.to());
        assertEquals("UNAVAILABLE", result.source());
        verify(aggregator, never()).current(ID);
        verify(candles).findByInstrumentIdAndTimeframeSecondsAndBucketStartGreaterThanEqualAndBucketStartLessThanAndSourceIdOrderByBucketStartAsc(
                ID, 60, from, to, "BROKER_FEED", PageRequest.of(0, 100_001));
    }

    @Test void candleQueryRejectsAnOverflowUsingOnlyTheBoundedDatabasePage() {
        when(instruments.findById(ID)).thenReturn(Optional.of(instrument));
        Instant from = demoStart();
        Instant to = demoEnd();
        when(candles.findByInstrumentIdAndTimeframeSecondsAndBucketStartGreaterThanEqualAndBucketStartLessThanOrderByBucketStartAsc(
                ID, 60, from, to, PageRequest.of(0, 10_081)))
                .thenReturn(java.util.Collections.nCopies(10_081, mock(MarketCandleEntity.class)));

        assertCode("CANDLE_LIMIT_EXCEEDED", () -> service.query(ID, "1m", from, to, null));
        verify(candles).findByInstrumentIdAndTimeframeSecondsAndBucketStartGreaterThanEqualAndBucketStartLessThanOrderByBucketStartAsc(
                ID, 60, from, to, PageRequest.of(0, 10_081));
    }

    @Test void gapListIsCappedAndClearlyMarkedWhenTheDatabasePageOverflows() {
        when(instruments.findById(ID)).thenReturn(Optional.of(instrument));
        Instant from = Instant.parse("2026-10-20T03:45:00Z");
        Instant to = from.plusSeconds(60);
        when(candles.findByInstrumentIdAndTimeframeSecondsAndBucketStartGreaterThanEqualAndBucketStartLessThanOrderByBucketStartAsc(
                ID, 60, from, to, PageRequest.of(0, 10_081))).thenReturn(List.of());
        when(gaps.findByInstrumentIdAndGapStartLessThanAndGapEndGreaterThanOrderByGapStartAsc(
                ID, to, from, PageRequest.of(0, 1_001)))
                .thenReturn(java.util.Collections.nCopies(1_001, mock(MarketDataGapEntity.class)));

        MarketCandleService.CandleSeries result = service.query(ID, "1m", from, to, null);

        assertEquals(1_000, result.gaps().size());
        assertTrue(result.warnings().contains("DATA_GAPS_PRESENT"));
        assertTrue(result.warnings().contains("DATA_GAP_LIST_TRUNCATED"));
    }

    @Test void analysisHistoryRejectsAnInstantTooEarlyToSubtractItsLookback() {
        assertCode("INVALID_CANDLE_RANGE", () -> service.queryForAnalysis(ID, Instant.MIN, "BROKER_FEED"));
    }

    private void assertCode(String code, Runnable request) {
        assertEquals(code, assertThrows(ApiException.class, request::run).code());
    }
    private Instant demoStart() {
        return SyntheticDemoHistoryProvider.DEMO_DATE.atTime(9, 15).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
    }
    private Instant demoEnd() { return demoStart().plusSeconds(120 * 60); }
}
