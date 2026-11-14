ALTER TABLE alert_rules ALTER COLUMN direction DROP NOT NULL;
ALTER TABLE alert_rules ADD COLUMN rule_type VARCHAR(16) NOT NULL DEFAULT 'PRICE_CROSSING'
    CHECK (rule_type IN ('PRICE_CROSSING', 'ZONE_TOUCH'));
ALTER TABLE alert_rules ADD COLUMN upper_price NUMERIC(18,6);
ALTER TABLE alert_rules ADD CONSTRAINT ck_alert_rule_price_shape CHECK (
    (rule_type = 'PRICE_CROSSING' AND direction IS NOT NULL AND upper_price IS NULL)
    OR (rule_type = 'ZONE_TOUCH' AND direction IS NULL AND upper_price > threshold_price)
);

ALTER TABLE alert_events ALTER COLUMN direction DROP NOT NULL;
ALTER TABLE alert_events ADD COLUMN rule_type VARCHAR(16) NOT NULL DEFAULT 'PRICE_CROSSING'
    CHECK (rule_type IN ('PRICE_CROSSING', 'ZONE_TOUCH'));
ALTER TABLE alert_events ADD COLUMN upper_price NUMERIC(18,6);
ALTER TABLE alert_events ADD CONSTRAINT ck_alert_event_price_shape CHECK (
    (rule_type = 'PRICE_CROSSING' AND direction IS NOT NULL AND upper_price IS NULL)
    OR (rule_type = 'ZONE_TOUCH' AND direction IS NULL AND upper_price > threshold_price)
);
