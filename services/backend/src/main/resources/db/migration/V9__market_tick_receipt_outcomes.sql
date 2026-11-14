ALTER TABLE market_tick_receipts ADD COLUMN processing_status VARCHAR(32);
ALTER TABLE market_tick_receipts ADD COLUMN outcome_code VARCHAR(64);

-- Older receipts were claimed before the candle buffer's acceptance was known.
-- Preserve them for audit, but never guess that they were incorporated into a candle.
UPDATE market_tick_receipts
SET processing_status = 'RECONCILIATION_REQUIRED',
    outcome_code = 'LEGACY_RECEIPT_STATE_UNKNOWN';

ALTER TABLE market_tick_receipts ALTER COLUMN processing_status SET NOT NULL;
ALTER TABLE market_tick_receipts ADD CONSTRAINT ck_market_tick_receipt_outcome CHECK (
    (processing_status IN ('RECEIVED','BUFFERED','FINALIZED') AND outcome_code IS NULL)
    OR (processing_status = 'RECONCILIATION_REQUIRED' AND outcome_code IS NOT NULL)
);
CREATE INDEX ix_market_tick_receipts_recovery
    ON market_tick_receipts(processing_status, received_at, exchange_time, sequence_number);
