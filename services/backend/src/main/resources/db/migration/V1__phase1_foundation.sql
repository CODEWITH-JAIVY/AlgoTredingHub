CREATE TABLE users (
  id UUID PRIMARY KEY,
  email VARCHAR(320) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_users_email UNIQUE (email),
  CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE','SUSPENDED','DELETED'))
);
CREATE TABLE refresh_sessions (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id),
  token_hash CHAR(64) NOT NULL UNIQUE,
  family_id UUID NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  replaced_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_refresh_sessions_user ON refresh_sessions(user_id);
CREATE INDEX ix_refresh_sessions_expiry ON refresh_sessions(expires_at);
CREATE TABLE risk_policies (
  user_id UUID PRIMARY KEY REFERENCES users(id),
  max_quantity BIGINT NOT NULL DEFAULT 100,
  max_order_notional NUMERIC(18,2) NOT NULL DEFAULT 100000,
  max_open_orders INTEGER NOT NULL DEFAULT 5,
  max_daily_loss NUMERIC(18,2) NOT NULL DEFAULT 5000,
  max_trade_loss NUMERIC(18,2) NOT NULL DEFAULT 1000,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_risk_values CHECK (max_quantity > 0 AND max_order_notional > 0 AND max_open_orders > 0 AND max_daily_loss > 0 AND max_trade_loss > 0)
);
CREATE TABLE account_controls (
  user_id UUID PRIMARY KEY REFERENCES users(id),
  kill_switch_active BOOLEAN NOT NULL DEFAULT FALSE,
  kill_switch_reason VARCHAR(500),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE broker_connections (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id),
  provider VARCHAR(32) NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  status VARCHAR(24) NOT NULL,
  secret_reference VARCHAR(500),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_broker_provider CHECK (provider IN ('MOCK','ZERODHA')),
  CONSTRAINT ck_broker_status CHECK (status IN ('DISCONNECTED','PENDING','CONNECTED','EXPIRED','ERROR'))
);
CREATE INDEX ix_broker_connections_user ON broker_connections(user_id);
CREATE TABLE instruments (
  id UUID PRIMARY KEY,
  provider_token VARCHAR(120) NOT NULL,
  exchange VARCHAR(8) NOT NULL,
  segment VARCHAR(24) NOT NULL,
  symbol VARCHAR(100) NOT NULL,
  instrument_type VARCHAR(16) NOT NULL,
  expiry_date DATE,
  strike_price NUMERIC(18,4),
  option_type VARCHAR(2),
  lot_size INTEGER NOT NULL DEFAULT 1,
  tick_size NUMERIC(12,6) NOT NULL DEFAULT 0.05,
  mock_price NUMERIC(18,4) NOT NULL DEFAULT 100,
  synthetic BOOLEAN NOT NULL DEFAULT TRUE,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  master_version VARCHAR(64) NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_instruments_exchange_token UNIQUE (exchange, provider_token),
  CONSTRAINT ck_instrument_exchange CHECK (exchange IN ('NSE','BSE')),
  CONSTRAINT ck_instrument_lot CHECK (lot_size > 0 AND tick_size > 0)
);
CREATE INDEX ix_instruments_search ON instruments(exchange, segment, symbol);
CREATE TABLE orders (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id),
  broker_connection_id UUID REFERENCES broker_connections(id),
  client_order_id VARCHAR(100) NOT NULL,
  instrument_id UUID NOT NULL REFERENCES instruments(id),
  side VARCHAR(8) NOT NULL,
  order_type VARCHAR(16) NOT NULL,
  quantity BIGINT NOT NULL,
  limit_price NUMERIC(18,4),
  trigger_price NUMERIC(18,4),
  stop_loss_price NUMERIC(18,4) NOT NULL,
  estimated_stop_risk NUMERIC(18,2) NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL,
  broker_order_id VARCHAR(120),
  mode VARCHAR(12) NOT NULL DEFAULT 'MOCK',
  rejection_reason VARCHAR(300),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_orders_user_client UNIQUE (user_id, client_order_id),
  CONSTRAINT ck_order_side CHECK (side IN ('BUY','SELL')),
  CONSTRAINT ck_order_mode CHECK (mode = 'MOCK'),
  CONSTRAINT ck_order_quantity CHECK (quantity > 0)
);
CREATE INDEX ix_orders_user_created ON orders(user_id, created_at DESC);
CREATE INDEX ix_orders_status ON orders(user_id, status);
CREATE TABLE daily_risk_state (
  user_id UUID PRIMARY KEY REFERENCES users(id),
  session_date DATE NOT NULL,
  realized_loss NUMERIC(18,2) NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_daily_loss_nonnegative CHECK (realized_loss >= 0)
);
CREATE TABLE audit_events (
  id UUID PRIMARY KEY,
  user_id UUID,
  event_type VARCHAR(80) NOT NULL,
  entity_type VARCHAR(80),
  entity_id VARCHAR(100),
  outcome VARCHAR(24) NOT NULL,
  reason_code VARCHAR(80),
  correlation_id VARCHAR(100) NOT NULL,
  safe_details TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_user_created ON audit_events(user_id, created_at DESC);