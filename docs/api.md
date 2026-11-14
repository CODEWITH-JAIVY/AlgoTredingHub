# Backend API

The machine-readable OpenAPI 3.1 contract is maintained in [openapi.yaml](openapi.yaml). Keep it aligned with controller DTOs and mock/live safety boundaries when changing routes.

All paths are under `/api/v1`. Except auth endpoints and health/info, endpoints require a valid bearer access token. Errors use safe Problem Details with a stable `code`; correlation ID is returned in `X-Correlation-ID`.

| Method | Path | Purpose |
|---|---|---|
| POST | `/auth/signup` | Create local account and default risk controls |
| POST | `/auth/login` | Authenticate and issue access/refresh tokens |
| POST | `/auth/refresh` | Rotate opaque refresh token |
| POST | `/auth/logout` | Revoke the supplied refresh token for the authenticated account |
| GET | `/brokers/adapters` | List mock and Zerodha boundary status |
| GET | `/market/instruments` | List synthetic demo instrument catalogue |
| GET | `/market/quotes` | Return fixed demo prices marked `SIMULATED`, never fresh/current |
| GET | `/market/data-status` | Report enabled sources and licensed-feed availability |
| GET | `/market/candles` | Read bounded one-minute history with freshness, quality, master-version, and gap metadata |
| GET | `/analysis/{instrumentId}?timeframe=5m&from=...&to=...&source=SYNTHETIC_DEMO` | Analyze bounded derived timeframes through the private service; synthetic is always non-actionable and provider rights default off |
| GET | `/orders` | List only the authenticated user's orders |
| POST | `/orders` | Risk-check and submit a mock order |
| POST | `/orders/{id}/cancel` | Cancel an open mock order owned by the user |
| GET | `/risk/policy` | Read active user risk limits and any scheduled relaxation |
| PUT | `/risk/policy` | Apply tighter values immediately and schedule relaxed values for the next supported demo session |
| GET | `/risk/kill-switch` | Read persistent account kill state and unresolved open mock-order count |
| PUT | `/risk/kill-switch` | Activate the kill switch and attempt open mock-order cancellation; deactivation is rejected with `409 KILL_SWITCH_UNRESOLVED_ORDERS` until all open, partially filled, and unknown orders are resolved |
| GET | `/actuator/health` | Liveness/readiness health status |

## Risk policy request

`PUT /risk/policy` requires all risk and daily-discipline values:

```json
{
  "maxQuantity": 100,
  "maxOrderNotional": "100000.00",
  "maxOpenOrders": 5,
  "maxDailyLoss": "5000.00",
  "maxTradeLoss": "1000.00",
  "maxOrdersPerDay": 10,
  "noNewOrdersAfter": "15:00",
  "noOrdersFirstMinutes": 5
}
```

The daily cap counts accepted mock order submissions. An accepted order remains counted if later cancelled; an identical idempotent retry does not count twice, and a broker-rejected mock submission does not count. Orders are blocked outside supported regular sessions, during the configured opening delay, and at or after the cutoff. The demo calendar includes verified NSE/BSE cash and equity-derivatives full-day holidays from 5 November 2025 through 31 December 2026; order submission returns HTTP `503 MARKET_CALENDAR_UNAVAILABLE` outside that coverage. Special sessions, early closes, unscheduled halts, and instrument-specific exceptions are not modeled. These controls are for the mock environment and cannot authorize live orders; see [calendar coverage](market-calendar.md).

`APP_SINGLE_USER_MODE=true` is the default. Signup is serialized through a database row lock and closes after the initial account transaction commits. Later attempts return `409 SIGNUP_CLOSED`. Existing accounts can still log in. Set the environment variable to `false` only when intentionally provisioning a multi-user deployment.

## Place mock order request

```json
{
  "clientOrderId": "demo-order-0001",
  "instrumentId": "<UUID from /market/instruments>",
  "side": "BUY",
  "orderType": "MARKET",
  "quantity": 1,
  "protectiveStopPrice": 95.00
}
```

`LIMIT` requires `limitPrice`; `STOP_MARKET` requires `triggerPrice`; `STOP_LIMIT` requires both. Every price must align to the instrument tick. F&O quantities must be whole lot multiples. All demo prices and order acknowledgements are synthetic.

## Synthetic candle demo

`GET /api/v1/market/data-status` returns source state and the fixed demo time range. The fixture runs from `2000-01-03T03:45:00Z` to `2000-01-03T05:45:00Z` (09:15–11:15 Asia/Kolkata). Request it with `GET /api/v1/market/candles?instrumentId=<UUID>&timeframe=1m&from=2000-01-03T03:45:00Z&to=2000-01-03T05:45:00Z`.

The response labels these candles `source: SYNTHETIC_DEMO`, `freshness: SIMULATED`, `fresh: false`, and includes `SYNTHETIC_DATA_NOT_FOR_MARKET_DECISIONS`. Other ranges return `NO_DATA` and `LICENSED_SOURCE_NOT_CONFIGURED`; no sample bars are moved to today's date. Only finalized `1m` candles are supported. Missing intervals are returned as gaps and are never padded with made-up OHLC values.

Historical import is an internal provider contract only. No provider is registered, and a future provider must have written persistent-storage and end-user-display rights before its adapter can run. Import windows must start/end on minute boundaries, and the job cannot report complete until the provider confirms full range coverage; cursor exhaustion alone is insufficient. Redistribution and derived analytics remain separate permissions. See [Phase 2 data design](phase-2-plan.md).

## Analysis endpoint

`GET /api/v1/analysis/{instrumentId}?timeframe=5m&from=<ISO-8601 instant>&to=<ISO-8601 instant>&source=<source-id>` requires a user access token. Supported output timeframes are `5m`, `15m`, `1h`, and `1d`; the backend reads finalized 1-minute candles and builds exchange-aligned full bars before calling the private Python service. For a provider source, `to` anchors a separate 350-calendar-day lookback; the chart-candle endpoint remains capped at seven days. The analysis query omits an unfinalized current minute and sends at most 5,000 aggregated candles per timeframe to the engine. Partial bars are dropped, gaps are not filled, and an unavailable requested timeframe returns `422 ANALYSIS_TIMEFRAME_UNAVAILABLE`. Unsupported timeframe codes return `422 UNSUPPORTED_ANALYSIS_TIMEFRAME`.

The dashboard currently requests only `SYNTHETIC_DEMO`; no licensed provider is connected. Synthetic analysis is always returned with `signalStatus: SUPPRESSED`, `actionable: false`, `bias: NEUTRAL`, and `zoneCount: 0`, regardless of the Python response. The backend provider path remains disabled unless the exact source ID and separate server-side storage/cache/display/derived-use rights are configured; client-supplied source text does not grant rights. Provider output is independently suppressed for stale or incomplete history, session gaps, an instrument-master boundary, or unverified corporate actions. The response includes EMA 20/50/200, ADX 14, RSI 14, MACD, ATR 14, and 20-candle volume-context values. A `null` value means valid warm-up history is unavailable; the UI displays it as `—`, never as zero. These sample values do not enable alerts or orders. A missing/inactive instrument returns `404 INSTRUMENT_UNAVAILABLE`, missing candles return `422 ANALYSIS_DATA_UNAVAILABLE`, and Python timeout, malformed output, or identity mismatch returns a safe `503 ANALYSIS_UNAVAILABLE`. The internal service token and raw engine payload are not returned to the browser.

## Response safety

Never interpret `mode: MOCK` or `status: OPEN` as a real broker order. The Phase 1 API does not expose a live-order route.

Risk-policy responses keep active limits at the top level. A non-null `pending` object contains only fields being relaxed plus an `effectiveOn` date. The demo calendar skips weekends and verified holidays from 2025-11-05 through 2026-12-31; outside its coverage, scheduling fails closed. Special sessions, early closes, and halts are not represented, and the demo calendar is not suitable for enabling live orders. Until `effectiveOn`, the backend checks orders against the active top-level limits. Submitting a field at its current active value cancels that field's pending relaxation.
