package com.algotradehub.backend.marketdata;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MarketDataGapRepository extends JpaRepository<MarketDataGapEntity, UUID> {
    boolean existsByInstrumentIdAndSourceIdAndGapStartAndGapEnd(UUID instrumentId, String sourceId, Instant gapStart, Instant gapEnd);
    List<MarketDataGapEntity> findByInstrumentIdAndGapStartLessThanAndGapEndGreaterThanOrderByGapStartAsc(
            UUID instrumentId, Instant toExclusive, Instant fromExclusive, Pageable pageable);
    List<MarketDataGapEntity> findByInstrumentIdAndGapStartLessThanAndGapEndGreaterThanAndSourceIdOrderByGapStartAsc(
            UUID instrumentId, Instant toExclusive, Instant fromExclusive, String sourceId, Pageable pageable);
}
