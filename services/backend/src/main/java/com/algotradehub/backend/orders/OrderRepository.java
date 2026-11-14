package com.algotradehub.backend.orders;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {
    Optional<OrderEntity> findByUserIdAndClientOrderId(UUID userId, String clientOrderId);
    Optional<OrderEntity> findByIdAndUserId(UUID id, UUID userId);
    List<OrderEntity> findByUserIdAndStatusInOrderByCreatedAtDesc(UUID userId, Collection<String> statuses);
    long countByUserIdAndStatusIn(UUID userId, Collection<String> statuses);
    @org.springframework.data.jpa.repository.Query("select coalesce(sum(o.estimatedStopRisk), 0) from OrderEntity o where o.userId = :userId and o.status in :statuses")
    java.math.BigDecimal sumEstimatedRisk(@org.springframework.data.repository.query.Param("userId") UUID userId, @org.springframework.data.repository.query.Param("statuses") Collection<String> statuses);
    List<OrderEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
}