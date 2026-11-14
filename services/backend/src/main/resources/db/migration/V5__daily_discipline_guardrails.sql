ALTER TABLE risk_policies
    ADD COLUMN max_orders_per_day INTEGER NOT NULL DEFAULT 10;

ALTER TABLE risk_policies
    ADD COLUMN no_new_orders_after TIME NOT NULL DEFAULT TIME '15:00';

ALTER TABLE risk_policies
    ADD COLUMN no_orders_first_minutes INTEGER NOT NULL DEFAULT 5;

ALTER TABLE risk_policies
    ADD COLUMN pending_max_orders_per_day INTEGER;

ALTER TABLE risk_policies
    ADD COLUMN pending_no_new_orders_after TIME;

ALTER TABLE risk_policies
    ADD COLUMN pending_no_orders_first_minutes INTEGER;

ALTER TABLE risk_policies
    DROP CONSTRAINT ck_risk_pending_schedule;

ALTER TABLE risk_policies
    ADD CONSTRAINT ck_risk_discipline_values CHECK (
        max_orders_per_day BETWEEN 1 AND 100
        AND no_new_orders_after > TIME '09:15'
        AND no_new_orders_after <= TIME '15:30'
        AND EXTRACT(SECOND FROM no_new_orders_after) = 0
        AND no_orders_first_minutes BETWEEN 0 AND 120
        AND (EXTRACT(HOUR FROM no_new_orders_after) * 60
            + EXTRACT(MINUTE FROM no_new_orders_after)) > (555 + no_orders_first_minutes)
    );

ALTER TABLE risk_policies
    ADD CONSTRAINT ck_risk_pending_discipline_values CHECK (
        (pending_max_orders_per_day IS NULL OR pending_max_orders_per_day BETWEEN 1 AND 100)
        AND (pending_no_orders_first_minutes IS NULL OR pending_no_orders_first_minutes BETWEEN 0 AND 120)
        AND ((pending_no_new_orders_after IS NULL AND pending_no_orders_first_minutes IS NULL)
            OR (coalesce(pending_no_new_orders_after, no_new_orders_after) > TIME '09:15'
                AND coalesce(pending_no_new_orders_after, no_new_orders_after) <= TIME '15:30'
                AND EXTRACT(SECOND FROM coalesce(pending_no_new_orders_after, no_new_orders_after)) = 0
                AND (EXTRACT(HOUR FROM coalesce(pending_no_new_orders_after, no_new_orders_after)) * 60
                    + EXTRACT(MINUTE FROM coalesce(pending_no_new_orders_after, no_new_orders_after)))
                    > (555 + coalesce(pending_no_orders_first_minutes, no_orders_first_minutes))))
    );

ALTER TABLE daily_risk_state
    ADD COLUMN accepted_order_entries INTEGER NOT NULL DEFAULT 0;

ALTER TABLE daily_risk_state
    ADD CONSTRAINT ck_daily_order_entries_range CHECK (accepted_order_entries BETWEEN 0 AND 100);

ALTER TABLE risk_policies
    ADD CONSTRAINT ck_risk_pending_schedule CHECK (
        (pending_effective_on IS NULL AND pending_max_quantity IS NULL
            AND pending_max_order_notional IS NULL AND pending_max_open_orders IS NULL
            AND pending_max_daily_loss IS NULL AND pending_max_trade_loss IS NULL
            AND pending_max_orders_per_day IS NULL
            AND pending_no_new_orders_after IS NULL AND pending_no_orders_first_minutes IS NULL)
        OR
        (pending_effective_on IS NOT NULL AND (pending_max_quantity IS NOT NULL
            OR pending_max_order_notional IS NOT NULL OR pending_max_open_orders IS NOT NULL
            OR pending_max_daily_loss IS NOT NULL OR pending_max_trade_loss IS NOT NULL
            OR pending_max_orders_per_day IS NOT NULL OR pending_no_new_orders_after IS NOT NULL
            OR pending_no_orders_first_minutes IS NOT NULL))
    );
