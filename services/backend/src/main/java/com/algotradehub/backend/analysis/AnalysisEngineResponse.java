package com.algotradehub.backend.analysis;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AnalysisEngineResponse(UUID instrumentId, String requestedTimeframe, Instant dataTimestamp,
                                     String sourceId, String freshness, boolean complete, String signalStatus,
                                     boolean actionable, String bias, String algorithmVersion, List<String> warnings,
                                     List<Object> zones, Indicators indicators, Summary summary) {
    public static AnalysisEngineResponse from(Object body) {
        if (!(body instanceof Map<?, ?> values)) throw new IllegalArgumentException("Invalid analysis response.");
        Object summaryValue = values.get("summary");
        if (!(summaryValue instanceof Map<?, ?> summaryMap)) throw new IllegalArgumentException("Invalid analysis summary.");
        return new AnalysisEngineResponse(
                UUID.fromString(stringValue(values, "instrument_id")),
                stringValue(values, "requested_timeframe"),
                Instant.parse(stringValue(values, "data_timestamp")),
                stringValue(values, "source_id"),
                stringValue(values, "freshness"),
                booleanValue(values, "complete"),
                stringValue(values, "signal_status"),
                booleanValue(values, "actionable"),
                stringValue(values, "bias"),
                stringValue(values, "algorithm_version"),
                stringList(values.get("warnings")),
                objectList(values.get("zones")),
                indicators(values.get("indicators")),
                new Summary(stringList(summaryMap.get("english")), stringList(summaryMap.get("hindi"))));
    }

    private static Indicators indicators(Object value) {
        if (!(value instanceof Map<?, ?> fields)) throw new IllegalArgumentException("Invalid analysis indicators.");
        return new Indicators(decimalValue(fields, "ema20"), decimalValue(fields, "ema50"),
                decimalValue(fields, "ema200"), decimalValue(fields, "adx14"), decimalValue(fields, "rsi14"),
                decimalValue(fields, "macd"), decimalValue(fields, "macd_signal"),
                decimalValue(fields, "macd_histogram"), decimalValue(fields, "atr14"),
                decimalValue(fields, "volume_average20"), decimalValue(fields, "latest_volume_ratio"));
    }

    private static BigDecimal decimalValue(Map<?, ?> fields, String key) {
        if (!fields.containsKey(key)) throw new IllegalArgumentException("Missing analysis indicator value.");
        Object value = fields.get(key);
        if (value == null) return null;
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Invalid analysis indicator value.");
        double finiteCheck = number.doubleValue();
        if (!Double.isFinite(finiteCheck)) throw new IllegalArgumentException("Invalid analysis indicator value.");
        if (number instanceof BigDecimal decimal) return decimal;
        return BigDecimal.valueOf(finiteCheck);
    }

    private static String stringValue(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Invalid analysis response.");
        return text;
    }

    private static boolean booleanValue(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Boolean result)) throw new IllegalArgumentException("Invalid analysis response.");
        return result;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> items)) throw new IllegalArgumentException("Invalid analysis response.");
        return items.stream().map(item -> {
            if (!(item instanceof String text)) throw new IllegalArgumentException("Invalid analysis response.");
            return text;
        }).toList();
    }

    private static List<Object> objectList(Object value) {
        if (!(value instanceof List<?> items)) throw new IllegalArgumentException("Invalid analysis response.");
        return List.copyOf(items);
    }

    public record Summary(List<String> english, List<String> hindi) { }
    public record Indicators(BigDecimal ema20, BigDecimal ema50, BigDecimal ema200, BigDecimal adx14,
                             BigDecimal rsi14, BigDecimal macd, BigDecimal macdSignal,
                             BigDecimal macdHistogram, BigDecimal atr14, BigDecimal volumeAverage20,
                             BigDecimal latestVolumeRatio) { }
}
