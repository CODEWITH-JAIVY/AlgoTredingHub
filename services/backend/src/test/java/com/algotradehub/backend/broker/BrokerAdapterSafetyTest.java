package com.algotradehub.backend.broker;

import com.algotradehub.backend.orders.OrderRequest;
import com.algotradehub.backend.orders.OrderSide;
import com.algotradehub.backend.orders.OrderStatus;
import com.algotradehub.backend.orders.OrderType;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokerAdapterSafetyTest {
    @Test
    void mockAdapterOnlyReturnsExplicitMockAcknowledgements() {
        MockBrokerAdapter mock = new MockBrokerAdapter();
        BrokerOrderResult placed = mock.placeOrder(UUID.randomUUID(), sampleOrder());

        assertEquals(BrokerProvider.MOCK, mock.provider());
        assertFalse(mock.supportsLiveExecution());
        assertTrue(placed.brokerOrderId().startsWith("MOCK-"));
        assertEquals(OrderStatus.OPEN, placed.status());

        BrokerOrderResult cancelled = mock.cancelOrder(placed.brokerOrderId());
        assertEquals(placed.brokerOrderId(), cancelled.brokerOrderId());
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
    }

    @Test
    void zerodhaBoundaryCannotAdvertiseOrExecuteLiveOrders() {
        ZerodhaAdapterBoundary boundary = new ZerodhaAdapterBoundary();

        assertEquals(BrokerProvider.ZERODHA, boundary.provider());
        assertFalse(boundary.supportsLiveExecution());
        assertThrows(UnsupportedOperationException.class,
                () -> boundary.placeOrder(UUID.randomUUID(), sampleOrder()));
        assertThrows(UnsupportedOperationException.class,
                () -> boundary.cancelOrder("broker-order-placeholder"));
    }

    @Test
    void mockAdapterIsExcludedFromLiveProfile() {
        Profile profile = MockBrokerAdapter.class.getAnnotation(Profile.class);

        assertNotNull(profile);
        assertTrue(Arrays.asList(profile.value()).contains("!live"));
    }

    private OrderRequest sampleOrder() {
        return new OrderRequest("broker-test-order", UUID.randomUUID(), OrderSide.BUY, OrderType.MARKET, 1,
                null, null, new BigDecimal("99.00"));
    }
}
