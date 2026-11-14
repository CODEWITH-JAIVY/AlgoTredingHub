package com.algotradehub.backend.marketdata;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Supported late-2025 and 2026 NSE/BSE equity and equity-derivatives demo sessions; not live status. */
@Component
public class NseBseEquityFnoDemoCalendar implements MarketSessionCalendar {
    private static final LocalDate SUPPORTED_START = LocalDate.of(2025, 11, 5);
    private static final LocalDate SUPPORTED_END = LocalDate.of(2026, 12, 31);
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalTime OPEN = LocalTime.of(9, 15);
    private static final LocalTime CLOSE = LocalTime.of(15, 30);
    private static final Set<LocalDate> FULL_DAY_HOLIDAYS = Set.of(
            LocalDate.of(2025, 2, 26),
            LocalDate.of(2025, 3, 14),
            LocalDate.of(2025, 3, 31),
            LocalDate.of(2025, 4, 10),
            LocalDate.of(2025, 4, 14),
            LocalDate.of(2025, 4, 18),
            LocalDate.of(2025, 5, 1),
            LocalDate.of(2025, 8, 15),
            LocalDate.of(2025, 8, 27),
            LocalDate.of(2025, 10, 2),
            LocalDate.of(2025, 10, 21),
            LocalDate.of(2025, 10, 22),
            LocalDate.of(2025, 11, 5),
            LocalDate.of(2025, 12, 25),
            LocalDate.of(2026, 1, 15),
            LocalDate.of(2026, 1, 26),
            LocalDate.of(2026, 3, 3),
            LocalDate.of(2026, 3, 26),
            LocalDate.of(2026, 3, 31),
            LocalDate.of(2026, 4, 3),
            LocalDate.of(2026, 4, 14),
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 5, 28),
            LocalDate.of(2026, 6, 26),
            LocalDate.of(2026, 9, 14),
            LocalDate.of(2026, 10, 2),
            LocalDate.of(2026, 10, 20),
            LocalDate.of(2026, 11, 10),
            LocalDate.of(2026, 11, 24),
            LocalDate.of(2026, 12, 25));

    @Override
    public boolean isCalendarDateSupported(LocalDate date) {
        return date != null && !date.isBefore(SUPPORTED_START) && !date.isAfter(SUPPORTED_END)
                && !date.equals(LocalDate.of(2026, 11, 8));
    }

    @Override
    public boolean isRegularTradingMinute(Instant instant) {
        var local = instant.atZone(EXCHANGE_ZONE);
        LocalDate date = local.toLocalDate();
        DayOfWeek day = local.getDayOfWeek();
        LocalTime time = local.toLocalTime();
        return isRegularTradingDate(date) && day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY
                && !time.isBefore(OPEN) && time.isBefore(CLOSE);
    }

    @Override
    public boolean isSameTradingDate(Instant first, Instant second) {
        return first.atZone(EXCHANGE_ZONE).toLocalDate().equals(second.atZone(EXCHANGE_ZONE).toLocalDate());
    }

    @Override
    public Optional<LocalDate> nextRegularTradingDate(LocalDate afterDate) {
        LocalDate candidate = afterDate.plusDays(1);
        while (!candidate.isAfter(SUPPORTED_END)) {
            if (isRegularTradingDate(candidate)) return Optional.of(candidate);
            candidate = candidate.plusDays(1);
        }
        return Optional.empty();
    }

    private boolean isRegularTradingDate(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return isCalendarDateSupported(date) && day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY
                && !FULL_DAY_HOLIDAYS.contains(date);
    }
}
