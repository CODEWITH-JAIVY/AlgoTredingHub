package com.algotradehub.backend.risk;

import com.algotradehub.backend.common.ApiException;
import com.algotradehub.backend.marketdata.MarketSessionCalendar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskPolicyControllerTest {
    private static final UUID USER_ID = UUID.fromString("bc859ced-b9d4-4f23-9f61-b2b064df664e");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-21T04:30:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
    @Mock private RiskPolicyRepository policies;
    @Mock private MarketSessionCalendar sessions;
    @Mock private DailyRiskStateRepository dailyRiskStates;
    private RiskPolicyController controller;
    private Jwt jwt;

    @BeforeEach void setUp() {
        controller = new RiskPolicyController(policies, sessions, dailyRiskStates, FIXED_CLOCK);
        jwt = Jwt.withTokenValue("test-access-token").header("alg", "none").claim("uid", USER_ID.toString()).build();
    }

    @Test void updateReturnsActiveLimitsAndClearlyExposesTheQueuedRelaxation() {
        LocalDate today = TODAY;
        LocalDate nextSession = today.plusDays(1);
        RiskPolicy current = new RiskPolicy(USER_ID);
        when(policies.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(current));
        when(policies.save(current)).thenReturn(current);
        when(sessions.nextRegularTradingDate(today)).thenReturn(Optional.of(nextSession));
        RiskPolicyController.PolicyUpdate request = new RiskPolicyController.PolicyUpdate(200,
                new BigDecimal("200000.00"), 10, new BigDecimal("10000.00"), new BigDecimal("2000.00"),
                20, LocalTime.of(15, 15), 0);

        RiskPolicyController.PolicyView view = controller.update(jwt, request);

        assertEquals(100, view.maxQuantity());
        assertEquals(5, view.maxOpenOrders());
        assertEquals(200, view.pending().maxQuantity());
        assertEquals(10, view.pending().maxOpenOrders());
        assertEquals(nextSession, view.pending().effectiveOn());
        verify(policies).save(current);
    }

    @Test void updateFailsClosedWhenNoVerifiedNextSessionExists() {
        LocalDate today = TODAY;
        when(policies.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(new RiskPolicy(USER_ID)));
        when(sessions.nextRegularTradingDate(today)).thenReturn(Optional.empty());
        RiskPolicyController.PolicyUpdate request = new RiskPolicyController.PolicyUpdate(200,
                new BigDecimal("200000.00"), 10, new BigDecimal("10000.00"), new BigDecimal("2000.00"),
                20, LocalTime.of(15, 15), 0);

        ApiException error = assertThrows(ApiException.class, () -> controller.update(jwt, request));

        assertEquals("MARKET_CALENDAR_UNAVAILABLE", error.code());
    }

    @Test void getActivatesDuePendingLimitsAndRemovesTheSchedule() {
        LocalDate today = TODAY;
        when(sessions.isCalendarDateSupported(today)).thenReturn(true);
        RiskPolicy current = new RiskPolicy(USER_ID);
        current.update(200, new BigDecimal("100000.00"), 5, new BigDecimal("5000.00"),
                new BigDecimal("1000.00"), 10, LocalTime.of(15, 0), 5, today.minusDays(1), today);
        when(policies.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(current));

        RiskPolicyController.PolicyView view = controller.get(jwt);

        assertEquals(200, view.maxQuantity());
        assertNull(view.pending());
        verify(policies).save(current);
    }
}
