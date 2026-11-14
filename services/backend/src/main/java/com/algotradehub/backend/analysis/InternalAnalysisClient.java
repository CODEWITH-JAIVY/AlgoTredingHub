package com.algotradehub.backend.analysis;

import com.algotradehub.backend.common.ApiException;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class InternalAnalysisClient implements AnalysisClient {
    private static final int MINIMUM_TOKEN_LENGTH = 32;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String internalToken;

    public InternalAnalysisClient(ObjectMapper objectMapper,
                                  @Value("${app.analysis.base-url:}") String baseUrl,
                                  @Value("${app.analysis.internal-token:}") String internalToken) {
        this.objectMapper = objectMapper;
        this.baseUrl = Objects.requireNonNullElse(baseUrl, "").trim();
        this.internalToken = Objects.requireNonNullElse(internalToken, "");
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    }

    @Override
    public AnalysisEngineResponse analyze(Map<String, Object> payload, String correlationId) {
        if (baseUrl.isBlank() || internalToken.length() < MINIMUM_TOKEN_LENGTH) throw unavailable();
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (RuntimeException exception) {
            throw unavailable();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/v1/analyze"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + internalToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-Correlation-ID", correlationId)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw unavailable();
            Object decoded = objectMapper.readValue(response.body(), Object.class);
            AnalysisEngineResponse result = AnalysisEngineResponse.from(decoded);
            if (result == null || result.instrumentId() == null || result.requestedTimeframe() == null
                    || result.dataTimestamp() == null || result.sourceId() == null || result.freshness() == null
                    || result.signalStatus() == null || result.bias() == null || result.summary() == null) throw unavailable();
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | RuntimeException exception) {
            throw unavailable();
        }
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ANALYSIS_UNAVAILABLE",
                "Analysis is temporarily unavailable. Try again later.");
    }
}
