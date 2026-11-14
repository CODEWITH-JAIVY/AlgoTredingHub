package com.algotradehub.backend.alerts;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlertEventRepository extends JpaRepository<AlertEventEntity, UUID> {
    List<AlertEventEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
    Optional<AlertEventEntity> findByIdAndUserId(UUID id, UUID userId);
}
