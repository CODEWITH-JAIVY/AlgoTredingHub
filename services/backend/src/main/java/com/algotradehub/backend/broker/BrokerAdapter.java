package com.algotradehub.backend.broker;

import com.algotradehub.backend.orders.OrderRequest;
import java.util.UUID;

public interface BrokerAdapter {
    BrokerProvider provider();
    boolean supportsLiveExecution();
    BrokerOrderResult placeOrder(UUID userId, OrderRequest request);
    BrokerOrderResult cancelOrder(String brokerOrderId);
}