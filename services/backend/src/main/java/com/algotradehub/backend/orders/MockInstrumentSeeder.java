package com.algotradehub.backend.orders;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import com.algotradehub.backend.marketdata.InstrumentMasterVersionEntity;
import com.algotradehub.backend.marketdata.InstrumentMasterVersionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Component
public class MockInstrumentSeeder implements CommandLineRunner {
    private final InstrumentRepository instruments;
    private final InstrumentMasterVersionRepository masterVersions;
    public MockInstrumentSeeder(InstrumentRepository instruments, InstrumentMasterVersionRepository masterVersions) {
        this.instruments = instruments;
        this.masterVersions = masterVersions;
    }
    @Override public void run(String... args) {
        if (instruments.count() > 0) return;
        Instant now = Instant.now();
        masterVersions.findById("DEMO-1").orElseGet(() -> masterVersions.save(new InstrumentMasterVersionEntity(
                "DEMO-1", "SYNTHETIC_DEMO", now, now, demoChecksum(), true, "CURRENT")));
        instruments.saveAll(List.of(
                new Instrument(UUID.randomUUID(), "DEMO-NSE-CASH-001", "NSE", "CASH", "DEMO-RELIANCE", "EQUITY", 1, new BigDecimal("0.05"), new BigDecimal("100.00"), "DEMO-1"),
                new Instrument(UUID.randomUUID(), "DEMO-BSE-CASH-001", "BSE", "CASH", "DEMO-TCS", "EQUITY", 1, new BigDecimal("0.05"), new BigDecimal("250.00"), "DEMO-1"),
                new Instrument(UUID.randomUUID(), "DEMO-NSE-FO-001", "NSE", "FNO", "DEMO-NIFTY-FUT", "FUTURE", 25, new BigDecimal("0.05"), new BigDecimal("1000.00"), "DEMO-1"),
                new Instrument(UUID.randomUUID(), "DEMO-BSE-FO-001", "BSE", "FNO", "DEMO-SENSEX-FUT", "FUTURE", 10, new BigDecimal("0.05"), new BigDecimal("1000.00"), "DEMO-1")
        ));
    }

    private String demoChecksum() {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest("SYNTHETIC_DEMO:DEMO-1:fixture-v1".getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", impossible);
        }
    }
}
