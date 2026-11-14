package com.algotradehub.backend.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class OpenApiContractTest {
    private static final Set<String> EXPECTED_PATHS = Set.of(
            "/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
            "/api/v1/brokers/adapters", "/api/v1/market/instruments", "/api/v1/market/quotes",
            "/api/v1/market/data-status", "/api/v1/market/candles", "/api/v1/analysis/{instrumentId}",
            "/api/v1/orders", "/api/v1/orders/{id}/cancel", "/api/v1/risk/policy",
            "/api/v1/alerts", "/api/v1/alerts/{id}", "/api/v1/alerts/events", "/api/v1/alerts/events/{id}/read",
            "/api/v1/risk/kill-switch", "/actuator/health", "/actuator/info");

    @Test
    void publicContractCoversRoutesResolvesReferencesAndKeepsLiveOrdersDisabled() throws Exception {
        Map<?, ?> document = loadDocument();
        assertEquals("3.1.0", document.get("openapi"));
        Map<?, ?> paths = requireMap(document.get("paths"));
        assertTrue(paths.keySet().containsAll(EXPECTED_PATHS));

        List<String> unresolvedReferences = new ArrayList<>();
        collectUnresolvedReferences(document, document, unresolvedReferences);
        assertTrue(unresolvedReferences.isEmpty(), unresolvedReferences.toString());

        Map<?, ?> components = requireMap(document.get("components"));
        Map<?, ?> schemas = requireMap(components.get("schemas"));
        Map<?, ?> orderProperties = requireMap(requireMap(schemas.get("Order")).get("properties"));
        Map<?, ?> analysisProperties = requireMap(requireMap(schemas.get("AnalysisSummary")).get("properties"));
        Map<?, ?> brokerProperties = requireMap(requireMap(schemas.get("BrokerAdapter")).get("properties"));
        Map<?, ?> alertRuleProperties = requireMap(requireMap(schemas.get("AlertRule")).get("properties"));
        Map<?, ?> alertRuleType = requireMap(alertRuleProperties.get("ruleType"));
        Map<?, ?> alertRequestProperties = requireMap(requireMap(schemas.get("AlertRuleRequest")).get("properties"));
        Map<?, ?> alertEventProperties = requireMap(requireMap(schemas.get("AlertEvent")).get("properties"));
        assertEquals("MOCK", requireMap(orderProperties.get("mode")).get("const"));
        assertEquals("boolean", requireMap(analysisProperties.get("actionable")).get("type"));
        assertEquals("string", requireMap(analysisProperties.get("source")).get("type"));
        assertEquals(Boolean.FALSE, requireMap(brokerProperties.get("supportsLiveExecution")).get("const"));
        assertEquals(List.of("PAUSED", "WAITING_FOR_DATA", "ARMED", "TRIGGERED"), requireMap(alertRuleProperties.get("state")).get("enum"));
        assertEquals(List.of("PRICE_CROSSING", "ZONE_TOUCH", "VOLUME_SPIKE"), alertRuleType.get("enum"));
        assertEquals(List.of("PRICE_CROSSING", "ZONE_TOUCH", "VOLUME_SPIKE"),
                requireMap(alertRequestProperties.get("ruleType")).get("enum"));
        assertNotNull(alertRuleProperties.get("upperPrice"));
        assertNotNull(alertRequestProperties.get("volumeMultiplier"));
        assertNotNull(alertEventProperties.get("triggerVolume"));
        assertNotNull(alertEventProperties.get("referenceAverageVolume"));
    }

    private Map<?, ?> loadDocument() throws Exception {
        Path path = Path.of("docs", "openapi.yaml");
        if (!Files.exists(path)) path = Path.of("..", "..", "docs", "openapi.yaml");
        assertTrue(Files.exists(path), "docs/openapi.yaml must be present from the repository root or backend module.");
        Object loaded = new Yaml().load(Files.readString(path));
        assertNotNull(loaded);
        return requireMap(loaded);
    }

    private Map<?, ?> requireMap(Object value) {
        assertTrue(value instanceof Map<?, ?>, "Expected a YAML mapping.");
        return (Map<?, ?>) value;
    }

    private void collectUnresolvedReferences(Object node, Map<?, ?> root, List<String> unresolved) {
        if (node instanceof Map<?, ?> map) {
            Object value = map.get("$ref");
            if (value instanceof String reference && reference.startsWith("#/")) {
                if (resolveReference(root, reference) == null) unresolved.add(reference);
            }
            map.values().forEach(child -> collectUnresolvedReferences(child, root, unresolved));
        } else if (node instanceof List<?> list) {
            list.forEach(child -> collectUnresolvedReferences(child, root, unresolved));
        }
    }

    private Object resolveReference(Map<?, ?> root, String reference) {
        Object current = root;
        for (String segment : reference.substring(2).split("/")) {
            if (!(current instanceof Map<?, ?> currentMap) || !currentMap.containsKey(segment)) return null;
            current = currentMap.get(segment);
        }
        return current;
    }
}
