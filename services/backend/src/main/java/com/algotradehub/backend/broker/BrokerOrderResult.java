package com.algotradehub.backend.broker;

import com.algotradehub.backend.orders.OrderStatus;

public record BrokerOrderResult(String brokerOrderId, OrderStatus status) { }