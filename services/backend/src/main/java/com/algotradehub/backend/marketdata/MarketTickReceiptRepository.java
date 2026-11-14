package com.algotradehub.backend.marketdata;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MarketTickReceiptRepository extends JpaRepository<MarketTickReceiptEntity, String> {
    @Modifying
    @Query(value = "INSERT INTO market_tick_receipts(event_key,instrument_id,source_id,sequence_number,exchange_time,received_at,price,trade_quantity,instrument_master_version,processing_status,outcome_code) " +
            "VALUES (:eventKey,:instrumentId,:sourceId,:sequenceNumber,:exchangeTime,:receivedAt,:price,:tradeQuantity,:masterVersion,'RECEIVED',NULL)",
            nativeQuery = true)
    int insertClaim(@Param("eventKey") String eventKey, @Param("instrumentId") UUID instrumentId,
                    @Param("sourceId") String sourceId, @Param("sequenceNumber") long sequenceNumber,
                    @Param("exchangeTime") Instant exchangeTime, @Param("receivedAt") Instant receivedAt,
                    @Param("price") java.math.BigDecimal price, @Param("tradeQuantity") long tradeQuantity,
                    @Param("masterVersion") String masterVersion);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MarketTickReceiptEntity receipt SET receipt.processingStatus = :status, "
            + "receipt.outcomeCode = :outcomeCode WHERE receipt.eventKey = :eventKey "
            + "AND receipt.processingStatus = :expectedStatus")
    int transitionFromStatus(@Param("eventKey") String eventKey,
                             @Param("expectedStatus") MarketTickReceiptStatus expectedStatus,
                             @Param("status") MarketTickReceiptStatus status,
                             @Param("outcomeCode") String outcomeCode);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MarketTickReceiptEntity receipt SET receipt.processingStatus = :reconciliationStatus, "
            + "receipt.outcomeCode = :outcomeCode WHERE receipt.eventKey = :eventKey "
            + "AND receipt.processingStatus IN :recoverableStatuses")
    int requireReconciliation(@Param("eventKey") String eventKey,
                              @Param("recoverableStatuses") List<MarketTickReceiptStatus> recoverableStatuses,
                              @Param("reconciliationStatus") MarketTickReceiptStatus reconciliationStatus,
                              @Param("outcomeCode") String outcomeCode);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MarketTickReceiptEntity receipt SET receipt.processingStatus = :finalizedStatus, "
            + "receipt.outcomeCode = NULL WHERE receipt.instrumentId = :instrumentId "
            + "AND receipt.sourceId = :sourceId AND receipt.masterVersion = :masterVersion "
            + "AND receipt.exchangeTime >= :bucketStart AND receipt.exchangeTime < :bucketEnd "
            + "AND receipt.processingStatus = :bufferedStatus")
    int markCandleTicksFinalized(@Param("instrumentId") UUID instrumentId,
                                 @Param("sourceId") String sourceId,
                                 @Param("masterVersion") String masterVersion,
                                 @Param("bucketStart") Instant bucketStart,
                                 @Param("bucketEnd") Instant bucketEnd,
                                 @Param("bufferedStatus") MarketTickReceiptStatus bufferedStatus,
                                 @Param("finalizedStatus") MarketTickReceiptStatus finalizedStatus);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MarketTickReceiptEntity receipt SET receipt.processingStatus = :reconciliationStatus, "
            + "receipt.outcomeCode = :outcomeCode WHERE receipt.receivedAt <= :cutoff "
            + "AND receipt.processingStatus IN :recoverableStatuses")
    int expireRecoveryWindow(@Param("cutoff") Instant cutoff,
                             @Param("recoverableStatuses") List<MarketTickReceiptStatus> recoverableStatuses,
                             @Param("reconciliationStatus") MarketTickReceiptStatus reconciliationStatus,
                             @Param("outcomeCode") String outcomeCode);

    List<MarketTickReceiptEntity> findByReceivedAtAfterAndProcessingStatusInOrderByExchangeTimeAscSequenceNumberAsc(
            Instant after, List<MarketTickReceiptStatus> processingStatuses);
    long deleteByReceivedAtBefore(Instant before);
}
