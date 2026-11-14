package com.algotradehub.backend.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderView(UUID id, String clientOrderId, UUID instrumentId, String side, String orderType, long quantity,
                        BigDecimal limitPrice, BigDecimal triggerPrice, BigDecimal protectiveStopPrice, String status,
                        String brokerOrderId, String mode, Instant createdAt) {
    public static OrderView from(OrderEntity order) {
        return new OrderView(order.getId(), order.getClientOrderId(), order.getInstrumentId(), order.getSide(),
                order.getOrderType(), order.getQuantity(), order.getLimitPrice(), order.getTriggerPrice(),
                order.getStopLossPrice(), order.getStatus(), order.getBrokerOrderId(), order.getMode(), order.getCreatedAt());
    }
}