# Phase 1 architecture

## Deployment decision

Develop and demo locally first. Preserve a user/account ownership boundary so a later hosted deployment does not require rewriting every table or endpoint. Local Compose is the supported Phase 4 setup. A cloud private-pilot design, its requirements, and its blockers are documented in [deployment options](deployment-options.md); cloud infrastructure, hosted sessions, tenant isolation, key management, and broker-network posture are not implemented.

## Components

- `services/backend`: Java 21, Spring Boot 4.1.1, REST API, stateless bearer-token security, risk manager, audit trail, and broker boundary.
- `services/analysis`: Python 3.12, FastAPI, pandas/NumPy analysis engine. It accepts only internal authenticated, validated candle requests and is not exposed to browsers.
- PostgreSQL: durable accounts, refresh-session hashes, risk policy/state, synthetic instruments, mock orders, and audit events.
- Redis: authentication rate-limit counters. Auth endpoints fail closed with 503 if Redis is unavailable, returns no counter, or cannot confirm the counter expiry; a later request repairs a counter left without TTL by an interrupted first request.
- `MockBrokerAdapter`: only order adapter injected into the order service.
- `ZerodhaAdapterBoundary`: provider descriptor only; no network, credentials, or live order implementation.
- `apps/website`, `apps/extension`, and `apps/desktop` remain reserved for Phase 4+; the Phase 3 analysis service is available as a private Compose container.

## Trust boundaries and auth

- Access JWT lifetime: 10 minutes. Refresh tokens are random 48-byte opaque values; only SHA-256 hashes are persisted. Refresh rotates the token and uses an atomic database update so a token cannot be rotated twice. Reuse or a concurrent replay revokes every active refresh session in that token family; unavailable accounts revoke all their sessions.
- Passwords use BCrypt cost 12. Passwords must contain 12–72 characters and no more than 72 UTF-8 bytes.
- API uses bearer tokens only; no cookie authentication is configured, so CSRF protection is disabled only for this stateless API. Logout requires authentication. Refresh-token clients must protect the token; the web/extension client storage design is deferred to its UI phase.
- Auth signup/login/refresh are rate limited using Redis counters and a 15-minute window. Redis loss disables auth routes rather than silently removing throttling.
- CORS uses an explicit origin allowlist. It does not allow credentials.
- Correlation IDs are validated or regenerated and returned in `X-Correlation-ID`.

## Order safety invariants

- Only synthetic instruments are orderable.
- Order adapter is hard-wired to `MOCK`, and the DB rejects any order mode other than `MOCK`.
- Every order requires a protective stop; quantity, lot size, tick size, notional, per-trade stop exposure, daily loss plus reserved open risk, open-order count, active account, and kill switch are checked server-side.
- Daily discipline limits are checked server-side in Asia/Kolkata time: no new orders outside supported regular sessions, during the configured opening delay, after the configured cutoff, or above the daily accepted-order-entry cap. The demo calendar includes verified NSE/BSE cash and F&O full-day holidays from 2025-11-05 through 2026-12-31 and fails closed outside that coverage. The daily state row is locked during check and acceptance; same-key idempotent retries do not increment the cap, while accepted orders still count after later cancellation.
- If an immediately tightened boundary overlaps a relaxation that must wait until the next session, the active opening delay/cutoff can temporarily leave no valid entry window. In that case every new order is blocked and the policy view warns the user.
- Until a fill lifecycle exists, the daily cap counts accepted mock order submissions, not completed trades. The API and UI name this distinction. Loss-streak cooldown and realized-loss locking cannot be claimed from mock open orders and remain deferred until fills and realized P&L are authoritative.
- Orders with the same `(user_id, client_order_id)` and identical payload return the original order. Reuse with changed values is a conflict.
- Pessimistic lock on the user's risk policy serializes order risk reservations to reduce races where concurrent orders exceed daily/open limits.
- Tightening risk limits is immediate. A relaxation is persisted separately and takes effect at the next supported session; reads show the active limits and scheduled values, and order checks continue using active limits until that date.
- The demo calendar models regular 09:15–15:30 sessions and verified full-day holidays from 2025-11-05 through 2026-12-31. It excludes the 2026-11-08 Muhurat session and does not model auctions, early closes, unscheduled halts, or instrument-specific exceptions; it is not an authoritative live session source and cannot enable live orders.
- Mock broker status `OPEN` means simulated acknowledgement only. It is not an exchange or broker acknowledgement.
- Kill switch persists in PostgreSQL and blocks future orders. It reports cancellation attempts and unresolved mock orders; it does not claim real positions were squared off.

## Known Phase 1 limits

No real quotes, candles, fills, position reconciliation, holdings, Zerodha authentication, live orders, tax calculations, UI, or production deployment. Daily realized loss state is present for the future fill pipeline; Phase 1 creates no fills and therefore cannot calculate real realized P&L. A mock quote is fixed synthetic fixture data, not fresh market data.

## Phase 2 market-data path

- The active source is only the fixed `SYNTHETIC_DEMO` fixture. It emits no live tick stream and carries no exchange data rights.
- Incoming normalized trade ticks must match an active instrument token, exchange, and master version; align to tick size; have positive price and quantity; be within the two-minute freshness and two-second future-skew bounds; and fall within the supported regular-session demo calendar.
- A PostgreSQL receipt key deduplicates source/instrument/time/sequence and stores enough normalized data to replay the latest eight minutes after restart. Receipt rows expire after seven days.
- The one-minute aggregator orders by exchange time and sequence, accepts bounded out-of-order ticks until a two-second close grace, then rejects late ticks for reconciliation. It does not generate empty OHLC bars. Same-day missing minutes are stored as explicit gap intervals.
- Finalized bars and detected gaps are written transactionally while the per-instrument buffer is locked. A database failure leaves the batch buffered so the scheduled writer can retry. The synthetic chart sample uses a fixed 2000 date, and quote responses are never fresh/current.
- Real history import is represented by an inert interface and resumable job model. No source is registered; written rights for storage and end-user display are required before importing/displaying provider history. Redistribution and derived analytics are separate permissions. The demo calendar coverage and limits are documented in [market-calendar.md](market-calendar.md).

## Phase 3 analysis path

- `services/analysis` is a private container with no host-published port. Internal routes require a random bearer token of at least 32 characters, a 120-requests-per-minute in-process limit, bounded request sizes, bounded compute concurrency, and validated correlation IDs.
- Requests include 5m/15m/1h/1d finalized candles, one instrument-master version, current tick size, explicit data freshness/completeness, and per-source rights. Missing license-dependent metrics are reported unavailable.
- The deterministic analysis engine suppresses market bias and scenarios unless rights, freshness, completeness, and multi-timeframe agreement pass. Synthetic/stale/gapped inputs always suppress the signal.
- Cached results are content-versioned and process-local with bounded TTL; cache expiration is an explicit miss. Redis sharing and incremental recomputation are future integration decisions.
- The authenticated Phase 4 backend bridge calls the private analysis service and keeps its internal token server-side. `SYNTHETIC_DEMO` is the only connected source and its actionable fields are always suppressed. A separate provider path requires an exact server-configured source ID and individual rights flags; those flags default off, and no licensed provider is connected.
- Provider analysis uses a 350-calendar-day query with database-side `maximum + 1` bounds, checked finalized/session-complete bars, and a maximum of 5,000 engine candles per timeframe. Candle reads filter exact sources in the database; gap reads return at most 1,000 intervals and flag truncation. Unsupported calendar dates, gaps, stale data, master-version boundaries, and unverified corporate actions keep directional output suppressed.
