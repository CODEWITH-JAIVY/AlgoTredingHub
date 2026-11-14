CREATE TABLE instrument_master_versions (
    version VARCHAR(64) PRIMARY KEY,
    source_id VARCHAR(50) NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    checksum VARCHAR(64),
    synthetic BOOLEAN NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('CURRENT', 'SUPERSEDED'))
);
WITH versions AS (
    SELECT master_version, max(updated_at) AS imported_at FROM instruments GROUP BY master_version
), ranked AS (
    SELECT master_version, imported_at, row_number() OVER (ORDER BY imported_at DESC, master_version) AS version_rank
    FROM versions
)
INSERT INTO instrument_master_versions(version,source_id,imported_at,effective_from,checksum,synthetic,status)
SELECT master_version,'SYNTHETIC_DEMO',imported_at,imported_at,NULL,TRUE,
       CASE WHEN version_rank = 1 THEN 'CURRENT' ELSE 'SUPERSEDED' END FROM ranked
ON CONFLICT (version) DO NOTHING;
ALTER TABLE instruments ADD CONSTRAINT fk_instruments_master_version
    FOREIGN KEY (master_version) REFERENCES instrument_master_versions(version);

CREATE TABLE market_candles (
    id UUID PRIMARY KEY,
    instrument_id UUID NOT NULL REFERENCES instruments(id),
    timeframe_seconds INTEGER NOT NULL CHECK (timeframe_seconds = 60),
    bucket_start TIMESTAMPTZ NOT NULL,
    open_price NUMERIC(18,6) NOT NULL CHECK (open_price > 0),
    high_price NUMERIC(18,6) NOT NULL CHECK (high_price > 0),
    low_price NUMERIC(18,6) NOT NULL CHECK (low_price > 0),
    close_price NUMERIC(18,6) NOT NULL CHECK (close_price > 0),
    volume BIGINT NOT NULL CHECK (volume >= 0),
    trade_count BIGINT NOT NULL CHECK (trade_count >= 0),
    source_id VARCHAR(50) NOT NULL,
    quality VARCHAR(24) NOT NULL CHECK (quality IN ('SYNTHETIC', 'PROVIDER')),
    master_version VARCHAR(64) NOT NULL,
    finalized_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_market_candle_source UNIQUE (instrument_id, timeframe_seconds, bucket_start, source_id),
    CONSTRAINT ck_market_candle_ohlc CHECK (high_price >= open_price AND high_price >= close_price
        AND low_price <= open_price AND low_price <= close_price AND high_price >= low_price)
);
CREATE INDEX ix_market_candles_range ON market_candles(instrument_id, timeframe_seconds, bucket_start);

CREATE TABLE market_data_gaps (
    id UUID PRIMARY KEY,
    instrument_id UUID NOT NULL REFERENCES instruments(id),
    source_id VARCHAR(50) NOT NULL,
    gap_start TIMESTAMPTZ NOT NULL,
    gap_end TIMESTAMPTZ NOT NULL,
    reason VARCHAR(32) NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_market_gap_range CHECK (gap_end > gap_start),
    CONSTRAINT uq_market_gap UNIQUE (instrument_id, source_id, gap_start, gap_end)
);
CREATE INDEX ix_market_gaps_range ON market_data_gaps(instrument_id, gap_start, gap_end);

CREATE TABLE market_tick_receipts (
    event_key VARCHAR(64) PRIMARY KEY CHECK (length(event_key) = 64),
    instrument_id UUID NOT NULL REFERENCES instruments(id),
    source_id VARCHAR(50) NOT NULL,
    sequence_number BIGINT NOT NULL CHECK (sequence_number >= 0),
    exchange_time TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    price NUMERIC(18,6) NOT NULL CHECK (price > 0),
    trade_quantity BIGINT NOT NULL CHECK (trade_quantity > 0),
    instrument_master_version VARCHAR(64) NOT NULL
);
CREATE INDEX ix_market_tick_receipts_received ON market_tick_receipts(received_at);

CREATE TABLE history_import_jobs (
    id UUID PRIMARY KEY,
    request_key VARCHAR(64) NOT NULL UNIQUE CHECK (length(request_key) = 64),
    instrument_id UUID NOT NULL REFERENCES instruments(id),
    source_id VARCHAR(50) NOT NULL,
    timeframe_seconds INTEGER NOT NULL CHECK (timeframe_seconds = 60),
    range_start TIMESTAMPTZ NOT NULL,
    range_end TIMESTAMPTZ NOT NULL,
    next_cursor VARCHAR(512),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETE', 'FAILED')),
    imported_count BIGINT NOT NULL DEFAULT 0 CHECK (imported_count >= 0),
    last_request_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_history_import_range CHECK (range_end > range_start)
);
CREATE INDEX ix_history_import_status ON history_import_jobs(status, updated_at);

CREATE TABLE market_data_source_rate_limits (
    source_id VARCHAR(50) PRIMARY KEY,
    last_request_at TIMESTAMPTZ NOT NULL
);
