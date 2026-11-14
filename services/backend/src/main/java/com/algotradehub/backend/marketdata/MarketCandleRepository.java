package com.algotradehub.backend.marketdata;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MarketCandleRepository extends JpaRepository<MarketCandleEntity, UUID> {
    List<MarketCandleEntity> findByInstrumentIdAndTimeframeSecondsAndBucketStartGreaterThanEqualAndBucketStartLessThanOrderByBucketStartAsc(
            UUID instrumentId, int timeframeSeconds, Instant fromInclusive, Instant toExclusive, Pageable pageable);
    List<MarketCandleEntity> findByInstrumentIdAndTimeframeSecondsAndBucketStartGreaterThanEqualAndBucketStartLessThanAndSourceIdOrderByBucketStartAsc(
            UUID instrumentId, int timeframeSeconds, Instant fromInclusive, Instant toExclusive, String sourceId,
            Pageable pageable);
    boolean existsByInstrumentIdAndTimeframeSecondsAndBucketStartAndSourceId(
            UUID instrumentId, int timeframeSeconds, Instant bucketStart, String sourceId);
    java.util.Optional<MarketCandleEntity> findFirstByInstrumentIdAndTimeframeSecondsAndSourceIdOrderByBucketStartDesc(
            UUID instrumentId, int timeframeSeconds, String sourceId);
    List<MarketCandleEntity> findTop20ByInstrumentIdAndTimeframeSecondsAndSourceIdAndBucketStartBeforeOrderByBucketStartDesc(
            UUID instrumentId, int timeframeSeconds, String sourceId, Instant before);
}
