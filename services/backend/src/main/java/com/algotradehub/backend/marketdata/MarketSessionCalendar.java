package com.algotradehub.backend.marketdata;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

public interface MarketSessionCalendar {
    boolean isCalendarDateSupported(LocalDate date);
    boolean isRegularTradingMinute(Instant instant);
    boolean isSameTradingDate(Instant first, Instant second);
    Optional<LocalDate> nextRegularTradingDate(LocalDate afterDate);
}
