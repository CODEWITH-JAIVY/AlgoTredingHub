ALTER TABLE alert_rules DROP CONSTRAINT IF EXISTS alert_rules_rule_type_check;
ALTER TABLE alert_rules DROP CONSTRAINT IF EXISTS ck_alert_rule_price_shape;
ALTER TABLE alert_rules ALTER COLUMN threshold_price DROP NOT NULL;
ALTER TABLE alert_rules ADD COLUMN volume_multiplier NUMERIC(4,2);
ALTER TABLE alert_rules ADD COLUMN lookback_candles INTEGER;
CREATE INDEX ix_market_candles_alert_history
    ON market_candles(instrument_id, timeframe_seconds, source_id, bucket_start DESC);
ALTER TABLE alert_rules ADD CONSTRAINT ck_alert_rule_type CHECK (rule_type IN ('PRICE_CROSSING', 'ZONE_TOUCH', 'VOLUME_SPIKE'));
ALTER TABLE alert_rules ADD CONSTRAINT ck_alert_rule_shape CHECK (
    (rule_type = 'PRICE_CROSSING' AND direction IS NOT NULL AND threshold_price IS NOT NULL AND upper_price IS NULL
        AND volume_multiplier IS NULL AND lookback_candles IS NULL)
    OR (rule_type = 'ZONE_TOUCH' AND direction IS NULL AND threshold_price IS NOT NULL AND upper_price IS NOT NULL AND upper_price > threshold_price
        AND volume_multiplier IS NULL AND lookback_candles IS NULL)
    OR (rule_type = 'VOLUME_SPIKE' AND direction IS NULL AND threshold_price IS NULL AND upper_price IS NULL
        AND volume_multiplier IS NOT NULL AND volume_multiplier >= 1.10 AND volume_multiplier <= 10.00
        AND lookback_candles IS NOT NULL AND lookback_candles = 20)
);

ALTER TABLE alert_events DROP CONSTRAINT IF EXISTS alert_events_rule_type_check;
ALTER TABLE alert_events DROP CONSTRAINT IF EXISTS ck_alert_event_price_shape;
ALTER TABLE alert_events ALTER COLUMN threshold_price DROP NOT NULL;
ALTER TABLE alert_events ADD COLUMN volume_multiplier NUMERIC(4,2);
ALTER TABLE alert_events ADD COLUMN lookback_candles INTEGER;
ALTER TABLE alert_events ADD COLUMN trigger_volume BIGINT;
ALTER TABLE alert_events ADD COLUMN reference_average_volume NUMERIC(25,6);
ALTER TABLE alert_events ADD CONSTRAINT ck_alert_event_type CHECK (rule_type IN ('PRICE_CROSSING', 'ZONE_TOUCH', 'VOLUME_SPIKE'));
ALTER TABLE alert_events ADD CONSTRAINT ck_alert_event_shape CHECK (
    (rule_type = 'PRICE_CROSSING' AND direction IS NOT NULL AND threshold_price IS NOT NULL AND upper_price IS NULL
        AND volume_multiplier IS NULL AND lookback_candles IS NULL AND trigger_volume IS NULL AND reference_average_volume IS NULL)
    OR (rule_type = 'ZONE_TOUCH' AND direction IS NULL AND threshold_price IS NOT NULL AND upper_price IS NOT NULL AND upper_price > threshold_price
        AND volume_multiplier IS NULL AND lookback_candles IS NULL AND trigger_volume IS NULL AND reference_average_volume IS NULL)
    OR (rule_type = 'VOLUME_SPIKE' AND direction IS NULL AND threshold_price IS NULL AND upper_price IS NULL
        AND volume_multiplier IS NOT NULL AND volume_multiplier >= 1.10 AND volume_multiplier <= 10.00
        AND lookback_candles IS NOT NULL AND lookback_candles = 20
        AND trigger_volume IS NOT NULL AND trigger_volume >= 0
        AND reference_average_volume IS NOT NULL AND reference_average_volume > 0)
);
