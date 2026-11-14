package com.algotradehub.backend.broker;

import com.algotradehub.backend.orders.OrderRequest;
import org.springframework.stereotype.Component;
import java.util.UUID;

/**
 * Phase 1 boundary only. No HTTP client, credentials, token flow, or order endpoint is present.
 * Live execution remains unavailable until the later compliance and deployment gates pass.
 */
@Component
public class ZerodhaAdapterBoundary implements BrokerAdapter {
    @Override public BrokerProvider provider() { return BrokerProvider.ZERODHA; }
    @Override public boolean supportsLiveExecution() { return false; }
    @Override public BrokerOrderResult placeOrder(UUID userId, OrderRequest request) {
        throw new UnsupportedOperationException("Zerodha live orders are disabled in this phase.");
    }
    @Override public BrokerOrderResult cancelOrder(String brokerOrderId) {
        throw new UnsupportedOperationException("Zerodha live orders are disabled in this phase.");
    }
}