package com.algotradehub.backend.marketdata;

import com.algotradehub.backend.common.ApiException;
import com.algotradehub.backend.orders.Instrument;
import com.algotradehub.backend.orders.InstrumentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

@Component
public class MarketTickNormalizer {
    static final String DEMO_SOURCE = "SYNTHETIC_DEMO";
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Kolkata");
    private static final Duration MAX_AGE = Duration.ofMinutes(2);
    private static final Duration MAX_FUTURE_SKEW = Duration.ofSeconds(2);
    private final InstrumentRepository instruments;
    private final MarketSessionCalendar sessions;
    private final Clock clock;

    @Autowired
    public MarketTickNormalizer(InstrumentRepository instruments, MarketSessionCalendar sessions) {
        this(instruments, sessions, Clock.systemUTC());
    }

    MarketTickNormalizer(InstrumentRepository instruments, MarketSessionCalendar sessions, Clock clock) {
        this.instruments = instruments;
        this.sessions = sessions;
        this.clock = clock;
    }

    public NormalizedTick normalize(ProviderTick input) {
        if (input == null || input.sourceId() == null || input.exchange() == null || input.providerToken() == null
                || input.instrumentMasterVersion() == null || input.exchangeTime() == null || input.price() == null) {
            throw reject("TICK_FIELDS_REQUIRED", "A tick is missing required fields.");
        }
        if (!DEMO_SOURCE.equals(input.sourceId())) {
            throw reject("MARKET_SOURCE_DISABLED", "Only the synthetic demo source is enabled.");
        }
        if (input.sequenceNumber() < 0 || input.tradeQuantity() <= 0 || input.price().signum() <= 0) {
            throw reject("INVALID_TICK_VALUE", "Trade tick price and sequence must be valid and trade quantity must be positive.");
        }
        String exchange = input.exchange().trim().toUpperCase(Locale.ROOT);
        String token = input.providerToken().trim();
        Instrument instrument = instruments.findByExchangeAndProviderToken(exchange, token)
                .filter(Instrument::isActive)
                .orElseThrow(() -> reject("INSTRUMENT_MASTER_MISMATCH", "Tick instrument is missing, inactive, or no longer in the master."));
        if (!instrument.isSynthetic()) {
            throw reject("INSTRUMENT_SOURCE_MISMATCH", "Synthetic ticks cannot be applied to a non-synthetic instrument.");
        }
        if (!instrument.getMasterVersion().equals(input.instrumentMasterVersion())) {
            throw reject("STALE_INSTRUMENT_MASTER", "Tick was mapped using a different instrument-master version.");
        }
        if (input.price().remainder(instrument.getTickSize()).compareTo(BigDecimal.ZERO) != 0) {
            throw reject("INVALID_TICK_SIZE", "Tick price is not aligned to the current instrument tick size.");
        }
        Instant now = clock.instant();
        if (input.exchangeTime().isAfter(now.plus(MAX_FUTURE_SKEW))) {
            throw reject("TICK_FROM_FUTURE", "Tick timestamp is beyond the permitted clock-skew window.");
        }
        if (input.exchangeTime().isBefore(now.minus(MAX_AGE))) {
            throw reject("STALE_TICK", "Tick is too old for live aggregation; recover history through the historical-data path.");
        }
        LocalDate exchangeDate = input.exchangeTime().atZone(EXCHANGE_ZONE).toLocalDate();
        if (!sessions.isCalendarDateSupported(exchangeDate)) {
            throw reject(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_CALENDAR_UNAVAILABLE",
                    "The demo market calendar has no verified coverage for this date.");
        }
        if (!sessions.isRegularTradingMinute(input.exchangeTime())) {
            throw reject("OUTSIDE_SUPPORTED_SESSION", "Only supported regular NSE/BSE cash and F&O session minutes are accepted.");
        }
        return new NormalizedTick(instrument.getId(), input.price(), input.tradeQuantity(), input.exchangeTime(), now,
                input.sequenceNumber(), DEMO_SOURCE, true, instrument.getMasterVersion());
    }

    private ApiException reject(String code, String message) {
        return reject(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    private ApiException reject(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message);
    }
}
