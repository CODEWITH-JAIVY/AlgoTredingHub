package com.algotradehub.backend.risk;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RiskPolicyMigrationTest {
    @Test void v4KeepsExistingPoliciesAndRejectsInvalidPendingRelaxationsInH2PostgresMode() throws Exception {
        String url = "jdbc:h2:mem:risk-policy-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE risk_policies ("
                    + "user_id UUID PRIMARY KEY, max_quantity BIGINT NOT NULL, "
                    + "max_order_notional NUMERIC(18,2) NOT NULL, max_open_orders INTEGER NOT NULL, "
                    + "max_daily_loss NUMERIC(18,2) NOT NULL, max_trade_loss NUMERIC(18,2) NOT NULL)");
            connection.createStatement().execute("INSERT INTO risk_policies VALUES "
                    + "('bc859ced-b9d4-4f23-9f61-b2b064df664e', 100, 100000.00, 5, 5000.00, 1000.00)");

            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V4__defer_risk_limit_relaxations.sql"));

            try (var rows = connection.createStatement().executeQuery(
                    "SELECT pending_max_quantity, pending_effective_on FROM risk_policies")) {
                rows.next();
                assertNull(rows.getObject("pending_max_quantity"));
                assertNull(rows.getDate("pending_effective_on"));
            }
            connection.createStatement().executeUpdate("UPDATE risk_policies SET "
                    + "pending_max_quantity = 150, pending_effective_on = DATE '2026-10-22'");
            try (var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM risk_policies "
                    + "WHERE pending_max_quantity = 150 AND pending_effective_on = DATE '2026-10-22'")) {
                rows.next();
                assertEquals(1, rows.getInt(1));
            }
            assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate(
                    "UPDATE risk_policies SET pending_max_quantity = 100"));
            assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate(
                    "UPDATE risk_policies SET pending_effective_on = NULL"));
        }
    }

    @Test void v5AddsSafeDailyDisciplineDefaultsAndRejectsInvalidBoundsInH2PostgresMode() throws Exception {
        String url = "jdbc:h2:mem:risk-discipline-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE risk_policies ("
                    + "user_id UUID PRIMARY KEY, max_quantity BIGINT NOT NULL, "
                    + "max_order_notional NUMERIC(18,2) NOT NULL, max_open_orders INTEGER NOT NULL, "
                    + "max_daily_loss NUMERIC(18,2) NOT NULL, max_trade_loss NUMERIC(18,2) NOT NULL)");
            connection.createStatement().execute("INSERT INTO risk_policies VALUES "
                    + "('bc859ced-b9d4-4f23-9f61-b2b064df664e', 100, 100000.00, 5, 5000.00, 1000.00)");
            connection.createStatement().execute("CREATE TABLE daily_risk_state ("
                    + "user_id UUID PRIMARY KEY, session_date DATE NOT NULL, "
                    + "realized_loss NUMERIC(18,2) NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL)");
            connection.createStatement().execute("INSERT INTO daily_risk_state VALUES "
                    + "('bc859ced-b9d4-4f23-9f61-b2b064df664e', DATE '2026-10-21', 0.00, CURRENT_TIMESTAMP)");

            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V4__defer_risk_limit_relaxations.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V5__daily_discipline_guardrails.sql"));

            try (var rows = connection.createStatement().executeQuery("SELECT max_orders_per_day, "
                    + "no_new_orders_after, no_orders_first_minutes FROM risk_policies")) {
                rows.next();
                assertEquals(10, rows.getInt("max_orders_per_day"));
                assertEquals(java.sql.Time.valueOf("15:00:00"), rows.getTime("no_new_orders_after"));
                assertEquals(5, rows.getInt("no_orders_first_minutes"));
            }
            try (var rows = connection.createStatement().executeQuery(
                    "SELECT accepted_order_entries FROM daily_risk_state")) {
                rows.next();
                assertEquals(0, rows.getInt("accepted_order_entries"));
            }

            assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate(
                    "UPDATE risk_policies SET max_orders_per_day = 0"));
            assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate(
                    "UPDATE risk_policies SET no_new_orders_after = TIME '09:15'"));
            assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate(
                    "UPDATE risk_policies SET no_new_orders_after = TIME '11:15', no_orders_first_minutes = 120"));
            assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate(
                    "UPDATE daily_risk_state SET accepted_order_entries = 101"));
            assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate(
                    "UPDATE risk_policies SET pending_no_new_orders_after = TIME '09:20', "
                            + "pending_no_orders_first_minutes = 10, pending_effective_on = DATE '2026-10-22'"));
        }
    }
}
