package com.algotradehub.backend.broker;

import com.algotradehub.backend.orders.OrderRequest;
import com.algotradehub.backend.orders.OrderStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
@Profile("!live")
public class MockBrokerAdapter implements BrokerAdapter {
    @Override public BrokerProvider provider() { return BrokerProvider.MOCK; }
    @Override public boolean supportsLiveExecution() { return false; }
    @Override public BrokerOrderResult placeOrder(UUID userId, OrderRequest request) {
        return new BrokerOrderResult("MOCK-" + UUID.randomUUID(), OrderStatus.OPEN);
    }
    @Override public BrokerOrderResult cancelOrder(String brokerOrderId) {
        return new BrokerOrderResult(brokerOrderId, OrderStatus.CANCELLED);
    }
}