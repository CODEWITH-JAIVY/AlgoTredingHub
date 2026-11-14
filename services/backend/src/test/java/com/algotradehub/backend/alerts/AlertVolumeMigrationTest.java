package com.algotradehub.backend.alerts;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AlertVolumeMigrationTest {
    @Test void v8PreservesExistingRulesAndEnforcesAllVolumeAndZoneShapesInH2PostgresMode() throws Exception {
        String url = "jdbc:h2:mem:alert-volume-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users (id UUID PRIMARY KEY)");
            connection.createStatement().execute("CREATE TABLE instruments (id UUID PRIMARY KEY)");
            connection.createStatement().execute("CREATE TABLE market_candles (instrument_id UUID NOT NULL, "
                    + "timeframe_seconds INTEGER NOT NULL, source_id VARCHAR(50) NOT NULL, "
                    + "bucket_start TIMESTAMP WITH TIME ZONE NOT NULL)");
            String v6;
            try (var input = new ClassPathResource("db/migration/V6__server_price_alerts.sql").getInputStream()) {
                v6 = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                        .replace("TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE");
            }
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(v6.getBytes(StandardCharsets.UTF_8)));
            applyV7ShapeWithPostgresConstraintNames(connection);

            UUID userId = UUID.fromString("ab4f1e73-16bf-4cbd-8f32-22ef85a80c0e");
            UUID instrumentId = UUID.fromString("86b8c400-dcac-46d0-b465-29e12ca80103");
            UUID priceRuleId = UUID.fromString("0c31f8c2-07f4-43af-8b3b-0de93f2fe6b1");
            connection.createStatement().execute("INSERT INTO users VALUES ('" + userId + "')");
            connection.createStatement().execute("INSERT INTO instruments VALUES ('" + instrumentId + "')");
            connection.createStatement().execute("INSERT INTO alert_rules (id,user_id,instrument_id,direction,threshold_price,"
                    + "active,state,created_at,updated_at) VALUES ('" + priceRuleId + "','" + userId + "','" + instrumentId
                    + "','ABOVE',100,TRUE,'PAUSED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            connection.createStatement().execute("INSERT INTO alert_events (id,user_id,rule_id,instrument_id,direction,"
                    + "threshold_price,trigger_price,candle_time,source_id,created_at) VALUES "
                    + "('0fb748dd-c8aa-48f8-88db-c5f8b21fd171','" + userId + "','" + priceRuleId + "','" + instrumentId
                    + "','ABOVE',100,101,CURRENT_TIMESTAMP,'TEST_FEED',CURRENT_TIMESTAMP)");

            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V8__volume_spike_alerts.sql"));

            try (var rows = connection.createStatement().executeQuery(
                    "SELECT rule_type, threshold_price, volume_multiplier, lookback_candles FROM alert_rules WHERE id='" + priceRuleId + "'")) {
                rows.next();
                assertEquals("PRICE_CROSSING", rows.getString("rule_type"));
                assertEquals(100.0, rows.getDouble("threshold_price"));
                assertNull(rows.getObject("volume_multiplier"));
                assertNull(rows.getObject("lookback_candles"));
            }

            UUID volumeRuleId = UUID.fromString("ed6cfc48-7607-4a64-89f6-1b07a15c1c13");
            insertVolumeRule(connection, volumeRuleId, userId, instrumentId, "2.00", "20");
            connection.createStatement().execute("INSERT INTO alert_events (id,user_id,rule_id,instrument_id,rule_type,"
                    + "trigger_price,volume_multiplier,lookback_candles,trigger_volume,reference_average_volume,"
                    + "candle_time,source_id,created_at) VALUES ('53a83ea9-c092-420c-865e-85ec2640d3e6','" + userId
                    + "','" + volumeRuleId + "','" + instrumentId + "','VOLUME_SPIKE',100,2.00,20,200,100.00,"
                    + "CURRENT_TIMESTAMP,'TEST_FEED',CURRENT_TIMESTAMP)");

            assertThrows(SQLException.class, () -> insertVolumeRule(connection,
                    UUID.fromString("c3aa438c-5f88-42f7-8d6d-88c1b6e3173b"), userId, instrumentId, "NULL", "20"));
            assertThrows(SQLException.class, () -> connection.createStatement().execute("INSERT INTO alert_rules "
                    + "(id,user_id,instrument_id,rule_type,direction,threshold_price,upper_price,active,state,created_at,updated_at) "
                    + "VALUES ('49f571b5-66a7-4c21-b6ac-86de8fcbb8ec','" + userId + "','" + instrumentId
                    + "','ZONE_TOUCH',NULL,100,NULL,TRUE,'PAUSED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)"));
            assertThrows(SQLException.class, () -> connection.createStatement().execute("INSERT INTO alert_events "
                    + "(id,user_id,rule_id,instrument_id,rule_type,trigger_price,volume_multiplier,lookback_candles,"
                    + "trigger_volume,candle_time,source_id,created_at) VALUES ('984cf457-63fa-48cc-8e36-f5814a6ce3d9','"
                    + userId + "','" + volumeRuleId + "','" + instrumentId
                    + "','VOLUME_SPIKE',100,2.00,20,200,CURRENT_TIMESTAMP,'TEST_FEED',CURRENT_TIMESTAMP)"));
        }
    }

    private void insertVolumeRule(Connection connection, UUID ruleId, UUID userId, UUID instrumentId,
                                  String multiplier, String lookback) throws SQLException {
        connection.createStatement().execute("INSERT INTO alert_rules (id,user_id,instrument_id,rule_type,direction,"
                + "threshold_price,upper_price,volume_multiplier,lookback_candles,active,state,created_at,updated_at) "
                + "VALUES ('" + ruleId + "','" + userId + "','" + instrumentId
                + "','VOLUME_SPIKE',NULL,NULL,NULL," + multiplier + "," + lookback
                + ",TRUE,'WAITING_FOR_DATA',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    }

    private void applyV7ShapeWithPostgresConstraintNames(Connection connection) throws SQLException {
        connection.createStatement().execute("ALTER TABLE alert_rules ALTER COLUMN direction DROP NOT NULL");
        connection.createStatement().execute("ALTER TABLE alert_rules ADD COLUMN rule_type VARCHAR(16) NOT NULL DEFAULT 'PRICE_CROSSING'");
        connection.createStatement().execute("ALTER TABLE alert_rules ADD CONSTRAINT alert_rules_rule_type_check "
                + "CHECK (rule_type IN ('PRICE_CROSSING','ZONE_TOUCH'))");
        connection.createStatement().execute("ALTER TABLE alert_rules ADD COLUMN upper_price NUMERIC(18,6)");
        connection.createStatement().execute("ALTER TABLE alert_rules ADD CONSTRAINT ck_alert_rule_price_shape CHECK "
                + "((rule_type='PRICE_CROSSING' AND direction IS NOT NULL AND upper_price IS NULL) "
                + "OR (rule_type='ZONE_TOUCH' AND direction IS NULL AND upper_price > threshold_price))");
        connection.createStatement().execute("ALTER TABLE alert_events ALTER COLUMN direction DROP NOT NULL");
        connection.createStatement().execute("ALTER TABLE alert_events ADD COLUMN rule_type VARCHAR(16) NOT NULL DEFAULT 'PRICE_CROSSING'");
        connection.createStatement().execute("ALTER TABLE alert_events ADD CONSTRAINT alert_events_rule_type_check "
                + "CHECK (rule_type IN ('PRICE_CROSSING','ZONE_TOUCH'))");
        connection.createStatement().execute("ALTER TABLE alert_events ADD COLUMN upper_price NUMERIC(18,6)");
        connection.createStatement().execute("ALTER TABLE alert_events ADD CONSTRAINT ck_alert_event_price_shape CHECK "
                + "((rule_type='PRICE_CROSSING' AND direction IS NOT NULL AND upper_price IS NULL) "
                + "OR (rule_type='ZONE_TOUCH' AND direction IS NULL AND upper_price > threshold_price))");
    }
}
