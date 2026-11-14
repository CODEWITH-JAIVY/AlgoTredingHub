package com.algotradehub.backend.risk;

import com.algotradehub.backend.common.ApiException;
import com.algotradehub.backend.orders.Instrument;
import com.algotradehub.backend.orders.OrderRepository;
import com.algotradehub.backend.orders.OrderRequest;
import com.algotradehub.backend.orders.OrderStatus;
import com.algotradehub.backend.orders.OrderType;
import com.algotradehub.backend.marketdata.MarketSessionCalendar;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Clock;
import java.time.LocalTime;
import java.time.Instant;

@Service
public class RiskService {
    private static final List<String> OPEN = List.of(OrderStatus.OPEN.name(), OrderStatus.PARTIALLY_FILLED.name(), OrderStatus.UNKNOWN.name());
    private final RiskPolicyRepository policies;
    private final AccountControlRepository controls;
    private final OrderRepository orders;
    private final DailyRiskStateRepository dailyRiskStates;
    private final MarketSessionCalendar sessions;
    private final Clock clock;
    private static final LocalTime REGULAR_OPEN = LocalTime.of(9, 15);
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Kolkata");
    @Autowired
    public RiskService(RiskPolicyRepository policies, AccountControlRepository controls, OrderRepository orders,
                       DailyRiskStateRepository dailyRiskStates, MarketSessionCalendar sessions, Clock clock) {
        this.policies = policies;
        this.controls = controls;
        this.orders = orders;
        this.dailyRiskStates = dailyRiskStates;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional
    public void lockAccountForOrder(UUID userId) {
        RiskPolicy policy = policies.findByUserIdForUpdate(userId)
                .orElseThrow(() -> reject("RISK_POLICY_MISSING", "Risk policy is unavailable."));
        LocalDate sessionDate = LocalDate.now(clock.withZone(EXCHANGE_ZONE));
        requireCalendarCoverage(sessionDate);
        if (policy.applyPendingLimits(sessionDate)) policies.save(policy);
    }

    @Transactional
    public RiskAssessment assess(UUID userId, Instrument instrument, OrderRequest request) {
        AccountControl control = controls.findById(userId)
                .orElseThrow(() -> reject("ACCOUNT_CONTROL_MISSING", "Account risk controls are unavailable."));
        if (control.isKillSwitchActive()) throw reject("KILL_SWITCH_ACTIVE", "The account kill switch is active.");
        if (!instrument.isActive()) throw reject("INSTRUMENT_INACTIVE", "This instrument is not active.");
        if (!instrument.isSynthetic()) throw reject("LIVE_INSTRUMENT_DISABLED", "Only synthetic demo instruments are enabled in Phase 1.");
        RiskPolicy policy = policies.findByUserIdForUpdate(userId)
                .orElseThrow(() -> reject("RISK_POLICY_MISSING", "Risk policy is unavailable."));
        Instant now = clock.instant();
        LocalDate sessionDate = now.atZone(EXCHANGE_ZONE).toLocalDate();
        requireCalendarCoverage(sessionDate);
        if (policy.applyPendingLimits(sessionDate)) policies.save(policy);
        if (!sessions.isRegularTradingMinute(now)) {
            throw reject("OUTSIDE_TRADING_SESSION", "New orders are allowed only during the configured regular session.");
        }
        LocalTime sessionTime = now.atZone(EXCHANGE_ZONE).toLocalTime();
        if (sessionTime.isBefore(REGULAR_OPEN.plusMinutes(policy.getNoOrdersFirstMinutes()))) {
            throw reject("OPENING_COOLDOWN", "New orders are blocked during the configured opening cooldown.");
        }
        if (!sessionTime.isBefore(policy.getNoNewOrdersAfter())) {
            throw reject("ORDER_ENTRY_CUTOFF", "The daily cutoff for new orders has passed.");
        }
        if (request.quantity() > policy.getMaxQuantity()) throw reject("MAX_QUANTITY", "Order quantity exceeds your risk limit.");
        if (request.quantity() % instrument.getLotSize() != 0) throw reject("INVALID_LOT_SIZE", "Quantity must be a whole multiple of the instrument lot size.");
        if ((request.orderType() == OrderType.MARKET || request.orderType() == OrderType.STOP_MARKET)
                && request.limitPrice() != null) {
            throw reject("UNEXPECTED_LIMIT_PRICE", "This order type does not accept a limit price.");
        }
        if ((request.orderType() == OrderType.MARKET || request.orderType() == OrderType.LIMIT)
                && request.triggerPrice() != null) {
            throw reject("UNEXPECTED_TRIGGER", "This order type does not accept a trigger price.");
        }
        if (request.orderType() == OrderType.LIMIT && request.limitPrice() == null) {
            throw reject("LIMIT_PRICE_REQUIRED", "Limit orders require a limit price.");
        }
        if (request.orderType() == OrderType.STOP_LIMIT
                && (request.limitPrice() == null || request.triggerPrice() == null)) {
            throw reject("STOP_FIELDS_REQUIRED", "Stop-limit orders require a trigger and a limit price.");
        }
        if (request.orderType() == OrderType.STOP_MARKET && request.triggerPrice() == null) {
            throw reject("TRIGGER_REQUIRED", "Stop-market orders require a trigger price.");
        }
        BigDecimal referencePrice = switch (request.orderType()) {
            case MARKET -> instrument.getMockPrice();
            case STOP_MARKET -> request.triggerPrice();
            case LIMIT, STOP_LIMIT -> request.limitPrice();
        };
        if (referencePrice == null || referencePrice.signum() <= 0) {
            throw reject("PRICE_REQUIRED", "A valid price is required for this order type.");
        }
        validateTick(request.limitPrice(), instrument, "limit price");
        validateTick(request.triggerPrice(), instrument, "trigger price");
        validateTick(request.protectiveStopPrice(), instrument, "protective stop");
        BigDecimal entryReference = referencePrice;
        if (request.triggerPrice() != null) {
            entryReference = request.triggerPrice();
        }
        if (request.side().name().equals("BUY") && request.protectiveStopPrice().compareTo(entryReference) >= 0) {
            throw reject("INVALID_PROTECTIVE_STOP", "For a buy order, protective stop must be below the reference price.");
        }
        if (request.side().name().equals("SELL") && request.protectiveStopPrice().compareTo(entryReference) <= 0) {
            throw reject("INVALID_PROTECTIVE_STOP", "For a sell order, protective stop must be above the reference price.");
        }
        BigDecimal notional = referencePrice.multiply(BigDecimal.valueOf(request.quantity())).setScale(2, RoundingMode.HALF_UP);
        if (notional.compareTo(policy.getMaxOrderNotional()) > 0) throw reject("MAX_ORDER_NOTIONAL", "Estimated order value exceeds your risk limit.");
        if (request.triggerPrice() != null && request.side().name().equals("BUY") && request.triggerPrice().compareTo(instrument.getMockPrice()) <= 0) {
            throw reject("INVALID_TRIGGER", "Buy stop trigger must be above the current synthetic price.");
        }
        if (request.triggerPrice() != null && request.side().name().equals("SELL") && request.triggerPrice().compareTo(instrument.getMockPrice()) >= 0) {
            throw reject("INVALID_TRIGGER", "Sell stop trigger must be below the current synthetic price.");
        }
        BigDecimal stopRisk = entryReference.subtract(request.protectiveStopPrice()).abs()
                .multiply(BigDecimal.valueOf(request.quantity())).setScale(2, RoundingMode.HALF_UP);
        if (stopRisk.compareTo(policy.getMaxTradeLoss()) > 0) throw reject("MAX_TRADE_LOSS", "Estimated stop-loss exposure exceeds your per-trade risk limit.");
        DailyRiskState state = dailyRiskStates.findByUserIdForUpdate(userId).orElseThrow(() -> reject("DAILY_RISK_STATE_MISSING", "Daily risk state is unavailable."));
        if (state.getSessionDate().isAfter(sessionDate)) {
            throw reject("DAILY_RISK_STATE_INVALID", "Daily risk state date is ahead of the current trading date.");
        }
        if (state.getSessionDate().isBefore(sessionDate)) {
            state.reset(sessionDate);
            dailyRiskStates.save(state);
        }
        if (state.getAcceptedOrderEntries() >= policy.getMaxOrdersPerDay()) {
            throw reject("MAX_DAILY_ORDER_ENTRIES", "The maximum accepted order entries for today has been reached.");
        }
        BigDecimal reservedRisk = orders.sumEstimatedRisk(userId, OPEN);
        if (reservedRisk == null) reservedRisk = BigDecimal.ZERO;
        if (state.getRealizedLoss().add(reservedRisk).add(stopRisk).compareTo(policy.getMaxDailyLoss()) > 0) {
            throw reject("MAX_DAILY_LOSS", "Open risk plus recorded daily loss would exceed the daily limit.");
        }
        if (orders.countByUserIdAndStatusIn(userId, OPEN) >= policy.getMaxOpenOrders()) {
            throw reject("MAX_OPEN_ORDERS", "Maximum open-order count reached.");
        }
        return new RiskAssessment(referencePrice, notional, stopRisk, sessionDate);
    }

    @Transactional
    public void recordAcceptedOrderEntry(UUID userId, LocalDate assessedSessionDate) {
        DailyRiskState state = dailyRiskStates.findByUserIdForUpdate(userId)
                .orElseThrow(() -> reject("DAILY_RISK_STATE_MISSING", "Daily risk state is unavailable."));
        if (!state.getSessionDate().equals(assessedSessionDate)) {
            throw reject("DAILY_RISK_STATE_CHANGED", "The daily risk state changed during order submission.");
        }
        state.recordAcceptedOrderEntry();
        dailyRiskStates.save(state);
    }

    private void validateTick(BigDecimal value, Instrument instrument, String label) {
        if (value != null && value.remainder(instrument.getTickSize()).compareTo(BigDecimal.ZERO) != 0) {
            throw reject("INVALID_TICK_SIZE", "The " + label + " is not aligned to the instrument tick size.");
        }
    }
    private void requireCalendarCoverage(LocalDate sessionDate) {
        if (!sessions.isCalendarDateSupported(sessionDate)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_CALENDAR_UNAVAILABLE",
                    "The demo market calendar has no verified coverage for the current date.");
        }
    }

    private ApiException reject(String code, String message) { return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message); }
    public record RiskAssessment(BigDecimal referencePrice, BigDecimal estimatedNotional,
                                 BigDecimal estimatedStopRisk, LocalDate sessionDate) { }
}
