package com.algotradehub.backend.risk;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskPolicyTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
    private static final LocalDate NEXT_SESSION = LocalDate.of(2026, 10, 22);

    @Test void appliesTighterValuesImmediatelyAndStagesOnlyRelaxations() {
        RiskPolicy policy = new RiskPolicy(java.util.UUID.randomUUID());

        policy.update(50, new BigDecimal("200000.00"), 4, new BigDecimal("5000.00"),
                new BigDecimal("1500.00"), 10, LocalTime.of(15, 0), 5, TODAY, NEXT_SESSION);

        assertEquals(50, policy.getMaxQuantity());
        assertEquals(0, policy.getMaxOrderNotional().compareTo(new BigDecimal("100000.00")));
        assertEquals(4, policy.getMaxOpenOrders());
        assertEquals(0, policy.getMaxDailyLoss().compareTo(new BigDecimal("5000.00")));
        assertEquals(0, policy.getMaxTradeLoss().compareTo(new BigDecimal("1000.00")));
        assertEquals(new BigDecimal("200000.00"), policy.getPendingLimits().maxOrderNotional());
        assertEquals(new BigDecimal("1500.00"), policy.getPendingLimits().maxTradeLoss());
        assertNull(policy.getPendingLimits().maxQuantity());
        assertNull(policy.getPendingLimits().maxOpenOrders());
        assertNull(policy.getPendingLimits().maxDailyLoss());
        assertEquals(NEXT_SESSION, policy.getPendingLimits().effectiveOn());
    }

    @Test void keepsRelaxationsInactiveUntilTheEffectiveSessionDate() {
        RiskPolicy policy = new RiskPolicy(java.util.UUID.randomUUID());
        policy.update(200, new BigDecimal("200000.00"), 10, new BigDecimal("10000.00"),
                new BigDecimal("2000.00"), 20, LocalTime.of(15, 15), 0, TODAY, NEXT_SESSION);

        assertFalse(policy.applyPendingLimits(TODAY));
        assertEquals(100, policy.getMaxQuantity());
        assertEquals(5, policy.getMaxOpenOrders());
        assertEquals(NEXT_SESSION, policy.getPendingLimits().effectiveOn());

        assertTrue(policy.applyPendingLimits(NEXT_SESSION));
        assertEquals(200, policy.getMaxQuantity());
        assertEquals(10, policy.getMaxOpenOrders());
        assertEquals(0, policy.getMaxOrderNotional().compareTo(new BigDecimal("200000.00")));
        assertEquals(0, policy.getMaxDailyLoss().compareTo(new BigDecimal("10000.00")));
        assertEquals(0, policy.getMaxTradeLoss().compareTo(new BigDecimal("2000.00")));
        assertNull(policy.getPendingLimits());
        assertFalse(policy.applyPendingLimits(NEXT_SESSION));
    }

    @Test void changingAQueuedRelaxationBackToTheActiveLimitCancelsIt() {
        RiskPolicy policy = new RiskPolicy(java.util.UUID.randomUUID());
        policy.update(200, new BigDecimal("100000.00"), 5, new BigDecimal("5000.00"),
                new BigDecimal("1000.00"), 10, LocalTime.of(15, 0), 5, TODAY, NEXT_SESSION);
        assertEquals(200, policy.getPendingLimits().maxQuantity());

        policy.update(100, new BigDecimal("100000.00"), 5, new BigDecimal("5000.00"),
                new BigDecimal("1000.00"), 10, LocalTime.of(15, 0), 5, TODAY, NEXT_SESSION);

        assertEquals(100, policy.getMaxQuantity());
        assertNull(policy.getPendingLimits());
    }

    @Test void rejectsAnEffectiveDateThatIsNotAfterTheCurrentDate() {
        RiskPolicy policy = new RiskPolicy(java.util.UUID.randomUUID());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> policy.update(200, new BigDecimal("100000.00"), 5, new BigDecimal("5000.00"),
                        new BigDecimal("1000.00"), 10, LocalTime.of(15, 0), 5, TODAY, TODAY));
        assertEquals(100, policy.getMaxQuantity());
        assertNull(policy.getPendingLimits());
    }

}
