package com.algotradehub.backend.risk;

import com.algotradehub.backend.audit.AuditService;
import com.algotradehub.backend.common.ApiException;
import com.algotradehub.backend.broker.BrokerOrderResult;
import com.algotradehub.backend.broker.MockBrokerAdapter;
import com.algotradehub.backend.orders.OrderEntity;
import com.algotradehub.backend.orders.OrderRepository;
import com.algotradehub.backend.orders.OrderRequest;
import com.algotradehub.backend.orders.OrderSide;
import com.algotradehub.backend.orders.OrderStatus;
import com.algotradehub.backend.orders.OrderType;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KillSwitchControllerTest {
    private static final UUID USER_ID = UUID.fromString("a3c3fbe5-5b9d-4f67-920a-e38b43b7b9c8");
    @Mock private AccountControlRepository controls;
    @Mock private RiskPolicyRepository policies;
    @Mock private OrderRepository orders;
    @Mock private MockBrokerAdapter broker;
    @Mock private AuditService audit;
    @Mock private Jwt jwt;
    @Mock private HttpServletRequest request;

    @Test
    void cancellationFailureKeepsKillSwitchActiveAndReportsUnresolvedOrder() {
        AccountControl control = new AccountControl(USER_ID);
        OrderEntity openOrder = openOrder();
        KillSwitchController controller = new KillSwitchController(controls, policies, orders, broker, audit);
        when(jwt.getClaimAsString("uid")).thenReturn(USER_ID.toString());
        when(request.getAttribute("correlationId")).thenReturn("corr-kill-1");
        when(policies.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(new RiskPolicy(USER_ID)));
        when(controls.findById(USER_ID)).thenReturn(Optional.of(control));
        when(orders.findByUserIdAndStatusInOrderByCreatedAtDesc(eq(USER_ID), anyList())).thenReturn(List.of(openOrder));
        when(broker.cancelOrder("MOCK-ORDER-1")).thenThrow(new IllegalStateException("simulated broker outage"));

        KillSwitchController.KillSwitchView result = controller.set(jwt,
                new KillSwitchController.KillSwitchRequest(true, "unexpected behavior"), request);

        assertTrue(result.active());
        assertEquals(0, result.confirmedCancelled());
        assertEquals(1, result.unresolvedOrders());
        assertTrue(control.isKillSwitchActive());
        assertEquals("OPEN", openOrder.getStatus());
        verify(controls).save(control);
        verify(orders, never()).save(openOrder);

        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(USER_ID), eq("KILL_SWITCH_CHANGE"), eq("ACCOUNT"), eq(USER_ID.toString()),
                eq("SUCCESS"), isNull(), eq("corr-kill-1"), details.capture());
        assertTrue(details.getValue().contains("unresolved=1"));
    }

    @Test
    void nonTerminalCancellationResponseKeepsOrderUnresolved() {
        AccountControl control = new AccountControl(USER_ID);
        OrderEntity openOrder = openOrder();
        KillSwitchController controller = new KillSwitchController(controls, policies, orders, broker, audit);
        when(jwt.getClaimAsString("uid")).thenReturn(USER_ID.toString());
        when(request.getAttribute("correlationId")).thenReturn("corr-kill-unknown");
        when(policies.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(new RiskPolicy(USER_ID)));
        when(controls.findById(USER_ID)).thenReturn(Optional.of(control));
        when(orders.findByUserIdAndStatusInOrderByCreatedAtDesc(eq(USER_ID), anyList())).thenReturn(List.of(openOrder));
        when(broker.cancelOrder("MOCK-ORDER-1"))
                .thenReturn(new BrokerOrderResult("MOCK-ORDER-1", OrderStatus.UNKNOWN));

        KillSwitchController.KillSwitchView result = controller.set(jwt,
                new KillSwitchController.KillSwitchRequest(true, "ambiguous cancellation"), request);

        assertTrue(result.active());
        assertEquals(0, result.confirmedCancelled());
        assertEquals(1, result.unresolvedOrders());
        assertTrue(control.isKillSwitchActive());
        assertEquals("OPEN", openOrder.getStatus());
        verify(controls).save(control);
        verify(orders, never()).save(openOrder);
    }

    @Test
    void repeatedActivationRemainsActiveAndRetriesVisibleCancellation() {
        AccountControl control = new AccountControl(USER_ID);
        control.activate("first request");
        OrderEntity openOrder = openOrder();
        KillSwitchController controller = new KillSwitchController(controls, policies, orders, broker, audit);
        when(jwt.getClaimAsString("uid")).thenReturn(USER_ID.toString());
        when(request.getAttribute("correlationId")).thenReturn("corr-kill-retry");
        when(policies.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(new RiskPolicy(USER_ID)));
        when(controls.findById(USER_ID)).thenReturn(Optional.of(control));
        when(orders.findByUserIdAndStatusInOrderByCreatedAtDesc(eq(USER_ID), anyList())).thenReturn(List.of(openOrder));
        when(broker.cancelOrder("MOCK-ORDER-1"))
                .thenReturn(new BrokerOrderResult("MOCK-ORDER-1", OrderStatus.CANCELLED));
        when(orders.save(openOrder)).thenReturn(openOrder);

        KillSwitchController.KillSwitchView result = controller.set(jwt,
                new KillSwitchController.KillSwitchRequest(true, "repeat request"), request);

        assertTrue(result.active());
        assertEquals(1, result.confirmedCancelled());
        assertEquals(0, result.unresolvedOrders());
        verify(broker).cancelOrder("MOCK-ORDER-1");
        verify(controls).save(control);
    }

    @Test
    void deactivationIsRejectedAndAuditedWhileOrdersRemainUnresolved() {
        AccountControl control = new AccountControl(USER_ID);
        control.activate("existing issue");
        KillSwitchController controller = new KillSwitchController(controls, policies, orders, broker, audit);
        when(jwt.getClaimAsString("uid")).thenReturn(USER_ID.toString());
        when(request.getAttribute("correlationId")).thenReturn("corr-kill-2");
        when(policies.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(new RiskPolicy(USER_ID)));
        when(controls.findById(USER_ID)).thenReturn(Optional.of(control));
        when(orders.countByUserIdAndStatusIn(eq(USER_ID), anyList())).thenReturn(2L);

        ApiException error = assertThrows(ApiException.class, () -> controller.set(jwt,
                new KillSwitchController.KillSwitchRequest(false, null), request));

        assertEquals(HttpStatus.CONFLICT, error.status());
        assertEquals("KILL_SWITCH_UNRESOLVED_ORDERS", error.code());
        assertTrue(control.isKillSwitchActive());
        verify(controls, never()).save(control);
        verify(broker, never()).cancelOrder(anyString());
        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(USER_ID), eq("KILL_SWITCH_DEACTIVATION_BLOCKED"), eq("ACCOUNT"), eq(USER_ID.toString()),
                eq("BLOCKED"), eq("UNRESOLVED_ORDERS"), eq("corr-kill-2"), details.capture());
        assertTrue(details.getValue().contains("unresolved=2"));
    }

    private OrderEntity openOrder() {
        OrderRequest request = new OrderRequest("kill-switch-test-order", UUID.randomUUID(), OrderSide.BUY,
                OrderType.MARKET, 1, null, null, new BigDecimal("99.00"));
        return new OrderEntity(UUID.randomUUID(), USER_ID, request.clientOrderId(), request,
                "OPEN", "MOCK-ORDER-1", new BigDecimal("1.00"));
    }
}
