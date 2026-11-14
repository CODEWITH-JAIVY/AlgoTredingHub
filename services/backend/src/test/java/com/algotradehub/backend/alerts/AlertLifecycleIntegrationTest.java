package com.algotradehub.backend.alerts;

import com.algotradehub.backend.auth.AuthDtos;
import com.algotradehub.backend.auth.AuthService;
import com.algotradehub.backend.orders.Instrument;
import com.algotradehub.backend.orders.InstrumentRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.registration.single-user-mode=false")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AlertLifecycleIntegrationTest {
    @Autowired private AuthService auth;
    @Autowired private InstrumentRepository instruments;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private ObjectMapper objectMapper;
    @Value("${local.server.port}") private int port;

    @Test void priceRulesPersistPerUserAndRemainPausedUntilRightsAreConfirmed() throws Exception {
        assertEquals(401, send("GET", "/api/v1/alerts", null).statusCode());
        AuthDtos.TokenResponse owner = auth.signup(new AuthDtos.SignupRequest("alerts-owner@example.test", "StrongPassword123!"),
                "corr-alert-owner-signup");
        Instrument instrument = instruments.findByExchangeAndProviderToken("NSE", "DEMO-NSE-CASH-001").orElseThrow();
        String createBody = objectMapper.writeValueAsString(new AlertRuleRequest(instrument.getId(),
                AlertRuleRequest.Direction.ABOVE, new java.math.BigDecimal("123.45")));

        HttpResponse<String> created = send("POST", "/api/v1/alerts", owner.accessToken(), createBody);
        assertEquals(201, created.statusCode());
        JsonNode rule = objectMapper.readTree(created.body());
        assertEquals("PAUSED", rule.path("state").asText());
        assertEquals("LICENSED_SOURCE_NOT_CONFIRMED", rule.path("reasonCode").asText());

        String zoneBody = "{\"instrumentId\":\"" + instrument.getId()
                + "\",\"ruleType\":\"ZONE_TOUCH\",\"price\":123.45,\"upperPrice\":124.50}";
        HttpResponse<String> zoneCreated = send("POST", "/api/v1/alerts", owner.accessToken(), zoneBody);
        assertEquals(201, zoneCreated.statusCode());
        JsonNode zoneRule = objectMapper.readTree(zoneCreated.body());
        assertEquals("ZONE_TOUCH", zoneRule.path("ruleType").asText());
        assertTrue(!zoneRule.has("direction"));
        assertEquals(124.50, zoneRule.path("upperPrice").asDouble());

        String volumeBody = "{\"instrumentId\":\"" + instrument.getId()
                + "\",\"ruleType\":\"VOLUME_SPIKE\"}";
        HttpResponse<String> volumeCreated = send("POST", "/api/v1/alerts", owner.accessToken(), volumeBody);
        assertEquals(201, volumeCreated.statusCode());
        JsonNode volumeRule = objectMapper.readTree(volumeCreated.body());
        assertEquals("VOLUME_SPIKE", volumeRule.path("ruleType").asText());
        assertTrue(!volumeRule.has("price") || volumeRule.path("price").isNull());
        assertEquals(2.0, volumeRule.path("volumeMultiplier").asDouble());
        assertEquals(20, volumeRule.path("lookbackCandles").asInt());

        String legacyPriceBody = "{\"instrumentId\":\"" + instrument.getId()
                + "\",\"direction\":\"BELOW\",\"price\":122.50}";
        HttpResponse<String> legacyPriceCreated = send("POST", "/api/v1/alerts", owner.accessToken(), legacyPriceBody);
        assertEquals(201, legacyPriceCreated.statusCode());
        JsonNode legacyRule = objectMapper.readTree(legacyPriceCreated.body());
        assertEquals("PRICE_CROSSING", legacyRule.path("ruleType").asText());

        String otherUserToken = tokenFor(UUID.randomUUID(), "other@example.test");
        HttpResponse<String> ownerList = send("GET", "/api/v1/alerts", owner.accessToken());
        HttpResponse<String> otherList = send("GET", "/api/v1/alerts", otherUserToken);
        HttpResponse<String> otherDelete = send("DELETE", "/api/v1/alerts/" + rule.path("id").asText(), otherUserToken);
        HttpResponse<String> invalidPrice = send("POST", "/api/v1/alerts", owner.accessToken(),
                "{\"instrumentId\":\"" + instrument.getId() + "\",\"direction\":\"ABOVE\",\"price\":0}");
        HttpResponse<String> invalidZone = send("POST", "/api/v1/alerts", owner.accessToken(),
                "{\"instrumentId\":\"" + instrument.getId()
                        + "\",\"ruleType\":\"ZONE_TOUCH\",\"price\":124.50,\"upperPrice\":123.45}");
        HttpResponse<String> contradictoryZone = send("POST", "/api/v1/alerts", owner.accessToken(),
                "{\"instrumentId\":\"" + instrument.getId()
                        + "\",\"ruleType\":\"ZONE_TOUCH\",\"direction\":\"ABOVE\",\"price\":123.45,\"upperPrice\":124.50}");
        HttpResponse<String> contradictoryVolume = send("POST", "/api/v1/alerts", owner.accessToken(),
                "{\"instrumentId\":\"" + instrument.getId()
                        + "\",\"ruleType\":\"VOLUME_SPIKE\",\"price\":123.45}");
        HttpResponse<String> excessiveVolumeMultiplier = send("POST", "/api/v1/alerts", owner.accessToken(),
                "{\"instrumentId\":\"" + instrument.getId()
                        + "\",\"ruleType\":\"VOLUME_SPIKE\",\"volumeMultiplier\":10.01}");
        HttpResponse<String> events = send("GET", "/api/v1/alerts/events?limit=10000", owner.accessToken());
        HttpResponse<String> deleted = send("DELETE", "/api/v1/alerts/" + rule.path("id").asText(), owner.accessToken());
        HttpResponse<String> deletedZone = send("DELETE", "/api/v1/alerts/" + zoneRule.path("id").asText(), owner.accessToken());
        HttpResponse<String> deletedVolume = send("DELETE", "/api/v1/alerts/" + volumeRule.path("id").asText(), owner.accessToken());
        HttpResponse<String> deletedLegacy = send("DELETE", "/api/v1/alerts/" + legacyRule.path("id").asText(), owner.accessToken());
        HttpResponse<String> ownerListAfterDelete = send("GET", "/api/v1/alerts", owner.accessToken());

        assertEquals(4, objectMapper.readTree(ownerList.body()).size());
        assertEquals(0, objectMapper.readTree(otherList.body()).size());
        assertEquals(404, otherDelete.statusCode());
        assertEquals(400, invalidPrice.statusCode());
        assertEquals(400, invalidZone.statusCode());
        assertEquals(400, contradictoryZone.statusCode());
        assertEquals(400, contradictoryVolume.statusCode());
        assertEquals(400, excessiveVolumeMultiplier.statusCode());
        assertEquals(200, events.statusCode());
        assertTrue(objectMapper.readTree(events.body()).isArray());
        assertEquals(204, deleted.statusCode());
        assertEquals(204, deletedZone.statusCode());
        assertEquals(204, deletedVolume.statusCode());
        assertEquals(204, deletedLegacy.statusCode());
        assertEquals(0, objectMapper.readTree(ownerListAfterDelete.body()).size());
    }

    private HttpResponse<String> send(String method, String path, String accessToken) throws IOException, InterruptedException {
        return send(method, path, accessToken, null);
    }

    private HttpResponse<String> send(String method, String path, String accessToken, String body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (accessToken != null) builder.header("Authorization", "Bearer " + accessToken);
        if (body != null) builder.header("Content-Type", "application/json");
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = builder.method(method, publisher).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String tokenFor(UUID userId, String email) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("algotrade-hub").subject(email)
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("uid", userId.toString()).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
