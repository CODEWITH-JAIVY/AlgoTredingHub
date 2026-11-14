package com.algotradehub.backend.marketdata;

import com.algotradehub.backend.orders.Instrument;
import com.algotradehub.backend.orders.InstrumentController;
import com.algotradehub.backend.orders.InstrumentRepository;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstrumentControllerTest {
    @Test void fixedDemoPriceCannotAppearFreshOrCurrent() {
        InstrumentRepository repository = mock(InstrumentRepository.class);
        Instrument instrument = new Instrument(UUID.randomUUID(), "DEMO-NSE-001", "NSE", "CASH", "DEMO-ALPHA",
                "EQUITY", 1, new BigDecimal("0.05"), new BigDecimal("100.00"), "DEMO-1");
        when(repository.findByActiveTrueOrderByExchangeAscSymbolAsc()).thenReturn(List.of(instrument));
        InstrumentController.QuoteView quote = new InstrumentController(repository).quotes().getFirst();
        assertEquals("SYNTHETIC_DEMO", quote.source());
        assertEquals("SIMULATED", quote.freshness());
        assertFalse(quote.fresh());
        assertNull(quote.asOf());
    }

    @Test void instrumentCatalogueShowsMasterVersionAndDoesNotExposeProviderToken() {
        InstrumentRepository repository = mock(InstrumentRepository.class);
        Instrument instrument = new Instrument(UUID.randomUUID(), "SECRET-TO-PROVIDER", "NSE", "CASH", "DEMO-ALPHA",
                "EQUITY", 1, new BigDecimal("0.05"), new BigDecimal("100.00"), "DEMO-1");
        when(repository.findByActiveTrueOrderByExchangeAscSymbolAsc()).thenReturn(List.of(instrument));
        InstrumentController.InstrumentView view = new InstrumentController(repository).instruments().getFirst();
        assertEquals("DEMO-1", view.masterVersion());
        assertEquals("SYNTHETIC_DEMO", view.source());
        assertEquals("DEMO-ALPHA", view.symbol());
        assertTrue(view.synthetic());
    }
}
