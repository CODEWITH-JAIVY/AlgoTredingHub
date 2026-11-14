ALTER TABLE risk_policies
    ADD COLUMN pending_max_quantity BIGINT;
ALTER TABLE risk_policies
    ADD COLUMN pending_max_order_notional NUMERIC(18,2);
ALTER TABLE risk_policies
    ADD COLUMN pending_max_open_orders INTEGER;
ALTER TABLE risk_policies
    ADD COLUMN pending_max_daily_loss NUMERIC(18,2);
ALTER TABLE risk_policies
    ADD COLUMN pending_max_trade_loss NUMERIC(18,2);
ALTER TABLE risk_policies
    ADD COLUMN pending_effective_on DATE;

ALTER TABLE risk_policies
    ADD CONSTRAINT ck_risk_pending_quantity CHECK (
        pending_max_quantity IS NULL OR pending_max_quantity > max_quantity
    );
ALTER TABLE risk_policies
    ADD CONSTRAINT ck_risk_pending_notional CHECK (
        pending_max_order_notional IS NULL OR pending_max_order_notional > max_order_notional
    );
ALTER TABLE risk_policies
    ADD CONSTRAINT ck_risk_pending_open_orders CHECK (
        pending_max_open_orders IS NULL OR pending_max_open_orders > max_open_orders
    );
ALTER TABLE risk_policies
    ADD CONSTRAINT ck_risk_pending_daily_loss CHECK (
        pending_max_daily_loss IS NULL OR pending_max_daily_loss > max_daily_loss
    );
ALTER TABLE risk_policies
    ADD CONSTRAINT ck_risk_pending_trade_loss CHECK (
        pending_max_trade_loss IS NULL OR pending_max_trade_loss > max_trade_loss
    );
ALTER TABLE risk_policies
    ADD CONSTRAINT ck_risk_pending_schedule CHECK (
        (pending_effective_on IS NULL AND pending_max_quantity IS NULL
            AND pending_max_order_notional IS NULL AND pending_max_open_orders IS NULL
            AND pending_max_daily_loss IS NULL AND pending_max_trade_loss IS NULL)
        OR
        (pending_effective_on IS NOT NULL AND (pending_max_quantity IS NOT NULL
            OR pending_max_order_notional IS NOT NULL OR pending_max_open_orders IS NOT NULL
            OR pending_max_daily_loss IS NOT NULL OR pending_max_trade_loss IS NOT NULL))
    );
