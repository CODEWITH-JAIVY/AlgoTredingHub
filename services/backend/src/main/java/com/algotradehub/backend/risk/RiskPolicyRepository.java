package com.algotradehub.backend.risk;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface RiskPolicyRepository extends JpaRepository<RiskPolicy, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from RiskPolicy p where p.userId = :userId")
    Optional<RiskPolicy> findByUserIdForUpdate(@Param("userId") UUID userId);
}