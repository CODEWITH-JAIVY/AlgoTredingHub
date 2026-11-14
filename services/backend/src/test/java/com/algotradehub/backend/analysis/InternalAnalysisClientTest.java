package com.algotradehub.backend.analysis;

import com.algotradehub.backend.common.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InternalAnalysisClientTest {
    private static final String TOKEN = "internal-test-token-0123456789abcdef";
    private static final String RESPONSE = "{\"instrument_id\":\"7b774ec2-a516-4a29-b2b4-3945256c7c2a\","
            + "\"requested_timeframe\":\"5m\",\"data_timestamp\":\"2000-01-03T05:40:00Z\","
            + "\"source_id\":\"SYNTHETIC_DEMO\",\"freshness\":\"STALE\",\"complete\":true,"
            + "\"signal_status\":\"SUPPRESSED\",\"actionable\":false,\"bias\":\"NEUTRAL\","
            + "\"algorithm_version\":\"test-v1\",\"warnings\":[],\"zones\":[],"
            + "\"indicators\":{\"ema20\":101.25,\"ema50\":null,\"ema200\":null,\"adx14\":12.5,"
            + "\"rsi14\":29.5,\"macd\":0.25,\"macd_signal\":0.2,\"macd_histogram\":0.05,"
            + "\"atr14\":1.2,\"volume_average20\":1000,\"latest_volume_ratio\":1.75},"
            + "\"summary\":{\"english\":[\"Synthetic data.\"],\"hindi\":[\"सिंथेटिक डेटा।\"]}}";

    @Test
    void sendsCredentialsOnlyToTheInternalServiceAndForwardsCorrelationId() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> correlation = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/analyze", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            correlation.set(exchange.getRequestHeaders().getFirst("X-Correlation-ID"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            InternalAnalysisClient client = new InternalAnalysisClient(new ObjectMapper(), baseUrl, TOKEN);
            AnalysisEngineResponse result = client.analyze(Map.of("requested_timeframe", "5m"), "corr-test-1");

            assertEquals("Bearer " + TOKEN, authorization.get());
            assertEquals("corr-test-1", correlation.get());
            org.junit.jupiter.api.Assertions.assertTrue(body.get().contains("requested_timeframe"));
            assertEquals("SYNTHETIC_DEMO", result.sourceId());
            assertEquals("SUPPRESSED", result.signalStatus());
            assertEquals(new java.math.BigDecimal("29.5"), result.indicators().rsi14());
            assertEquals(new java.math.BigDecimal("1.75"), result.indicators().latestVolumeRatio());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingInternalConfigurationFailsClosedWithoutARequest() {
        InternalAnalysisClient client = new InternalAnalysisClient(new ObjectMapper(), "", "");

        ApiException error = assertThrows(ApiException.class,
                () -> client.analyze(Map.of(), "corr-test-2"));

        assertEquals("ANALYSIS_UNAVAILABLE", error.code());
    }

    @Test
    void missingOrNonNumericIndicatorFieldsAreNotTreatedAsValidWarmupNulls() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Object missing = mapper.readValue(RESPONSE.replace("\"rsi14\":29.5,", ""), Object.class);
        Object stringValue = mapper.readValue(RESPONSE.replace("\"rsi14\":29.5", "\"rsi14\":\"29.5\""), Object.class);

        assertThrows(IllegalArgumentException.class, () -> AnalysisEngineResponse.from(missing));
        assertThrows(IllegalArgumentException.class, () -> AnalysisEngineResponse.from(stringValue));
    }
}
