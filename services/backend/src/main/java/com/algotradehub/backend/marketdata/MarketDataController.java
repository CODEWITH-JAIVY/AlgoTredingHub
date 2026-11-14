package com.algotradehub.backend.marketdata;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/market")
public class MarketDataController {
    private final MarketCandleService candles;
    public MarketDataController(MarketCandleService candles) { this.candles = candles; }

    @GetMapping("/candles")
    public MarketCandleService.CandleSeries candles(@RequestParam UUID instrumentId,
                                                    @RequestParam(defaultValue = "1m") String timeframe,
                                                    @RequestParam Instant from,
                                                    @RequestParam Instant to,
                                                    @RequestParam(required = false) String source) {
        return candles.query(instrumentId, timeframe, from, to, source);
    }

    @GetMapping("/data-status")
    public DataStatus dataStatus() {
        Instant sampleStart = SyntheticDemoHistoryProvider.DEMO_DATE.atTime(LocalTime.of(9, 15))
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
        return new DataStatus(false, "No licensed market-data provider is configured.", sampleStart,
                sampleStart.plusSeconds(120 * 60), List.of(new SourceStatus("SYNTHETIC_DEMO", true, true, false, false)));
    }

    public record DataStatus(boolean licensedSourceConfigured, String statusMessage, Instant demoRangeStart,
                             Instant demoRangeEnd, List<SourceStatus> sources) { }
    public record SourceStatus(String id, boolean enabled, boolean synthetic, boolean usageRightsConfirmed, boolean live) { }
}
