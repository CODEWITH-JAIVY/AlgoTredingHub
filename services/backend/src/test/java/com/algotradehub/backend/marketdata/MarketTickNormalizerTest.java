package com.algotradehub.backend.marketdata;

import com.algotradehub.backend.common.ApiException;
import com.algotradehub.backend.orders.Instrument;
import com.algotradehub.backend.orders.InstrumentRepository;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketTickNormalizerTest {
    private static final Instant NOW = Instant.parse("2026-10-21T04:30:00Z");
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final UUID id = UUID.fromString("7b774ec2-a516-4a29-b2b4-3945256c7c2a");
    private final Instrument instrument = new Instrument(id, "DEMO-NSE-001", "NSE", "CASH", "DEMO-ALPHA", "EQUITY", 1,
            new BigDecimal("0.05"), new BigDecimal("100.00"), "DEMO-1");
    private final MarketTickNormalizer normalizer = new MarketTickNormalizer(instruments, new NseBseEquityFnoDemoCalendar(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void acceptsValidTickAndCarriesMasterVersion() {
        when(instruments.findByExchangeAndProviderToken("NSE", "DEMO-NSE-001")).thenReturn(Optional.of(instrument));
        NormalizedTick result = normalizer.normalize(tick("SYNTHETIC_DEMO", "DEMO-1", "100.05", NOW.minusSeconds(10), 2));
        assertEquals(id, result.instrumentId());
        assertEquals("DEMO-1", result.masterVersion());
        assertEquals(NOW, result.receivedAt());
    }

    @Test void rejectsSourceTickSizeMasterAgeClockAndSessionErrors() {
        assertCode("MARKET_SOURCE_DISABLED", tick("UNAPPROVED", "DEMO-1", "100.00", NOW.minusSeconds(1), 1));
        when(instruments.findByExchangeAndProviderToken("NSE", "DEMO-NSE-001")).thenReturn(Optional.of(instrument));
        assertCode("INVALID_TICK_SIZE", tick("SYNTHETIC_DEMO", "DEMO-1", "100.02", NOW.minusSeconds(1), 1));
        assertCode("STALE_INSTRUMENT_MASTER", tick("SYNTHETIC_DEMO", "DEMO-0", "100.00", NOW.minusSeconds(1), 1));
        assertCode("STALE_TICK", tick("SYNTHETIC_DEMO", "DEMO-1", "100.00", NOW.minusSeconds(121), 1));
        assertCode("TICK_FROM_FUTURE", tick("SYNTHETIC_DEMO", "DEMO-1", "100.00", NOW.plusSeconds(3), 1));
        Instant preOpenNow = Instant.parse("2026-10-21T03:00:00Z");
        MarketTickNormalizer preOpen = new MarketTickNormalizer(instruments, new NseBseEquityFnoDemoCalendar(), Clock.fixed(preOpenNow, ZoneOffset.UTC));
        assertEquals("OUTSIDE_SUPPORTED_SESSION", assertThrows(ApiException.class,
                () -> preOpen.normalize(tick("SYNTHETIC_DEMO", "DEMO-1", "100.00", preOpenNow, 1))).code());
        Instant weekendNow = Instant.parse("2026-10-24T04:30:00Z");
        MarketTickNormalizer weekend = new MarketTickNormalizer(instruments, new NseBseEquityFnoDemoCalendar(), Clock.fixed(weekendNow, ZoneOffset.UTC));
        assertEquals("OUTSIDE_SUPPORTED_SESSION", assertThrows(ApiException.class,
                () -> weekend.normalize(tick("SYNTHETIC_DEMO", "DEMO-1", "100.00", weekendNow, 1))).code());
    }

    @Test void rejectsExchangeHolidayAndFailsClosedOutsideCalendarCoverage() {
        when(instruments.findByExchangeAndProviderToken("NSE", "DEMO-NSE-001")).thenReturn(Optional.of(instrument));
        Instant holiday = Instant.parse("2026-10-20T04:30:00Z");
        MarketTickNormalizer holidayNormalizer = new MarketTickNormalizer(instruments,
                new NseBseEquityFnoDemoCalendar(), Clock.fixed(holiday, ZoneOffset.UTC));
        assertEquals("OUTSIDE_SUPPORTED_SESSION", assertThrows(ApiException.class,
                () -> holidayNormalizer.normalize(tick("SYNTHETIC_DEMO", "DEMO-1", "100.00", holiday, 1))).code());

        Instant unsupported = Instant.parse("2027-01-04T04:30:00Z");
        MarketTickNormalizer unsupportedNormalizer = new MarketTickNormalizer(instruments,
                new NseBseEquityFnoDemoCalendar(), Clock.fixed(unsupported, ZoneOffset.UTC));
        ApiException error = assertThrows(ApiException.class,
                () -> unsupportedNormalizer.normalize(tick("SYNTHETIC_DEMO", "DEMO-1", "100.00", unsupported, 1)));
        assertEquals("MARKET_CALENDAR_UNAVAILABLE", error.code());
    }

    private void assertCode(String code, ProviderTick input) {
        assertEquals(code, assertThrows(ApiException.class, () -> normalizer.normalize(input)).code());
    }

    private ProviderTick tick(String source, String version, String price, Instant time, long quantity) {
        return new ProviderTick(source, "NSE", "DEMO-NSE-001", version, new BigDecimal(price), quantity, time, 7);
    }
}
