package com.algotradehub.backend.orders;

import com.algotradehub.backend.audit.AuditService;
import com.algotradehub.backend.broker.BrokerAdapter;
import com.algotradehub.backend.broker.BrokerOrderResult;
import com.algotradehub.backend.common.ApiException;
import com.algotradehub.backend.risk.RiskService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
public class OrderService {
    private final OrderRepository orders;
    private final InstrumentRepository instruments;
    private final RiskService risk;
    private final BrokerAdapter mockBroker;
    private final AuditService audit;
    public OrderService(OrderRepository orders, InstrumentRepository instruments, RiskService risk,
                        @Qualifier("mockBrokerAdapter") BrokerAdapter mockBroker, AuditService audit) {
        this.orders = orders;
        this.instruments = instruments;
        this.risk = risk;
        this.mockBroker = mockBroker;
        this.audit = audit;
        if (mockBroker.supportsLiveExecution() || mockBroker.provider() != com.algotradehub.backend.broker.BrokerProvider.MOCK) {
            throw new IllegalStateException("Phase 1 order service must be wired to the mock broker only.");
        }
    }

    @Transactional
    public OrderView place(UUID userId, OrderRequest request, String correlationId) {
        risk.lockAccountForOrder(userId);
        var existing = orders.findByUserIdAndClientOrderId(userId, request.clientOrderId());
        if (existing.isPresent()) {
            if (!existing.get().matches(request)) throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "This client order ID was already used for a different order.");
            return OrderView.from(existing.get());
        }
        Instrument instrument = instruments.findById(request.instrumentId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INSTRUMENT_NOT_FOUND", "Instrument was not found."));
        RiskService.RiskAssessment assessment;
        try {
            assessment = risk.assess(userId, instrument, request);
        } catch (ApiException ex) {
            audit.record(userId, "ORDER_RISK_REJECT", "ORDER", request.clientOrderId(), "REJECTED", ex.code(), correlationId, null);
            throw ex;
        }
        BrokerOrderResult result = mockBroker.placeOrder(userId, request);
        OrderEntity order = orders.save(new OrderEntity(UUID.randomUUID(), userId, request.clientOrderId(), request,
                result.status().name(), result.brokerOrderId(), assessment.estimatedStopRisk()));
        boolean accepted = result.status() != OrderStatus.REJECTED;
        if (accepted) risk.recordAcceptedOrderEntry(userId, assessment.sessionDate());
        String outcome = "MOCK_REJECTED";
        if (accepted) outcome = "MOCK_ACCEPTED";
        audit.record(userId, "ORDER_SUBMIT", "ORDER", order.getId().toString(), outcome, null,
                correlationId, "notional=" + assessment.estimatedNotional() + ";stopRisk=" + assessment.estimatedStopRisk());
        return OrderView.from(order);
    }

    @Transactional(readOnly = true)
    public List<OrderView> list(UUID userId) {
        return orders.findByUserIdOrderByCreatedAtDesc(userId).stream().map(OrderView::from).toList();
    }

    @Transactional
    public OrderView cancel(UUID userId, UUID orderId, String correlationId) {
        OrderEntity order = orders.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order was not found."));
        if (order.getStatus().equals(OrderStatus.CANCELLED.name())) return OrderView.from(order);
        if (!order.getStatus().equals(OrderStatus.OPEN.name())) throw new ApiException(HttpStatus.CONFLICT, "ORDER_NOT_CANCELLABLE", "Order is no longer open.");
        BrokerOrderResult result = mockBroker.cancelOrder(order.getBrokerOrderId());
        order.cancel(result.brokerOrderId());
        audit.record(userId, "ORDER_CANCEL", "ORDER", order.getId().toString(), "MOCK_CANCELLED", null, correlationId, null);
        return OrderView.from(orders.save(order));
    }
}
