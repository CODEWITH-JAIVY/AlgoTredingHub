package com.algotradehub.backend.marketdata;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NseBseEquityFnoDemoCalendarTest {
    private final NseBseEquityFnoDemoCalendar calendar = new NseBseEquityFnoDemoCalendar();

    @Test void recognizesSupportedYearAndFailsClosedForUnknownYears() {
        assertTrue(calendar.isCalendarDateSupported(LocalDate.of(2026, 1, 1)));
        assertTrue(calendar.isCalendarDateSupported(LocalDate.of(2025, 11, 5)));
        assertTrue(calendar.isCalendarDateSupported(LocalDate.of(2025, 11, 6)));
        assertTrue(calendar.isCalendarDateSupported(LocalDate.of(2025, 12, 31)));
        assertFalse(calendar.isCalendarDateSupported(LocalDate.of(2025, 11, 4)));
        assertFalse(calendar.isCalendarDateSupported(LocalDate.of(2026, 11, 8)));
        assertFalse(calendar.isCalendarDateSupported(LocalDate.of(2027, 1, 1)));
    }

    @Test void excludesWeekendsAndVerifiedFullDayExchangeHolidays() {
        assertFalse(calendar.isRegularTradingMinute(Instant.parse("2026-10-24T04:30:00Z")));
        assertFalse(calendar.isRegularTradingMinute(Instant.parse("2026-10-20T04:30:00Z")));
        assertFalse(calendar.isRegularTradingMinute(Instant.parse("2026-01-15T04:30:00Z")));
        assertFalse(calendar.isRegularTradingMinute(Instant.parse("2025-11-05T04:30:00Z")));
        assertTrue(calendar.isRegularTradingMinute(Instant.parse("2026-10-21T04:30:00Z")));
        assertTrue(calendar.isRegularTradingMinute(Instant.parse("2025-11-06T04:30:00Z")));
        assertFalse(calendar.isRegularTradingMinute(Instant.parse("2025-12-25T04:30:00Z")));
    }

    @Test void nextSessionSkipsWeekendAndHolidaysAndStopsAtCoverageBoundary() {
        assertEquals(Optional.of(LocalDate.of(2026, 10, 21)),
                calendar.nextRegularTradingDate(LocalDate.of(2026, 10, 20)));
        assertEquals(Optional.of(LocalDate.of(2026, 10, 26)),
                calendar.nextRegularTradingDate(LocalDate.of(2026, 10, 23)));
        assertEquals(Optional.empty(), calendar.nextRegularTradingDate(LocalDate.of(2026, 12, 31)));
        assertEquals(Optional.of(LocalDate.of(2025, 12, 26)),
                calendar.nextRegularTradingDate(LocalDate.of(2025, 12, 24)));
    }
}
