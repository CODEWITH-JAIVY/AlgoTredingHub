package com.algotradehub.backend.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface RegistrationGateRepository extends JpaRepository<RegistrationGate, Short> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select gate from RegistrationGate gate where gate.id = :id")
    Optional<RegistrationGate> findByIdForUpdate(@Param("id") Short id);
}
