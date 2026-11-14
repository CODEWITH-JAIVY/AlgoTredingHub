package com.algotradehub.backend.orders;

import com.algotradehub.backend.audit.AuditService;
import com.algotradehub.backend.broker.BrokerAdapter;
import com.algotradehub.backend.broker.BrokerOrderResult;
import com.algotradehub.backend.broker.BrokerProvider;
import com.algotradehub.backend.common.ApiException;
import com.algotradehub.backend.risk.RiskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {
    private static final UUID USER = UUID.fromString("77a20bdd-d2e4-46d7-93a7-72a0ce11a00e");
    @Mock private OrderRepository orders;
    @Mock private InstrumentRepository instruments;
    @Mock private RiskService risk;
    @Mock private BrokerAdapter broker;
    @Mock private AuditService audit;
    private OrderService service;
    private Instrument instrument;
    private OrderRequest request;

    @BeforeEach void setUp() {
        when(broker.supportsLiveExecution()).thenReturn(false);
        when(broker.provider()).thenReturn(BrokerProvider.MOCK);
        service = new OrderService(orders, instruments, risk, broker, audit);
        instrument = new Instrument(UUID.randomUUID(), "DEMO-001", "NSE", "CASH", "DEMO-RELIANCE", "EQUITY", 1,
                new BigDecimal("0.05"), new BigDecimal("100.00"), "DEMO-1");
        request = new OrderRequest("client-order-123", instrument.getId(), OrderSide.BUY, OrderType.MARKET, 1,
                null, null, new BigDecimal("99.00"));
    }

    @Test void retryWithSameIdAndPayloadReturnsOriginalOrderWithoutSecondBrokerCall() {
        OrderEntity original = new OrderEntity(UUID.randomUUID(), USER, request.clientOrderId(), request, "OPEN", "MOCK-1", new BigDecimal("1.00"));
        when(orders.findByUserIdAndClientOrderId(USER, request.clientOrderId())).thenReturn(Optional.of(original));
        OrderView result = service.place(USER, request, "corr-1");
        assertEquals(original.getId(), result.id());
        verify(broker, never()).placeOrder(any(), any());
        verify(risk, never()).assess(any(), any(), any());
        verify(risk, never()).recordAcceptedOrderEntry(any(), any());
    }

    @Test void reusingIdForDifferentPayloadIsRejected() {
        OrderEntity original = new OrderEntity(UUID.randomUUID(), USER, request.clientOrderId(), request, "OPEN", "MOCK-1", new BigDecimal("1.00"));
        when(orders.findByUserIdAndClientOrderId(USER, request.clientOrderId())).thenReturn(Optional.of(original));
        OrderRequest changed = new OrderRequest(request.clientOrderId(), request.instrumentId(), OrderSide.BUY, OrderType.MARKET,
                2, null, null, new BigDecimal("99.00"));
        ApiException ex = assertThrows(ApiException.class, () -> service.place(USER, changed, "corr-2"));
        assertEquals("IDEMPOTENCY_KEY_REUSED", ex.code());
        verify(broker, never()).placeOrder(any(), any());
    }

    @Test void mockAcceptanceIsPersistedWithExplicitMockMode() {
        LocalDate assessedSessionDate = LocalDate.of(2026, 10, 21);
        when(orders.findByUserIdAndClientOrderId(USER, request.clientOrderId())).thenReturn(Optional.empty());
        when(instruments.findById(instrument.getId())).thenReturn(Optional.of(instrument));
        when(risk.assess(USER, instrument, request)).thenReturn(new RiskService.RiskAssessment(new BigDecimal("100.00"),
                new BigDecimal("100.00"), new BigDecimal("1.00"), assessedSessionDate));
        when(broker.placeOrder(USER, request)).thenReturn(new BrokerOrderResult("MOCK-1", OrderStatus.OPEN));
        when(orders.save(any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        OrderView result = service.place(USER, request, "corr-3");
        assertEquals("MOCK", result.mode());
        assertEquals("OPEN", result.status());
        verify(risk).lockAccountForOrder(USER);
        verify(broker).placeOrder(USER, request);
        verify(risk).recordAcceptedOrderEntry(USER, assessedSessionDate);
    }

    @Test void rejectedMockSubmissionDoesNotConsumeDailyEntry() {
        when(orders.findByUserIdAndClientOrderId(USER, request.clientOrderId())).thenReturn(Optional.empty());
        when(instruments.findById(instrument.getId())).thenReturn(Optional.of(instrument));
        when(risk.assess(USER, instrument, request)).thenReturn(new RiskService.RiskAssessment(new BigDecimal("100.00"),
                new BigDecimal("100.00"), new BigDecimal("1.00"), LocalDate.of(2026, 10, 21)));
        when(broker.placeOrder(USER, request)).thenReturn(new BrokerOrderResult("MOCK-REJECTED", OrderStatus.REJECTED));
        when(orders.save(any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderView result = service.place(USER, request, "corr-rejected-mock");

        assertEquals("REJECTED", result.status());
        verify(risk, never()).recordAcceptedOrderEntry(any(), any());
    }
}
