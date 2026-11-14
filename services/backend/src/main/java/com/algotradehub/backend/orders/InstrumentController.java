package com.algotradehub.backend.orders;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/market")
public class InstrumentController {
    private final InstrumentRepository instruments;
    public InstrumentController(InstrumentRepository instruments) { this.instruments = instruments; }

    @GetMapping("/instruments")
    public List<InstrumentView> instruments() {
        return instruments.findByActiveTrueOrderByExchangeAscSymbolAsc().stream()
                .map(i -> new InstrumentView(i.getId(), i.getExchange(), i.getSegment(), i.getSymbol(), i.getInstrumentType(),
                        i.getLotSize(), i.getTickSize(), i.getMasterVersion(), "SYNTHETIC_DEMO", true, i.getUpdatedAt()))
                .toList();
    }
    @GetMapping("/quotes")
    public List<QuoteView> quotes() {
        return instruments.findByActiveTrueOrderByExchangeAscSymbolAsc().stream()
                .map(i -> new QuoteView(i.getId(), i.getExchange(), i.getSymbol(), i.getMockPrice(), "SYNTHETIC_DEMO", true,
                        "SIMULATED", false, null))
                .toList();
    }
    public record InstrumentView(UUID id, String exchange, String segment, String symbol, String instrumentType,
                                 int lotSize, BigDecimal tickSize, String masterVersion, String source,
                                 boolean synthetic, Instant updatedAt) { }
    public record QuoteView(UUID instrumentId, String exchange, String symbol, BigDecimal price, String source,
                            boolean synthetic, String freshness, boolean fresh, Instant asOf) { }
}
