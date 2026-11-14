# Phase 4 — server-managed alerts

Date: 2026-10-22 (Asia/Kolkata)

## Implemented scope

- Authenticated users can create, list, and delete server-persisted one-shot `ABOVE` and `BELOW` price-crossing rules, user-defined `ZONE_TOUCH` ranges, and `VOLUME_SPIKE` rules. In-app trigger events are durable, account-scoped, and can be marked read.
- The backend evaluates rules only when the market-candle writer commits a new finalized 60-second candle; other timeframes are paused as unsupported. A first eligible candle establishes the baseline; a later price crossing or zone entry in the next continuous candle with the same instrument-master version triggers the rule. A gap, a session break, or a master-version change resets the baseline without inventing a trigger.
- Triggered rules become inactive. A database uniqueness constraint on `(rule_id, candle_time)` prevents a duplicate in-app event for the same candle. The per-account active-rule limit is 100; the event list is capped at 100 entries per request.
- A volume-spike rule compares the current finalized candle with the arithmetic mean of its previous 20 consecutive finalized 60-second candles. Every baseline candle must be from the configured source, carry provider quality, match the current instrument-master version, and fall inside a supported regular session. Missing intervals, session breaks, source/master changes, short history, or zero average volume wait without triggering. The default multiplier is 2.00; accepted values are 1.10–10.00. Events store the current volume, reference average, multiplier, and lookback count.
- The browser uses the authenticated server API for rule CRUD and the in-app event list. It no longer stores price rules in `localStorage`. Journal entries remain browser-local.

## Data and delivery gate

All alert data is denied by default. To permit evaluation, an operator must set all of `ALERTS_MARKET_DATA_ENABLED=true`, a non-empty `ALERTS_SOURCE_ID`, `ALERTS_STORAGE_RIGHTS_CONFIRMED=true`, `ALERTS_DERIVED_RIGHTS_CONFIRMED=true`, and `ALERTS_DISPLAY_RIGHTS_CONFIRMED=true` only after written source terms have been reviewed for those specific uses. Storing provider candles in the backend requires its own storage-rights confirmation; derived analytics and showing alert events are separate uses. The event candle must also be finalized, have quality `PROVIDER`, match the configured source ID, not be future-dated, and close within the fixed 120-second freshness window. Synthetic, stale, incomplete, future, or mismatched-source candles pause the rule and cannot emit a notification. The current sample source fails this gate, so rules remain paused and live alert events remain zero.

Configuration is necessary but is not proof that a source contract grants the required rights. No provider is configured by this phase. Do not set these flags to enable synthetic samples or before the data-rights review is complete. See `.env.example`; each rights confirmation defaults to `false`.

## API

- `GET /api/v1/alerts`, `POST /api/v1/alerts`, `DELETE /api/v1/alerts/{id}` — account-owned rules.
- `GET /api/v1/alerts/events?limit=50`, `PATCH /api/v1/alerts/events/{id}/read` — account-owned in-app events.
- Price-crossing rule creation accepts `instrumentId`, `ruleType=PRICE_CROSSING`, `direction`, and a positive decimal `price`. A zone-touch rule accepts `ruleType=ZONE_TOUCH`, `price` as the inclusive lower boundary, and `upperPrice` as an inclusive upper boundary strictly greater than `price`; direction must be absent/null. A volume-spike rule accepts `ruleType=VOLUME_SPIKE` and an optional `volumeMultiplier` (default 2.00; range 1.10–10.00); price and direction fields are rejected for this type. Invalid or inactive instruments are rejected. Attempts to inspect, read, or delete another account's rule/event return not found.
- The OpenAPI document is `docs/openapi.yaml`. Existing price-only request bodies remain compatible; `ruleType` omitted by an older client defaults to `PRICE_CROSSING`.

## Explicit limitations and next work

- Implemented conditions: one-shot price crossing, one-shot user-defined zone entry, and one-shot volume spike. Volume history requires 20 continuous regular-session candles, exact source/master continuity, and a positive average. The active session calendar is the verified 2026 demo calendar; provider dates outside its supported coverage fail closed until a maintained live-market calendar is configured. Indicator, order-fill, and recurring rules are not implemented.
- Implemented channel: in-app only. Web push, email, Telegram, quiet hours, external provider retries, and channel-specific delivery rate limits are not configured. No external message is sent by this phase.
- There is no notification if the app or market-data service is offline; the persisted server event is created only when an eligible candle is committed. The alert path does not run from browser polling.
- PostgreSQL migration execution, licensed-feed behavior, authenticated visual QA, and end-to-end alert display on a real authorized data feed remain unverified. H2 HTTP/API and deterministic evaluator checks pass.

## Verification

Focused backend checks cover owner isolation, authentication, validation, rule limits and paused-without-rights response, event lifecycle, fresh-provider price crossing and zone entry, volume-spike calculation and history rejection, stale/future/synthetic suppression, and continuity/master-version resets. The shared API client has a dedicated request/auth test. See the current Phase 4 and Phase 1 reports for command results.
