package com.algotradehub.backend.analysis;

import java.time.Duration;

public enum AnalysisTimeframe {
    M5("5m", 300, 5),
    M15("15m", 900, 15),
    H1("1h", 3_600, 60),
    D1("1d", 86_400, 375);

    private final String code;
    private final int seconds;
    private final int minutes;

    AnalysisTimeframe(String code, int seconds, int minutes) {
        this.code = code;
        this.seconds = seconds;
        this.minutes = minutes;
    }

    public String code() { return code; }
    public int seconds() { return seconds; }
    public int minutes() { return minutes; }
    public Duration duration() { return Duration.ofSeconds(seconds); }

    public static AnalysisTimeframe fromCode(String code) {
        for (AnalysisTimeframe timeframe : values()) {
            if (timeframe.code.equals(code)) return timeframe;
        }
        throw new IllegalArgumentException("Unsupported timeframe.");
    }
}
