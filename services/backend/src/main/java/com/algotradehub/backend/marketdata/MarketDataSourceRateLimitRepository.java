package com.algotradehub.backend.marketdata;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Optional;

public interface MarketDataSourceRateLimitRepository extends JpaRepository<MarketDataSourceRateLimitEntity, String> {
    @Modifying
    @Query(value = "INSERT INTO market_data_source_rate_limits(source_id,last_request_at) VALUES (:sourceId,:initialTime) " +
            "ON CONFLICT (source_id) DO NOTHING", nativeQuery = true)
    int initializeIfAbsent(@Param("sourceId") String sourceId, @Param("initialTime") Instant initialTime);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MarketDataSourceRateLimitEntity r where r.sourceId = :sourceId")
    Optional<MarketDataSourceRateLimitEntity> findBySourceIdForUpdate(@Param("sourceId") String sourceId);
}
