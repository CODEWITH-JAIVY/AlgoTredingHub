package com.algotradehub.backend.risk;

import com.algotradehub.backend.common.ApiException;
import com.algotradehub.backend.orders.Instrument;
import com.algotradehub.backend.orders.OrderRepository;
import com.algotradehub.backend.orders.OrderRequest;
import com.algotradehub.backend.orders.OrderSide;
import com.algotradehub.backend.orders.OrderType;
import com.algotradehub.backend.marketdata.MarketSessionCalendar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentMatchers;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskServiceTest {
    private static final UUID USER = UUID.fromString("77a20bdd-d2e4-46d7-93a7-72a0ce11a00e");
    @Mock private RiskPolicyRepository policies;
    @Mock private AccountControlRepository controls;
    @Mock private OrderRepository orders;
    @Mock private DailyRiskStateRepository dailyRiskStates;
    @Mock private MarketSessionCalendar sessions;
    private RiskService service;
    private Instrument instrument;

    @BeforeEach void setUp() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        Instant testTime = today.atTime(10, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
        org.mockito.Mockito.lenient().when(sessions.isRegularTradingMinute(ArgumentMatchers.any())).thenReturn(true);
        org.mockito.Mockito.lenient().when(sessions.isCalendarDateSupported(ArgumentMatchers.any(LocalDate.class))).thenReturn(true);
        service = new RiskService(policies, controls, orders, dailyRiskStates, sessions, Clock.fixed(testTime, ZoneOffset.UTC));
        instrument = new Instrument(UUID.randomUUID(), "DEMO-NIFTY", "NSE", "FNO", "DEMO-NIFTY-FUT", "FUTURE",
                25, new BigDecimal("0.05"), new BigDecimal("1000.00"), "DEMO-1");
        org.mockito.Mockito.lenient().when(controls.findById(USER)).thenReturn(Optional.of(new AccountControl(USER)));
        org.mockito.Mockito.lenient().when(policies.findByUserIdForUpdate(USER)).thenReturn(Optional.of(new RiskPolicy(USER)));
        org.mockito.Mockito.lenient().when(dailyRiskStates.findByUserIdForUpdate(USER)).thenReturn(Optional.of(new DailyRiskState(USER, today)));
        org.mockito.Mockito.lenient().when(orders.sumEstimatedRisk(org.mockito.ArgumentMatchers.eq(USER), anyCollection())).thenReturn(BigDecimal.ZERO);
        org.mockito.Mockito.lenient().when(orders.countByUserIdAndStatusIn(org.mockito.ArgumentMatchers.eq(USER), anyCollection())).thenReturn(0L);
    }

    @Test void acceptsOrderWithinAllRiskLimits() {
        var result = service.assess(USER, instrument, request(25, "990.00"));
        assertEquals(0, result.estimatedNotional().compareTo(new BigDecimal("25000.00")));
        assertEquals(0, result.estimatedStopRisk().compareTo(new BigDecimal("250.00")));
    }

    @Test void rejectsOrdersWhenCalendarCoverageIsUnavailable() {
        org.mockito.Mockito.lenient().when(sessions.isCalendarDateSupported(ArgumentMatchers.any(LocalDate.class))).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.assess(USER, instrument, request(25, "990.00")));

        assertEquals("MARKET_CALENDAR_UNAVAILABLE", ex.code());
    }

    @Test void rejectsQuantityThatIsNotAWholeLot() {
        ApiException ex = assertThrows(ApiException.class, () -> service.assess(USER, instrument, request(24, "990.00")));
        assertEquals("INVALID_LOT_SIZE", ex.code());
    }

    @Test void rejectsPriceThatDoesNotMatchTickSize() {
        OrderRequest invalid = new OrderRequest("client-order-456", instrument.getId(), OrderSide.BUY, OrderType.MARKET, 25, null, null, new BigDecimal("990.02"));
        ApiException ex = assertThrows(ApiException.class, () -> service.assess(USER, instrument, invalid));
        assertEquals("INVALID_TICK_SIZE", ex.code());
    }

    @Test void rejectsLimitOrderWithoutLimitPriceUsingSpecificCode() {
        OrderRequest invalid = orderRequest(OrderType.LIMIT, null, null);
        ApiException ex = assertThrows(ApiException.class, () -> service.assess(USER, instrument, invalid));
        assertEquals("LIMIT_PRICE_REQUIRED", ex.code());
    }

    @Test void rejectsStopLimitWhenEitherRequiredPriceIsMissing() {
        OrderRequest missingLimit = orderRequest(OrderType.STOP_LIMIT, null, new BigDecimal("1010.00"));
        OrderRequest missingTrigger = orderRequest(OrderType.STOP_LIMIT, new BigDecimal("1010.00"), null);

        ApiException missingLimitError = assertThrows(ApiException.class,
                () -> service.assess(USER, instrument, missingLimit));
        ApiException missingTriggerError = assertThrows(ApiException.class,
                () -> service.assess(USER, instrument, missingTrigger));

        assertEquals("STOP_FIELDS_REQUIRED", missingLimitError.code());
        assertEquals("STOP_FIELDS_REQUIRED", missingTriggerError.code());
    }

    @Test void rejectsStopMarketWithoutTriggerPriceUsingSpecificCode() {
        OrderRequest invalid = orderRequest(OrderType.STOP_MARKET, null, null);
        ApiException ex = assertThrows(ApiException.class, () -> service.assess(USER, instrument, invalid));
        assertEquals("TRIGGER_REQUIRED", ex.code());
    }

    @Test void rejectsUnexpectedMarketOrderPricesBeforeReferencePriceValidation() {
        OrderRequest unexpectedLimit = orderRequest(OrderType.MARKET, new BigDecimal("1000.00"), null);
        OrderRequest unexpectedTrigger = orderRequest(OrderType.MARKET, null, new BigDecimal("1010.00"));

        ApiException limitError = assertThrows(ApiException.class,
                () -> service.assess(USER, instrument, unexpectedLimit));
        ApiException triggerError = assertThrows(ApiException.class,
                () -> service.assess(USER, instrument, unexpectedTrigger));

        assertEquals("UNEXPECTED_LIMIT_PRICE", limitError.code());
        assertEquals("UNEXPECTED_TRIGGER", triggerError.code());
    }

    @Test void rejectsStopRiskAbovePerTradeLimit() {
        ApiException ex = assertThrows(ApiException.class, () -> service.assess(USER, instrument, request(25, "949.95")));
        assertEquals("MAX_TRADE_LOSS", ex.code());
    }

    @Test void rejectsWhenOpenRiskWouldExceedDailyLimit() {
        org.mockito.Mockito.lenient().when(orders.sumEstimatedRisk(org.mockito.ArgumentMatchers.eq(USER), anyCollection())).thenReturn(new BigDecimal("4800.00"));
        ApiException ex = assertThrows(ApiException.class, () -> service.assess(USER, instrument, request(25, "990.00")));
        assertEquals("MAX_DAILY_LOSS", ex.code());
    }

    @Test void rejectsWhenAcceptedOrderEntryCapHasBeenReached() {
        LocalDate sessionDate = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        DailyRiskState state = new DailyRiskState(USER, sessionDate);
        for (int i = 0; i < 10; i++) state.recordAcceptedOrderEntry();
        when(dailyRiskStates.findByUserIdForUpdate(USER)).thenReturn(Optional.of(state));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.assess(USER, instrument, request(25, "990.00")));

        assertEquals("MAX_DAILY_ORDER_ENTRIES", ex.code());
    }

    @Test void rejectsOrdersDuringOpeningCooldown() {
        useTime(LocalTime.of(9, 19));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.assess(USER, instrument, request(25, "990.00")));

        assertEquals("OPENING_COOLDOWN", ex.code());
    }

    @Test void rejectsOrdersAtTheConfiguredCutoff() {
        useTime(LocalTime.of(15, 0));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.assess(USER, instrument, request(25, "990.00")));

        assertEquals("ORDER_ENTRY_CUTOFF", ex.code());
    }

    @Test void orderUsesActiveLimitUntilQueuedRelaxationReachesItsEffectiveDate() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        RiskPolicy policy = new RiskPolicy(USER);
        policy.update(200, new BigDecimal("100000.00"), 5, new BigDecimal("5000.00"),
                new BigDecimal("1000.00"), 10, LocalTime.of(15, 0), 5, today, today.plusDays(1));
        when(policies.findByUserIdForUpdate(USER)).thenReturn(Optional.of(policy));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.assess(USER, instrument, request(125, "990.00")));

        assertEquals("MAX_QUANTITY", ex.code());
        assertEquals(100, policy.getMaxQuantity());
        assertEquals(200, policy.getPendingLimits().maxQuantity());
        verify(policies, never()).save(policy);
    }

    @Test void orderAppliesAQueuedRelaxationOnItsEffectiveIstDate() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        RiskPolicy policy = new RiskPolicy(USER);
        policy.update(200, new BigDecimal("100000.00"), 5, new BigDecimal("5000.00"),
                new BigDecimal("1000.00"), 10, LocalTime.of(15, 0), 5, today.minusDays(1), today);
        when(policies.findByUserIdForUpdate(USER)).thenReturn(Optional.of(policy));

        service.assess(USER, instrument, request(25, "990.00"));

        assertEquals(200, policy.getMaxQuantity());
        org.junit.jupiter.api.Assertions.assertNull(policy.getPendingLimits());
        verify(policies).save(policy);
    }

    @Test void resetsStaleDailyRiskStateAtIstSessionBoundary() {
        LocalDate sessionDate = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        DailyRiskState stale = new DailyRiskState(USER, sessionDate.minusDays(1));
        when(dailyRiskStates.findByUserIdForUpdate(USER)).thenReturn(Optional.of(stale));

        service.assess(USER, instrument, request(25, "990.00"));

        assertEquals(sessionDate, stale.getSessionDate());
        verify(dailyRiskStates).save(stale);
    }

    @Test void rejectsFutureDatedDailyRiskState() {
        LocalDate futureDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1);
        when(dailyRiskStates.findByUserIdForUpdate(USER)).thenReturn(Optional.of(new DailyRiskState(USER, futureDate)));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.assess(USER, instrument, request(25, "990.00")));

        assertEquals("DAILY_RISK_STATE_INVALID", ex.code());
    }

    @Test void killSwitchBlocksEveryNewOrder() {
        AccountControl control = new AccountControl(USER);
        control.activate("test");
        org.mockito.Mockito.lenient().when(controls.findById(USER)).thenReturn(Optional.of(control));
        ApiException ex = assertThrows(ApiException.class, () -> service.assess(USER, instrument, request(25, "990.00")));
        assertEquals("KILL_SWITCH_ACTIVE", ex.code());
    }

    private OrderRequest request(long quantity, String stop) {
        return new OrderRequest("client-order-123", instrument.getId(), OrderSide.BUY, OrderType.MARKET, quantity,
                null, null, new BigDecimal(stop));
    }

    private OrderRequest orderRequest(OrderType orderType, BigDecimal limitPrice, BigDecimal triggerPrice) {
        return new OrderRequest("client-order-456", instrument.getId(), OrderSide.BUY, orderType, 25,
                limitPrice, triggerPrice, new BigDecimal("990.00"));
    }

    private void useTime(LocalTime localTime) {
        LocalDate sessionDate = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        Instant testTime = sessionDate.atTime(localTime).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
        service = new RiskService(policies, controls, orders, dailyRiskStates, sessions,
                Clock.fixed(testTime, ZoneOffset.UTC));
    }
}
