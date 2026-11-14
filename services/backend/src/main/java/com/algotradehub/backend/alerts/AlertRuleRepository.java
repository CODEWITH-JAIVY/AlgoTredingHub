package com.algotradehub.backend.alerts;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlertRuleRepository extends JpaRepository<AlertRuleEntity, UUID> {
    List<AlertRuleEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
    Optional<AlertRuleEntity> findByIdAndUserId(UUID id, UUID userId);
    long countByUserIdAndActiveTrue(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AlertRuleEntity r where r.instrumentId = :instrumentId and r.active = true order by r.createdAt asc")
    List<AlertRuleEntity> lockActiveForInstrument(@Param("instrumentId") UUID instrumentId);
}
