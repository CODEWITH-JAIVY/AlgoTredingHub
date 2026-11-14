package com.algotradehub.backend.auth;

import com.algotradehub.backend.common.ApiException;
import com.algotradehub.backend.orders.Instrument;
import com.algotradehub.backend.orders.InstrumentRepository;
import com.algotradehub.backend.orders.OrderRequest;
import com.algotradehub.backend.orders.OrderEntity;
import com.algotradehub.backend.orders.OrderRepository;
import com.algotradehub.backend.orders.OrderService;
import com.algotradehub.backend.orders.OrderSide;
import com.algotradehub.backend.orders.OrderType;
import com.algotradehub.backend.marketdata.NseBseEquityFnoDemoCalendar;
import com.algotradehub.backend.risk.RiskPolicy;
import com.algotradehub.backend.risk.RiskPolicyRepository;
import com.algotradehub.backend.risk.DailyRiskState;
import com.algotradehub.backend.risk.DailyRiskStateRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Locale;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(AuthLifecycleIntegrationTest.TradingSessionClockConfiguration.class)
class AuthLifecycleIntegrationTest {
    private static final LocalDate TEST_SESSION_DATE = LocalDate.of(2026, 10, 21);
    @Autowired private AuthService auth;
    @Autowired private UserAccountRepository users;
    @Autowired private OrderService orders;
    @Autowired private OrderRepository orderRepository;
    @Autowired private InstrumentRepository instruments;
    @Autowired private RiskPolicyRepository policies;
    @Autowired private DailyRiskStateRepository dailyRiskStates;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private ObjectMapper objectMapper;
    @Value("${local.server.port}") private int port;

    @TestConfiguration
    static class TradingSessionClockConfiguration {
        @Bean
        @Primary
        Clock tradingSessionClock() {
            ZoneId exchangeZone = ZoneId.of("Asia/Kolkata");
            Instant instant = TEST_SESSION_DATE.atTime(10, 0).atZone(exchangeZone).toInstant();
            return Clock.fixed(instant, ZoneOffset.UTC);
        }
    }

    @Test @Transactional void refreshReplayRevokesThePersistedSuccessorBeforeReturningUnauthorized() {
        AuthDtos.TokenResponse initial = auth.signup(
                new AuthDtos.SignupRequest("refresh-replay@example.test", "StrongPassword123!"), "corr-integration-signup");
        AuthDtos.TokenResponse successor = auth.refresh(
                new AuthDtos.RefreshRequest(initial.refreshToken()), "corr-integration-rotate");
        assertNotEquals(initial.refreshToken(), successor.refreshToken());

        ApiException replay = assertThrows(ApiException.class, () -> auth.refresh(
                new AuthDtos.RefreshRequest(initial.refreshToken()), "corr-integration-replay"));
        assertEquals("INVALID_REFRESH_TOKEN", replay.code());

        ApiException revokedSuccessor = assertThrows(ApiException.class, () -> auth.refresh(
                new AuthDtos.RefreshRequest(successor.refreshToken()), "corr-integration-successor"));
        assertEquals("INVALID_REFRESH_TOKEN", revokedSuccessor.code());
    }

    @Test
    @DisplayName("order, broker, and risk APIs reject requests without a bearer token")
    void privateApiRoutesRequireAuthentication() throws IOException, InterruptedException {
        assertEquals(401, send("GET", "/api/v1/orders", null, null).statusCode());
        assertEquals(401, send("GET", "/api/v1/brokers/adapters", null, null).statusCode());
        assertEquals(401, send("GET", "/api/v1/risk/policy", null, null).statusCode());
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @DisplayName("an account cannot list or cancel another account's order")
    void orderReadsAndCancellationAreScopedToTheAuthenticatedAccount() throws Exception {
        AuthDtos.TokenResponse owner = auth.signup(
                new AuthDtos.SignupRequest("order-owner@example.test", "StrongPassword123!"),
                "corr-order-owner-signup");
        DailyRiskState dailyState = dailyRiskStates.findById(owner.userId()).orElseThrow();
        dailyState.recordAcceptedOrderEntry();
        dailyRiskStates.saveAndFlush(dailyState);
        Instrument instrument = instruments.findByExchangeAndProviderToken("NSE", "DEMO-NSE-CASH-001").orElseThrow();
        OrderRequest request = new OrderRequest("owner-order-1", instrument.getId(), OrderSide.BUY,
                OrderType.MARKET, 1, null, null, new BigDecimal("90.00"));
        OrderEntity ownedOrder = orderRepository.saveAndFlush(new OrderEntity(UUID.randomUUID(), owner.userId(),
                request.clientOrderId(), request, "OPEN", "mock-owner-order-1", new BigDecimal("10.00")));
        String otherAccountToken = tokenFor(UUID.randomUUID(), "other-account@example.test");

        HttpResponse<String> ownerList = sendAuthorizedGet("/api/v1/orders", owner.accessToken());
        HttpResponse<String> otherAccountList = sendAuthorizedGet("/api/v1/orders", otherAccountToken);
        HttpResponse<String> unauthorizedCancel = sendAuthorized("POST",
                "/api/v1/orders/" + ownedOrder.getId() + "/cancel", otherAccountToken, "{}");
        HttpResponse<String> ownerCancel = sendAuthorized("POST",
                "/api/v1/orders/" + ownedOrder.getId() + "/cancel", owner.accessToken(), "{}");

        assertEquals(200, ownerList.statusCode());
        assertEquals(1, objectMapper.readTree(ownerList.body()).size());
        assertEquals(200, otherAccountList.statusCode());
        assertEquals(0, objectMapper.readTree(otherAccountList.body()).size());
        assertEquals(404, unauthorizedCancel.statusCode());
        assertEquals(200, ownerCancel.statusCode());
        assertEquals("CANCELLED", objectMapper.readTree(ownerCancel.body()).path("status").asText());
        assertEquals(1, dailyRiskStates.findById(owner.userId()).orElseThrow().getAcceptedOrderEntries());
    }

    @Test
    @DisplayName("CORS accepts an explicitly configured origin and rejects an unlisted origin")
    void corsUsesAnExactOriginAllowlist() throws IOException, InterruptedException {
        HttpResponse<String> allowed = send("OPTIONS", "/api/v1/orders", "http://localhost:3000", "GET");
        assertEquals(200, allowed.statusCode());
        assertEquals("http://localhost:3000", allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertTrue(allowed.headers().firstValue("Access-Control-Allow-Headers")
                .orElse("").toLowerCase(Locale.ROOT).contains("authorization"));

        HttpResponse<String> denied = send("OPTIONS", "/api/v1/orders", "https://unlisted.example", "GET");
        assertEquals(403, denied.statusCode());
        assertNull(denied.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
    }

    @Test
    @DisplayName("the actuator info endpoint remains public")
    void actuatorInfoIsPublic() throws IOException, InterruptedException {
        assertEquals(200, send("GET", "/actuator/info", null, null).statusCode());
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @DisplayName("simultaneous first signups create exactly one account in single-user mode")
    void simultaneousInitialSignupsAreSerialized() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> attemptSignup(
                    "race-first@example.test", ready, start));
            Future<Boolean> second = executor.submit(() -> attemptSignup(
                    "race-second@example.test", ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            boolean firstAccepted = first.get(15, TimeUnit.SECONDS);
            boolean secondAccepted = second.get(15, TimeUnit.SECONDS);
            assertNotEquals(firstAccepted, secondAccepted);
            assertEquals(1L, users.count());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @DisplayName("simultaneous orders cannot exceed the account open-order limit")
    void simultaneousOrdersRespectOpenOrderLimit() throws Exception {
        UUID userId = auth.signup(
                new AuthDtos.SignupRequest("order-race@example.test", "StrongPassword123!"),
                "corr-order-race-signup").userId();
        RiskPolicy policy = policies.findById(userId).orElseThrow();
        LocalDate today = TEST_SESSION_DATE;
        policy.update(100, new BigDecimal("100000.00"), 1,
                new BigDecimal("5000.00"), new BigDecimal("1000.00"), 10,
                LocalTime.of(15, 0), 5, today, today.plusDays(1));
        policies.save(policy);
        Instrument instrument = instruments.findByExchangeAndProviderToken("NSE", "DEMO-NSE-CASH-001").orElseThrow();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> attemptOrder(
                    userId, instrument, "order-race-first", ready, start));
            Future<String> second = executor.submit(() -> attemptOrder(
                    userId, instrument, "order-race-second", ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            String firstResult = first.get(15, TimeUnit.SECONDS);
            String secondResult = second.get(15, TimeUnit.SECONDS);
            assertNotEquals(firstResult, secondResult);
            assertTrue(List.of(firstResult, secondResult).contains("ACCEPTED"));
            assertTrue(List.of(firstResult, secondResult).contains("MAX_OPEN_ORDERS"));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @DisplayName("simultaneous orders cannot exceed the daily accepted-entry limit")
    void simultaneousOrdersRespectDailyOrderEntryLimit() throws Exception {
        UUID userId = auth.signup(
                new AuthDtos.SignupRequest("daily-entry-race@example.test", "StrongPassword123!"),
                "corr-daily-entry-race-signup").userId();
        RiskPolicy policy = policies.findById(userId).orElseThrow();
        LocalDate today = TEST_SESSION_DATE;
        policy.update(100, new BigDecimal("100000.00"), 5,
                new BigDecimal("5000.00"), new BigDecimal("1000.00"), 1,
                LocalTime.of(15, 0), 5, today, today.plusDays(1));
        policies.save(policy);
        Instrument instrument = instruments.findByExchangeAndProviderToken("NSE", "DEMO-NSE-CASH-001").orElseThrow();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> attemptOrder(
                    userId, instrument, "daily-entry-race-first", ready, start));
            Future<String> second = executor.submit(() -> attemptOrder(
                    userId, instrument, "daily-entry-race-second", ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            String firstResult = first.get(15, TimeUnit.SECONDS);
            String secondResult = second.get(15, TimeUnit.SECONDS);
            assertNotEquals(firstResult, secondResult);
            assertTrue(List.of(firstResult, secondResult).contains("ACCEPTED"));
            assertTrue(List.of(firstResult, secondResult).contains("MAX_DAILY_ORDER_ENTRIES"));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @DisplayName("risk-policy HTTP API returns active limits separately from scheduled relaxations")
    void riskPolicyHttpApiKeepsTighterValuesActive() throws Exception {
        AuthDtos.TokenResponse tokens = auth.signup(
                new AuthDtos.SignupRequest("risk-policy@example.test", "StrongPassword123!"),
                "corr-risk-policy-signup");
        String body = "{\"maxQuantity\":50,\"maxOrderNotional\":\"200000.00\",\"maxOpenOrders\":10,"
                + "\"maxDailyLoss\":\"4000.00\",\"maxTradeLoss\":\"1500.00\",\"maxOrdersPerDay\":10,"
                + "\"noNewOrdersAfter\":\"15:00\",\"noOrdersFirstMinutes\":5}";
        HttpRequest update = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/risk/policy"))
                .header("Authorization", "Bearer " + tokens.accessToken())
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();

        HttpResponse<String> updated = HttpClient.newHttpClient().send(update, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, updated.statusCode());
        JsonNode updateJson = objectMapper.readTree(updated.body());
        assertEquals(50, updateJson.path("maxQuantity").asInt());
        assertEquals(5, updateJson.path("maxOpenOrders").asInt());
        assertEquals(0, updateJson.path("maxOrderNotional").decimalValue().compareTo(new BigDecimal("100000.00")));
        assertEquals(4000, updateJson.path("maxDailyLoss").decimalValue().intValueExact());
        assertEquals(200000, updateJson.path("pending").path("maxOrderNotional").decimalValue().intValueExact());
        assertEquals(10, updateJson.path("pending").path("maxOpenOrders").asInt());
        assertEquals(1500, updateJson.path("pending").path("maxTradeLoss").decimalValue().intValueExact());
        assertEquals(new NseBseEquityFnoDemoCalendar().nextRegularTradingDate(
                TEST_SESSION_DATE).orElseThrow().toString(), updateJson.path("pending").path("effectiveOn").asText());

        HttpRequest read = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/risk/policy"))
                .header("Authorization", "Bearer " + tokens.accessToken()).GET().build();
        HttpResponse<String> fetched = HttpClient.newHttpClient().send(read, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, fetched.statusCode());
        JsonNode fetchedJson = objectMapper.readTree(fetched.body());
        assertEquals(50, fetchedJson.path("maxQuantity").asInt());
        assertEquals(10, fetchedJson.path("pending").path("maxOpenOrders").asInt());

        String missingLoss = "{\"maxQuantity\":50,\"maxOrderNotional\":\"200000.00\",\"maxOpenOrders\":10,"
                + "\"maxDailyLoss\":null,\"maxTradeLoss\":\"1500.00\",\"maxOrdersPerDay\":10,"
                + "\"noNewOrdersAfter\":\"15:00\",\"noOrdersFirstMinutes\":5}";
        HttpResponse<String> missingValue = sendAuthorized("PUT", "/api/v1/risk/policy", tokens.accessToken(), missingLoss);
        assertEquals(400, missingValue.statusCode());
        String oversizedValue = "{\"maxQuantity\":50,\"maxOrderNotional\":\"10000000000000000.00\",\"maxOpenOrders\":10,"
                + "\"maxDailyLoss\":\"4000.00\",\"maxTradeLoss\":\"1500.00\",\"maxOrdersPerDay\":10,"
                + "\"noNewOrdersAfter\":\"15:00\",\"noOrdersFirstMinutes\":5}";
        HttpResponse<String> oversized = sendAuthorized("PUT", "/api/v1/risk/policy", tokens.accessToken(), oversizedValue);
        assertEquals(400, oversized.statusCode());
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @DisplayName("mock order lifecycle is confirmed, cancelled by the kill switch, and blocked while active")
    void mockOrderLifecycleHonorsKillSwitchThroughHttpApi() throws Exception {
        AuthDtos.TokenResponse tokens = auth.signup(
                new AuthDtos.SignupRequest("kill-switch-e2e@example.test", "StrongPassword123!"),
                "corr-kill-switch-e2e-signup");
        Instrument instrument = instruments.findByExchangeAndProviderToken("NSE", "DEMO-NSE-CASH-001").orElseThrow();
        BigDecimal entryReference = instrument.getMockPrice();
        OrderRequest acceptedRequest = new OrderRequest("kill-switch-e2e-order", instrument.getId(),
                OrderSide.BUY, OrderType.MARKET, instrument.getLotSize(), null, null,
                entryReference.subtract(instrument.getTickSize()));

        HttpResponse<String> initialSwitch = sendAuthorizedGet("/api/v1/risk/kill-switch", tokens.accessToken());
        assertEquals(200, initialSwitch.statusCode());
        assertEquals(false, objectMapper.readTree(initialSwitch.body()).path("active").asBoolean());

        HttpResponse<String> placed = sendAuthorized("POST", "/api/v1/orders", tokens.accessToken(),
                objectMapper.writeValueAsString(acceptedRequest));
        assertEquals(200, placed.statusCode());
        JsonNode placedOrder = objectMapper.readTree(placed.body());
        assertEquals("OPEN", placedOrder.path("status").asText());
        assertEquals("MOCK", placedOrder.path("mode").asText());
        String orderId = placedOrder.path("id").asText();

        HttpResponse<String> activated = sendAuthorized("PUT", "/api/v1/risk/kill-switch",
                tokens.accessToken(), "{\"active\":true,\"reason\":\"mock E2E safety check\"}");
        assertEquals(200, activated.statusCode());
        JsonNode activation = objectMapper.readTree(activated.body());
        assertEquals(true, activation.path("active").asBoolean());
        assertEquals(1, activation.path("confirmedCancelled").asInt());
        assertEquals(0, activation.path("unresolvedOrders").asInt());

        HttpResponse<String> ordersAfterActivation = sendAuthorizedGet("/api/v1/orders", tokens.accessToken());
        assertEquals(200, ordersAfterActivation.statusCode());
        JsonNode savedOrders = objectMapper.readTree(ordersAfterActivation.body());
        assertEquals(1, savedOrders.size());
        assertEquals(orderId, savedOrders.get(0).path("id").asText());
        assertEquals("CANCELLED", savedOrders.get(0).path("status").asText());

        OrderRequest blockedRequest = new OrderRequest("kill-switch-blocked-order", instrument.getId(),
                OrderSide.BUY, OrderType.MARKET, instrument.getLotSize(), null, null,
                entryReference.subtract(instrument.getTickSize()));
        HttpResponse<String> blocked = sendAuthorized("POST", "/api/v1/orders", tokens.accessToken(),
                objectMapper.writeValueAsString(blockedRequest));
        assertEquals(422, blocked.statusCode());
        assertEquals("KILL_SWITCH_ACTIVE", objectMapper.readTree(blocked.body()).path("code").asText());

        HttpResponse<String> deactivated = sendAuthorized("PUT", "/api/v1/risk/kill-switch",
                tokens.accessToken(), "{\"active\":false}");
        assertEquals(200, deactivated.statusCode());
        JsonNode deactivation = objectMapper.readTree(deactivated.body());
        assertEquals(false, deactivation.path("active").asBoolean());
        assertEquals(0, deactivation.path("unresolvedOrders").asInt());
        assertEquals(1, dailyRiskStates.findById(tokens.userId()).orElseThrow().getAcceptedOrderEntries());

        HttpResponse<String> finalOrders = sendAuthorizedGet("/api/v1/orders", tokens.accessToken());
        assertEquals(1, objectMapper.readTree(finalOrders.body()).size());
        assertEquals("CANCELLED", objectMapper.readTree(finalOrders.body()).get(0).path("status").asText());
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @DisplayName("unresolved mock orders keep the kill switch active and audit blocked deactivation over HTTP")
    void killSwitchDeactivationIsBlockedAndAuditedOverHttp() throws Exception {
        AuthDtos.TokenResponse tokens = auth.signup(
                new AuthDtos.SignupRequest("kill-switch-unresolved@example.test", "StrongPassword123!"),
                "corr-kill-switch-unresolved-signup");
        HttpResponse<String> activated = sendAuthorized("PUT", "/api/v1/risk/kill-switch",
                tokens.accessToken(), "{\"active\":true,\"reason\":\"integration safety check\"}");
        assertEquals(200, activated.statusCode());

        Instrument instrument = instruments.findByExchangeAndProviderToken("NSE", "DEMO-NSE-CASH-001").orElseThrow();
        OrderRequest request = new OrderRequest("kill-switch-unresolved-order", instrument.getId(),
                OrderSide.BUY, OrderType.MARKET, instrument.getLotSize(), null, null,
                instrument.getMockPrice().subtract(instrument.getTickSize()));
        orderRepository.saveAndFlush(new OrderEntity(UUID.randomUUID(), tokens.userId(), request.clientOrderId(),
                request, "UNKNOWN", "MOCK-UNKNOWN-ORDER-1", new BigDecimal("10.00")));

        HttpResponse<String> deactivation = sendAuthorized("PUT", "/api/v1/risk/kill-switch",
                tokens.accessToken(), "{\"active\":false}");
        assertEquals(409, deactivation.statusCode());
        assertEquals("KILL_SWITCH_UNRESOLVED_ORDERS", objectMapper.readTree(deactivation.body()).path("code").asText());

        HttpResponse<String> stateResponse = sendAuthorizedGet("/api/v1/risk/kill-switch", tokens.accessToken());
        assertEquals(200, stateResponse.statusCode());
        JsonNode state = objectMapper.readTree(stateResponse.body());
        assertTrue(state.path("active").asBoolean());
        assertEquals(1, state.path("unresolvedOrders").asInt());
        Integer blockedAuditEvents = jdbcTemplate.queryForObject(
                "select count(*) from audit_events where user_id = ? and event_type = ? and outcome = ? and reason_code = ?",
                Integer.class, tokens.userId(), "KILL_SWITCH_DEACTIVATION_BLOCKED", "BLOCKED", "UNRESOLVED_ORDERS");
        assertEquals(1, blockedAuditEvents);
    }

    private boolean attemptSignup(String email, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out while synchronizing concurrent signup requests.");
        }
        try {
            auth.signup(new AuthDtos.SignupRequest(email, "StrongPassword123!"), "corr-concurrent-signup");
            return true;
        } catch (ApiException ex) {
            assertEquals("SIGNUP_CLOSED", ex.code());
            return false;
        }
    }

    private String attemptOrder(UUID userId, Instrument instrument, String clientOrderId,
                                CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out while synchronizing concurrent order requests.");
        }
        OrderRequest request = new OrderRequest(clientOrderId, instrument.getId(), OrderSide.BUY,
                OrderType.MARKET, 1, null, null, new BigDecimal("90.00"));
        try {
            orders.place(userId, request, "corr-concurrent-order");
            return "ACCEPTED";
        } catch (ApiException ex) {
            return ex.code();
        }
    }

    private HttpResponse<String> send(String method, String path, String origin, String requestedMethod)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (origin != null) {
            request.header("Origin", origin);
        }
        if (requestedMethod != null) {
            request.header("Access-Control-Request-Method", requestedMethod);
            request.header("Access-Control-Request-Headers", "authorization");
        }
        request.method(method, HttpRequest.BodyPublishers.noBody());
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendAuthorized(String method, String path, String accessToken, String body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendAuthorizedGet(String path, String accessToken)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + accessToken).GET().build();
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
