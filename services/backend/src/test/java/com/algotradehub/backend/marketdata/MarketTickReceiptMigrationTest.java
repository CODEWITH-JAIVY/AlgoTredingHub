package com.algotradehub.backend.marketdata;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketTickReceiptMigrationTest {
    @Test
    void v9FailsClosedForLegacyReceiptsAndEnforcesReceiptOutcomeShapes() throws Exception {
        String url = "jdbc:h2:mem:market-tick-receipt-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE market_tick_receipts (event_key VARCHAR(64) PRIMARY KEY, "
                    + "received_at TIMESTAMP WITH TIME ZONE NOT NULL, exchange_time TIMESTAMP WITH TIME ZONE NOT NULL, "
                    + "sequence_number BIGINT NOT NULL)");
            connection.createStatement().execute("INSERT INTO market_tick_receipts "
                    + "(event_key,received_at,exchange_time,sequence_number) "
                    + "VALUES ('legacy-event',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1)");

            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V9__market_tick_receipt_outcomes.sql"));

            try (var rows = connection.createStatement().executeQuery(
                    "SELECT processing_status,outcome_code FROM market_tick_receipts WHERE event_key='legacy-event'")) {
                rows.next();
                assertEquals("RECONCILIATION_REQUIRED", rows.getString("processing_status"));
                assertEquals("LEGACY_RECEIPT_STATE_UNKNOWN", rows.getString("outcome_code"));
            }

            connection.createStatement().execute("INSERT INTO market_tick_receipts "
                    + "(event_key,received_at,exchange_time,sequence_number,processing_status,outcome_code) "
                    + "VALUES ('buffered-event',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,2,'BUFFERED',NULL)");
            connection.createStatement().execute("INSERT INTO market_tick_receipts "
                    + "(event_key,received_at,exchange_time,sequence_number,processing_status,outcome_code) "
                    + "VALUES ('reconciliation-event',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,3,'RECONCILIATION_REQUIRED','LATE_TICK')");
            assertThrows(SQLException.class, () -> connection.createStatement().execute("INSERT INTO market_tick_receipts "
                    + "(event_key,received_at,exchange_time,sequence_number,processing_status,outcome_code) "
                    + "VALUES ('invalid-event',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,4,'BUFFERED','LATE_TICK')"));
        }
    }
}
