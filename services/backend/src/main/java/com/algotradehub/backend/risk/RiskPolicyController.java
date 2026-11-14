package com.algotradehub.backend.risk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import com.algotradehub.backend.marketdata.MarketSessionCalendar;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.LocalTime;
import com.algotradehub.backend.common.ApiException;
import org.springframework.http.HttpStatus;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/risk/policy")
public class RiskPolicyController {
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalTime REGULAR_OPEN = LocalTime.of(9, 15);
    private final RiskPolicyRepository policies;
    private final MarketSessionCalendar sessions;
    private final DailyRiskStateRepository dailyRiskStates;
    private final Clock clock;
    public RiskPolicyController(RiskPolicyRepository policies, MarketSessionCalendar sessions,
                                DailyRiskStateRepository dailyRiskStates, Clock clock) {
        this.policies = policies;
        this.sessions = sessions;
        this.dailyRiskStates = dailyRiskStates;
        this.clock = clock;
    }
    @GetMapping @Transactional public PolicyView get(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = userId(jwt);
        LocalDate today = LocalDate.now(clock.withZone(EXCHANGE_ZONE));
        RiskPolicy current = policies.findByUserIdForUpdate(userId).orElseThrow();
        if (sessions.isCalendarDateSupported(today) && current.applyPendingLimits(today)) policies.save(current);
        return PolicyView.from(current, todayEntryCount(userId, today));
    }
    @PutMapping @Transactional public PolicyView update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PolicyUpdate body) {
        LocalDate today = LocalDate.now(clock.withZone(EXCHANGE_ZONE));
        if (body.noNewOrdersAfter() == null || body.maxOrdersPerDay() == null || body.noOrdersFirstMinutes() == null
                || body.noNewOrdersAfter().isAfter(LocalTime.of(15, 30))
                || body.noNewOrdersAfter().getSecond() != 0 || body.noNewOrdersAfter().getNano() != 0
                || body.noOrdersFirstMinutes() < 0 || body.noOrdersFirstMinutes() > 120
                || !body.noNewOrdersAfter().isAfter(REGULAR_OPEN.plusMinutes(body.noOrdersFirstMinutes()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TRADING_WINDOW", "Use minute precision, keep the cutoff after the opening delay, and set it no later than 15:30 IST.");
        }
        RiskPolicy current = policies.findByUserIdForUpdate(userId(jwt)).orElseThrow();
        LocalDate effectiveDate = sessions.nextRegularTradingDate(today)
                .orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_CALENDAR_UNAVAILABLE",
                        "No verified next market session is available in the demo calendar."));
        current.update(body.maxQuantity(), body.maxOrderNotional(), body.maxOpenOrders(), body.maxDailyLoss(),
                body.maxTradeLoss(), body.maxOrdersPerDay(), body.noNewOrdersAfter(), body.noOrdersFirstMinutes(),
                today, effectiveDate);
        return PolicyView.from(policies.save(current), todayEntryCount(userId(jwt), today));
    }
    private int todayEntryCount(UUID userId, LocalDate today) {
        return dailyRiskStates.findById(userId)
                .filter(state -> state.getSessionDate().equals(today))
                .map(DailyRiskState::getAcceptedOrderEntries)
                .orElse(0);
    }
    private UUID userId(Jwt jwt) { return UUID.fromString(jwt.getClaimAsString("uid")); }
    public record PolicyUpdate(@Positive @Max(10000) long maxQuantity,
                               @NotNull @DecimalMin(value = "1.00") @Digits(integer = 16, fraction = 2) BigDecimal maxOrderNotional,
                               @Positive @Max(50) int maxOpenOrders,
                               @NotNull @DecimalMin(value = "1.00") @Digits(integer = 16, fraction = 2) BigDecimal maxDailyLoss,
                               @NotNull @DecimalMin(value = "1.00") @Digits(integer = 16, fraction = 2) BigDecimal maxTradeLoss,
                               @NotNull @Positive @Max(100) Integer maxOrdersPerDay,
                               @NotNull LocalTime noNewOrdersAfter,
                               @NotNull @Min(0) @Max(120) Integer noOrdersFirstMinutes) { }
    public record PolicyView(long maxQuantity, BigDecimal maxOrderNotional, int maxOpenOrders,
                             BigDecimal maxDailyLoss, BigDecimal maxTradeLoss, int maxOrdersPerDay,
                             LocalTime noNewOrdersAfter, int noOrdersFirstMinutes,
                             int acceptedOrderEntriesToday, RiskPolicy.PendingLimits pending, Instant updatedAt) {
        static PolicyView from(RiskPolicy p, int acceptedOrderEntriesToday) {
            return new PolicyView(p.getMaxQuantity(), p.getMaxOrderNotional(), p.getMaxOpenOrders(),
                    p.getMaxDailyLoss(), p.getMaxTradeLoss(), p.getMaxOrdersPerDay(), p.getNoNewOrdersAfter(),
                    p.getNoOrdersFirstMinutes(), acceptedOrderEntriesToday, p.getPendingLimits(), p.getUpdatedAt());
        }
    }
}
