package com.algotradehub.backend.risk;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface DailyRiskStateRepository extends JpaRepository<DailyRiskState, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DailyRiskState s where s.userId = :userId")
    Optional<DailyRiskState> findByUserIdForUpdate(@Param("userId") UUID userId);
}
