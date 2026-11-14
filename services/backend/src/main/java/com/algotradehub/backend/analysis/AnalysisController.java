package com.algotradehub.backend.analysis;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisController {
    private final AnalysisService service;

    public AnalysisController(AnalysisService service) { this.service = service; }

    @GetMapping("/{instrumentId}")
    public AnalysisService.AnalysisView analyze(@PathVariable UUID instrumentId,
                                                @RequestParam(defaultValue = "5m") String timeframe,
                                                @RequestParam Instant from,
                                                @RequestParam Instant to,
                                                @RequestParam String source,
                                                HttpServletRequest request) {
        Object correlationId = request.getAttribute("correlationId");
        String correlation = "";
        if (correlationId instanceof String value) correlation = value;
        return service.analyze(instrumentId, timeframe, from, to, source, correlation);
    }
}
