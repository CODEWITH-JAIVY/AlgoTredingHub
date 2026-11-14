package com.algotradehub.backend.orders;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InstrumentRepository extends JpaRepository<Instrument, UUID> {
    List<Instrument> findByActiveTrueOrderByExchangeAscSymbolAsc();
    Optional<Instrument> findByExchangeAndProviderToken(String exchange, String providerToken);
}
