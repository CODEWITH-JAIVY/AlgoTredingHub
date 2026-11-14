CREATE TABLE alert_rules (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    instrument_id UUID NOT NULL REFERENCES instruments(id),
    direction VARCHAR(8) NOT NULL CHECK (direction IN ('ABOVE', 'BELOW')),
    threshold_price NUMERIC(18,6) NOT NULL CHECK (threshold_price > 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    state VARCHAR(32) NOT NULL CHECK (state IN ('PAUSED', 'WAITING_FOR_DATA', 'ARMED', 'TRIGGERED')),
    reason_code VARCHAR(64),
    baseline_price NUMERIC(18,6),
    baseline_candle_time TIMESTAMPTZ,
    baseline_master_version VARCHAR(64),
    triggered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_alert_rules_user_created ON alert_rules(user_id, created_at DESC);
CREATE INDEX ix_alert_rules_instrument_active ON alert_rules(instrument_id, active, created_at);

CREATE TABLE alert_events (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    rule_id UUID NOT NULL REFERENCES alert_rules(id) ON DELETE CASCADE,
    instrument_id UUID NOT NULL REFERENCES instruments(id),
    direction VARCHAR(8) NOT NULL CHECK (direction IN ('ABOVE', 'BELOW')),
    threshold_price NUMERIC(18,6) NOT NULL CHECK (threshold_price > 0),
    trigger_price NUMERIC(18,6) NOT NULL CHECK (trigger_price > 0),
    candle_time TIMESTAMPTZ NOT NULL,
    source_id VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ,
    CONSTRAINT uq_alert_event_rule_candle UNIQUE (rule_id, candle_time)
);
CREATE INDEX ix_alert_events_user_created ON alert_events(user_id, created_at DESC);
