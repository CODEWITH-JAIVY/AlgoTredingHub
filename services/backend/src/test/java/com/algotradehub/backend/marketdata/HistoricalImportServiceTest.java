package com.algotradehub.backend.marketdata;

import com.algotradehub.backend.common.ApiException;
import com.algotradehub.backend.orders.Instrument;
import com.algotradehub.backend.orders.InstrumentRepository;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoricalImportServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-20T04:30:00Z");
    private static final Instant FROM = NOW.minusSeconds(3600);
    private static final Instant TO = NOW;
    private static final UUID ID = UUID.fromString("7b774ec2-a516-4a29-b2b4-3945256c7c2a");
    private static final UUID JOB_ID = UUID.fromString("28d60cb3-a30f-4168-ae33-0b09b39ad6a0");
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final HistoryImportJobRepository jobs = mock(HistoryImportJobRepository.class);
    private final MarketCandleRepository candles = mock(MarketCandleRepository.class);
    private final MarketDataSourceRateLimitService sourceRateLimits = mock(MarketDataSourceRateLimitService.class);
    private final Instrument instrument = new Instrument(ID, "DEMO-NSE-001", "NSE", "CASH", "DEMO-ALPHA", "EQUITY", 1,
            new BigDecimal("0.05"), new BigDecimal("100.00"), "DEMO-1");

    @Test void sameRangeMapsToOneIdempotencyKeyAndNoProviderConfiguredFailsClosed() {
        HistoricalImportService absent = service(List.of());
        when(instruments.findById(ID)).thenReturn(Optional.of(instrument));
        assertEquals(HistoricalImportService.requestKey(ID, "TEST_SOURCE", FROM, TO),
                HistoricalImportService.requestKey(ID, "TEST_SOURCE", FROM, TO));
        assertCode("MARKET_SOURCE_NOT_CONFIGURED", () -> absent.start(ID, "TEST_SOURCE", FROM, TO));
        verify(jobs, never()).createIfAbsent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test void importRangeMustUseMinuteAlignedBoundaries() {
        assertCode("INVALID_IMPORT_RANGE", () -> service(List.of()).start(ID, "TEST_SOURCE", FROM.plusSeconds(1), TO));
        verify(instruments, never()).findById(ID);
    }

    @Test void providerPageRequiresAnExplicitCandleList() {
        assertThrows(IllegalArgumentException.class, () -> new HistoricalCandlePage(null, null, true, true));
    }

    @Test void repeatedRangeStartReusesTheSamePersistedJobKey() {
        TestSource source = new TestSource(true, Duration.ofSeconds(1));
        String key = HistoricalImportService.requestKey(ID, source.sourceId(), FROM, TO);
        HistoryImportJobEntity existing = new HistoryImportJobEntity(JOB_ID, key, ID, source.sourceId(), FROM, TO, NOW);
        when(instruments.findById(ID)).thenReturn(Optional.of(instrument));
        when(jobs.findByRequestKey(key)).thenReturn(Optional.of(existing));
        HistoricalImportService importer = service(List.of(source));
        var first = importer.start(ID, source.sourceId(), FROM, TO);
        var retry = importer.start(ID, source.sourceId(), FROM, TO);
        assertEquals(JOB_ID, first.id());
        assertEquals(first.id(), retry.id());
        verify(jobs, org.mockito.Mockito.times(2)).createIfAbsent(any(), eq(key), eq(ID), eq(source.sourceId()), eq(FROM), eq(TO), any());
    }

    @Test void retriesResumeAtSavedCursorAndOnlyMarkExhaustedPageComplete() {
        TestSource source = new TestSource(true, Duration.ofSeconds(1));
        MutableClock clock = new MutableClock(NOW);
        HistoryImportJobEntity job = new HistoryImportJobEntity(JOB_ID, "key", ID, source.sourceId(), FROM, TO, NOW);
        when(instruments.findById(ID)).thenReturn(Optional.of(instrument));
        when(jobs.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));
        when(jobs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(candles.existsByInstrumentIdAndTimeframeSecondsAndBucketStartAndSourceId(any(), anyInt(), any(), any())).thenReturn(false);
        source.addPage(new HistoricalCandlePage(List.of(bar(FROM, "TEST_SOURCE")), "cursor-1", false, false));
        source.addPage(new HistoricalCandlePage(List.of(bar(FROM.plusSeconds(60), "TEST_SOURCE")), null, true, true));

        HistoricalImportService importer = service(List.of(source), clock);
        HistoricalImportService.ImportJobView first = importer.importNextPage(JOB_ID);
        assertEquals("RUNNING", first.status());
        assertEquals(1, first.importedCount());
        assertEquals(true, first.hasCheckpoint());
        clock.advance(Duration.ofSeconds(2));
        HistoricalImportService.ImportJobView second = importer.importNextPage(JOB_ID);
        assertEquals("COMPLETE", second.status());
        assertEquals(2, second.importedCount());
        assertEquals(2, source.cursors.size());
        org.junit.jupiter.api.Assertions.assertNull(source.cursors.getFirst());
        assertEquals("cursor-1", source.cursors.getLast());
    }

    @Test void providerRateLimitAndUnconfirmedRightsBlockRequests() {
        TestSource throttled = new TestSource(true, Duration.ofSeconds(30));
        HistoryImportJobEntity job = new HistoryImportJobEntity(JOB_ID, "key", ID, throttled.sourceId(), FROM, TO, NOW.minusSeconds(10));
        job.markRequested(NOW.minusSeconds(5));
        when(jobs.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));
        assertCode("IMPORT_RATE_LIMITED", () -> service(List.of(throttled)).importNextPage(JOB_ID));
        assertEquals(0, throttled.fetchCount);

        TestSource unapproved = new TestSource(false, Duration.ofSeconds(1));
        when(instruments.findById(ID)).thenReturn(Optional.of(instrument));
        assertCode("MARKET_DATA_RIGHTS_UNCONFIRMED", () -> service(List.of(unapproved)).start(ID, "TEST_SOURCE", FROM, TO));
    }

    @Test void nonAdvancingCursorLeavesImportIncomplete() {
        TestSource source = new TestSource(true, Duration.ofSeconds(1));
        HistoryImportJobEntity job = new HistoryImportJobEntity(JOB_ID, "key", ID, source.sourceId(), FROM, TO, NOW);
        when(instruments.findById(ID)).thenReturn(Optional.of(instrument));
        when(jobs.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));
        when(jobs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        source.addPage(new HistoricalCandlePage(List.of(), null, false, false));
        HistoricalImportService.ImportJobView result = service(List.of(source)).importNextPage(JOB_ID);
        assertEquals("RUNNING", result.status());
        assertEquals("IMPORT_CURSOR_STALLED", result.lastErrorCode());
        assertEquals(0, result.importedCount());
    }

    @Test void sharedProviderRateLimitBlocksDifferentJobsBeforeCallingProvider() {
        TestSource source = new TestSource(true, Duration.ofSeconds(1));
        HistoryImportJobEntity job = new HistoryImportJobEntity(JOB_ID, "key", ID, source.sourceId(), FROM, TO, NOW);
        when(jobs.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));
        HistoricalImportService importer = service(List.of(source));
        when(sourceRateLimits.tryReserve(eq(source.sourceId()), eq(NOW), eq(Duration.ofSeconds(1)))).thenReturn(false);

        assertCode("IMPORT_RATE_LIMITED", () -> importer.importNextPage(JOB_ID));
        assertEquals(0, source.fetchCount);
        verify(sourceRateLimits).tryReserve(eq(source.sourceId()), eq(NOW), eq(Duration.ofSeconds(1)));
    }

    @Test void malformedOhlcAndTickSizeAreRejectedWithoutSaving() {
        TestSource source = new TestSource(true, Duration.ofSeconds(1));
        HistoryImportJobEntity job = new HistoryImportJobEntity(JOB_ID, "key", ID, source.sourceId(), FROM, TO, NOW);
        when(instruments.findById(ID)).thenReturn(Optional.of(instrument));
        when(jobs.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));
        when(jobs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        CandleBar malformed = new CandleBar(ID, 60, FROM, new BigDecimal("100.00"), new BigDecimal("100.05"),
                new BigDecimal("99.95"), new BigDecimal("100.03"), 100, 10, source.sourceId(), "PROVIDER", "DEMO-1", true, NOW);
        source.addPage(new HistoricalCandlePage(List.of(malformed), null, true, true));

        var result = service(List.of(source)).importNextPage(JOB_ID);
        assertEquals("INVALID_PROVIDER_CANDLE_VALUES", result.lastErrorCode());
        verify(candles, never()).save(any());
    }

    @Test void exhaustedProviderPagesWithoutFullRangeConfirmationStayIncomplete() {
        TestSource source = new TestSource(true, Duration.ofSeconds(1));
        HistoryImportJobEntity job = new HistoryImportJobEntity(JOB_ID, "key", ID, source.sourceId(), FROM, TO, NOW);
        when(instruments.findById(ID)).thenReturn(Optional.of(instrument));
        when(jobs.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));
        when(jobs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        source.addPage(new HistoricalCandlePage(List.of(bar(FROM, "TEST_SOURCE")), null, true, false));

        var result = service(List.of(source)).importNextPage(JOB_ID);

        assertEquals("RUNNING", result.status());
        assertEquals("INCOMPLETE_PROVIDER_RANGE", result.lastErrorCode());
        assertEquals(0, result.importedCount());
        verify(candles, never()).save(any());
    }

    @Test void providerCannotConfirmCompletenessWhilePagesRemain() {
        TestSource source = new TestSource(true, Duration.ofSeconds(1));
        HistoryImportJobEntity job = new HistoryImportJobEntity(JOB_ID, "key", ID, source.sourceId(), FROM, TO, NOW);
        when(instruments.findById(ID)).thenReturn(Optional.of(instrument));
        when(jobs.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));
        when(jobs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        source.addPage(new HistoricalCandlePage(List.of(bar(FROM, "TEST_SOURCE")), "cursor-1", false, true));

        var result = service(List.of(source)).importNextPage(JOB_ID);

        assertEquals("RUNNING", result.status());
        assertEquals("INVALID_PROVIDER_COMPLETENESS", result.lastErrorCode());
        assertEquals(0, result.importedCount());
        verify(candles, never()).save(any());
    }

    private HistoricalImportService service(List<HistoricalCandleSource> sources) {
        return service(sources, Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private HistoricalImportService service(List<HistoricalCandleSource> sources, Clock clock) {
        when(sourceRateLimits.tryReserve(any(), any(), any())).thenReturn(true);
        return new HistoricalImportService(instruments, jobs, candles, sourceRateLimits, sources, clock);
    }
    private CandleBar bar(Instant time, String source) {
        return new CandleBar(ID, 60, time, new BigDecimal("100.00"), new BigDecimal("100.10"),
                new BigDecimal("99.95"), new BigDecimal("100.05"), 100, 10, source, "PROVIDER", "DEMO-1", true, NOW);
    }
    private void assertCode(String code, Runnable action) {
        assertEquals(code, assertThrows(ApiException.class, action::run).code());
    }

    private static final class TestSource implements HistoricalCandleSource {
        private final boolean rights;
        private final Duration interval;
        private final java.util.ArrayDeque<HistoricalCandlePage> pages = new java.util.ArrayDeque<>();
        private final java.util.ArrayList<String> cursors = new java.util.ArrayList<>();
        private int fetchCount;
        private TestSource(boolean rights, Duration interval) { this.rights = rights; this.interval = interval; }
        @Override public String sourceId() { return "TEST_SOURCE"; }
        @Override public boolean enabled() { return true; }
        @Override public MarketDataRights rights() { return rights ? new MarketDataRights(true, true, true, true, false) : MarketDataRights.unconfirmed(); }
        @Override public Duration minimumRequestInterval() { return interval; }
        @Override public int maxPageSize() { return 1000; }
        private void addPage(HistoricalCandlePage page) { pages.add(page); }
        @Override public HistoricalCandlePage fetchPage(UUID instrumentId, String masterVersion, Instant from, Instant to, String cursor, int pageSize) {
            fetchCount++;
            cursors.add(cursor);
            assertEquals(500, pageSize);
            return pages.removeFirst();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        private void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
