package com.algotradehub.backend.broker;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/v1/brokers")
public class BrokerController {
    @GetMapping("/adapters")
    public List<AdapterView> adapters() {
        return List.of(new AdapterView("MOCK", true, false, "Synthetic instruments only"),
                new AdapterView("ZERODHA", false, false, "Adapter boundary only; no credentials or live API calls in Phase 1"));
    }
    public record AdapterView(String provider, boolean availableForOrders, boolean supportsLiveExecution, String note) { }
}