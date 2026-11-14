package com.algotradehub.backend.marketdata;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** Fixed, reproducible chart fixture. Its 2000 date and SIMULATED label prevent it looking like current quotes. */
@Component
public class SyntheticDemoHistoryProvider {
    public static final LocalDate DEMO_DATE = LocalDate.of(2000, 1, 3);
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final int SAMPLE_MINUTES = 120;

    public List<CandleBar> samples(UUID instrumentId, BigDecimal basePrice, BigDecimal tickSize, String masterVersion) {
        Random random = new Random(instrumentId.getMostSignificantBits() ^ instrumentId.getLeastSignificantBits());
        List<CandleBar> output = new ArrayList<>(SAMPLE_MINUTES);
        BigDecimal last = basePrice;
        Instant finalizedAt = DEMO_DATE.atTime(11, 17).atZone(ZONE).toInstant();
        for (int i = 0; i < SAMPLE_MINUTES; i++) {
            long deltaTicks = random.nextInt(7) - 3;
            BigDecimal open = last;
            BigDecimal close = open.add(tickSize.multiply(BigDecimal.valueOf(deltaTicks))).max(tickSize);
            BigDecimal high = open.max(close).add(tickSize.multiply(BigDecimal.valueOf(random.nextInt(4))));
            BigDecimal low = open.min(close).subtract(tickSize.multiply(BigDecimal.valueOf(random.nextInt(4)))).max(tickSize);
            Instant bucket = DEMO_DATE.atTime(LocalTime.of(9, 15)).plusMinutes(i).atZone(ZONE).toInstant();
            output.add(new CandleBar(instrumentId, CandleAggregator.SUPPORTED_TIMEFRAME_SECONDS, bucket,
                    round(open, tickSize), round(high, tickSize), round(low, tickSize), round(close, tickSize),
                    10 + random.nextInt(9_990), 1 + random.nextInt(75), "SYNTHETIC_DEMO", "SYNTHETIC",
                    masterVersion, true, finalizedAt));
            last = close;
        }
        return List.copyOf(output);
    }

    private BigDecimal round(BigDecimal value, BigDecimal tickSize) {
        return value.divide(tickSize, 0, RoundingMode.HALF_UP).multiply(tickSize).setScale(tickSize.scale(), RoundingMode.UNNECESSARY);
    }
}
