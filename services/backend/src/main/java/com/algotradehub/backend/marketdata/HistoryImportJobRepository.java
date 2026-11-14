package com.algotradehub.backend.marketdata;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface HistoryImportJobRepository extends JpaRepository<HistoryImportJobEntity, UUID> {
    Optional<HistoryImportJobEntity> findByRequestKey(String requestKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from HistoryImportJobEntity j where j.id = :id")
    Optional<HistoryImportJobEntity> findByIdForUpdate(@Param("id") UUID id);

    @Modifying
    @Query(value = "INSERT INTO history_import_jobs(id,request_key,instrument_id,source_id,timeframe_seconds,range_start,range_end,status,imported_count,created_at,updated_at) " +
            "VALUES (:id,:requestKey,:instrumentId,:sourceId,60,:from,:to,'PENDING',0,:now,:now) ON CONFLICT (request_key) DO NOTHING", nativeQuery = true)
    int createIfAbsent(@Param("id") UUID id, @Param("requestKey") String requestKey,
                       @Param("instrumentId") UUID instrumentId, @Param("sourceId") String sourceId,
                       @Param("from") Instant from, @Param("to") Instant to, @Param("now") Instant now);
}
