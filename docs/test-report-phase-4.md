# Phase 4 verification report

Date: 2026-10-21; updated 2026-10-22 (Asia/Kolkata)

## Calendar hardening update

- The backend suite now passes **102 tests** (up from the 98-test run below), including exchange-holiday/weekend session checks, next-session holiday skipping, unsupported-year fail-closed behavior, and risk/calendar error handling.
- Checkstyle reports **0 violations**. OpenAPI route/schema and mock-only contract checks pass with the updated 503 responses.
- The demo calendar covers verified full-day NSE/BSE equity cash and equity-derivatives closures from 2025-11-05 through 2026-12-31; it remains unsuitable for live orders. See [market-calendar.md](market-calendar.md).

## Passed

- `pnpm typecheck`: shared dashboard, Next.js PWA, Chrome extension, and desktop TypeScript checks passed.
- `pnpm test:backtest`: **5 tests passed**. CSV checks cover timestamp interpretation and session bounds, strict row shape, impossible calendar dates, empty volume, ordering/gaps, tick/OHLC validity, deterministic simulation, sizing/cost assumptions, and invalid configuration bounds.
- `pnpm build`: Next.js production build and Chrome extension and desktop Vite bundles completed successfully. Bundles are at `apps/extension/dist` and `apps/desktop/dist`.
- Backend Maven suite: **98 tests passed**, 0 failures, 0 errors on Java 21; Checkstyle reported 0 violations. This includes analysis timeframe aggregation, rights gating, synthetic-signal suppression, the internal client failure boundary, and OpenAPI route/reference/live-safety contract checks.
- `docker compose --env-file .env.example -f infra/docker-compose.yml config --quiet`: Compose configuration parsed successfully.
- Browser preview: the PWA login screen rendered; the password field is singular and the login/signup controls are present.

## Not verified in this environment

- Docker Desktop's Linux engine was unavailable, so PostgreSQL, Redis, backend, and analysis containers were not started together. PostgreSQL migration execution against PostgreSQL and full signup/login/order browser flows remain to be verified after Docker is running.
- The Chrome extension was type-checked and production-built, but not loaded into Chrome. Add its generated extension ID to `.env` before using it with the local API.
- No licensed market-data adapter exists. The market chart stays synthetic. The analysis bridge is wired and verified, but its sample result is visibly simulated and non-actionable, with bias and zones suppressed. Backtest UI compiled and its parser/simulation were unit-tested, but no user history file was imported.
- Cloud hosting remains design-only. See [deployment options](deployment-options.md); hosted sessions and operational security work are still required.

## Mock lifecycle follow-up — 2026-10-21

- Backend HTTP integration now exercises signup, mock order acceptance, kill-switch cancellation of that open order, rejection of a new order while the switch is active, and safe deactivation while retaining the cancelled order.
- The full backend suite passes **107 tests, 0 failures, 0 errors, 0 skipped; 0 Checkstyle violations**. The new integration test is deterministic at 10:00 IST on the supported 2026-10-21 demo session.
- At that point, the browser still needed a visual/keyboard pass through order review, confirmation, cancellation, and responsive authenticated views. PostgreSQL/Redis and extension-in-Chrome runtime checks remain unverified.
## Cancellation ambiguity follow-up — 2026-10-21

- The mock kill switch now treats only an explicit CANCELLED adapter result as confirmed. An UNKNOWN response leaves the order OPEN/unresolved and keeps the switch active.
- Latest backend verification: **108 tests passed, 0 failures, 0 errors, 0 skipped; 0 Checkstyle violations**.
- Broker cancellation and restart reconciliation are still not verified against a live broker or PostgreSQL/Redis.
## Authenticated browser screen sweep — 2026-10-22

- With a temporary local mock account, Overview, Scanner, Backtest, Reference P&L, Trade journal, and Risk & discipline loaded in the PWA. No browser console warnings or errors were recorded during the sweep.
- The scanner disclosed that no licensed feed, verified universe rights, or backend scan route is connected and ran no scan. Backtest and Reference P&L required the local-file usage-right acknowledgement and clearly kept imported data in the tab.
- The order ticket exposed the four required discipline checks; Review stayed disabled while unchecked. These checks are user attestations, so the QA session did not mark them or submit an order.
- Desktop-width accessibility-tree inspection only at that point; authenticated 360 px/mobile visual QA, full keyboard order-dialog interaction, extension loading in Chrome, and local-file import flows had not yet been checked.
- Temporary local account was signed out. The local-demo H2 database was restarted on 2026-10-22 to remove the temporary account; no mock order was created in that browser session.

## Order-ticket clarity update — 2026-10-22

- Added an English/Hindi simple/advanced view toggle to the shared mock-order dialog. Advanced details expose the account, instrument, side, mock order mode, synthetic reference price, quantity, protective stop, estimated notional/risk, lot/tick sizes, and current backend quantity/notional/per-trade-risk limits. Core checkboxes, mock-only labeling, warnings, and final confirmation remain present in either view.
- The English/Hindi toggle now updates the document language; language buttons expose their selected state and their own pronunciation language.
- The four-workspace TypeScript check passed, all **40 dashboard tests passed**, and `pnpm build` completed for the Next.js PWA, Chrome extension, and desktop Vite bundle.
- Browser verification of the changed dialog was not performed in that turn: the browser URL policy rejected observation of the local `127.0.0.1` page. Authenticated mobile and keyboard flows were still pending then; no test order was submitted.

## PWA API-origin hardening — 2026-10-22

- The PWA uses a `127.0.0.1` HTTP default only in development. Production accepts HTTPS or an explicitly configured same-origin path; absent, malformed, credential-bearing, query-bearing, and non-loopback HTTP targets fail closed without silently targeting the visitor's localhost.
- Five API-origin tests cover development defaults, production defaults, HTTPS/relative paths, loopback-only HTTP, and unsafe/malformed values. Four-workspace typecheck and the three-surface build passed after the change.
- A build using the harmless `https://api.example.test/v1` value confirmed Next.js inlines the configured public endpoint; the normal no-env production build was then restored and checked to contain neither that test endpoint nor an HTTP localhost API origin.
- The Chrome side panel now targets the same IPv4 loopback address as the backend and requests only that host permission; the redundant `localhost` alias was removed from its content security policy.
- Two extension tests verify the fixed API origin and exact host-permission/CSP set. Extension typecheck and Vite production build passed after narrowing access.

## Order timestamp timezone hardening — 2026-10-22

- Recent mock-order times now use the shared IST date formatter instead of the machine-default timezone. Invalid API timestamps render as unavailable rather than throwing during page render.
- Two tests verify the expected IST instant under Honolulu and London host timezones and verify invalid input handling. Dashboard tests now pass **42/42**; the full workspace typecheck and three-surface production build passed.

## Mock-order status disclosure — 2026-10-22

- Replaced the incorrect claim that the backend simulates fills. The order panel now states that accepted mock API requests remain status-only (`OPEN` until cancelled), create no fills or positions, and directs users to Backtest → Paper Replay for modeled execution.
- Dashboard tests passed **42/42**, workspace typecheck passed, and `pnpm build` completed for the PWA, extension, and desktop bundles.

## Server-managed price alerts — 2026-10-22

- Price rules and in-app trigger events now persist in the backend and are scoped to the authenticated account. Evaluation runs only on a newly committed finalized one-minute candle from the configured rights-confirmed provider. Synthetic, stale, future, incomplete, wrong-source, gapped, or master-version-discontinuous data cannot create a trigger.
- Full Java 21 backend verification passed: **116 tests, 0 failures/errors/skips; package succeeded; Checkstyle reported 0 violations**. The alert-specific evaluator and H2 authenticated lifecycle tests passed, along with the OpenAPI contract check.
- Dashboard tests passed **46/46**, shared contract tests **5/5**, all four workspace typechecks passed, and `pnpm build` completed the Next.js website, Chrome extension, and desktop Vite bundles.
- These checks use H2 and mocked candles. PostgreSQL migration/runtime, a licensed provider, real feed behavior, visual browser alert display, desktop native interaction, and external delivery channels remain unverified.

## Zone-touch alerts — 2026-10-22

- Added one-shot user-defined price ranges. The lower and upper bounds must be positive and strictly ordered; price-crossing and zone rules cannot mix direction/range fields. The database enforces the same rule shape.
- A zone triggers only if a continuous approved 60-second candle overlaps the range after the preceding close was outside. First candles, gaps, session breaks, master-version changes, unsupported timeframes, and candles already within the zone do not invent a touch. Existing source-rights/freshness gates remain mandatory.
- The authenticated H2 lifecycle test verified zone rule persistence, paused-by-default behavior, invalid-range and contradictory-field rejection, owner isolation and deletion; it also confirms old price-only clients that omit `ruleType` remain compatible. Eight evaluator tests cover entry, in-zone baseline, one-minute-only enforcement and the existing source/continuity failure cases; the OpenAPI test checks the zone schema.
- Full Java 21 backend verification passed **119 tests, 0 failures/errors/skips, package succeeded, Checkstyle 0 violations**. Dashboard passed **46/46**, API-client contracts **5/5**, all workspace typechecks passed, website/extension/desktop production builds passed, and the rebuilt desktop bundle passed its **5/5** trust-boundary suite.
- PostgreSQL migration/runtime, live licensed provider, visual alert display, and external delivery channels remain unverified.

## Volume-spike alerts — 2026-10-22

- Added one-shot server alerts that compare a finalized one-minute candle's volume with the average of the 20 immediately preceding consecutive finalized provider candles. The 20-candle baseline must match the configured source and instrument-master version and remain inside supported regular-session minutes. Missing intervals, session breaks, insufficient history, changed source/master, invalid volume, and a zero-volume average wait without producing an event. Volume multiplier defaults to **2.00** and is bounded to **1.10–10.00**.
- Added rule/event schema fields and database shape checks, an indexed history query, API request validation, bilingual dashboard controls, and event display with current/reference volumes and multiplier. Existing price-only and zone-touch rule payloads remain supported.
- Focused checks passed: H2 alert lifecycle/evaluator **16/16**, OpenAPI **1/1**, shared API client **5/5**, dashboard **46/46**; all workspace typechecks and `pnpm build` passed. Full Java 21 Maven regression passed **127 tests, 0 failures/errors/skips**; package succeeded; Checkstyle reported **0 violations**.
- Dedicated migration-shape test passed **1/1** in H2 PostgreSQL mode: it loaded V6, applied V7-equivalent constraints with PostgreSQL constraint names, then ran V8 and checked legacy-row preservation plus valid/invalid volume and zone rows. This is useful shape validation, but it is **not** a Flyway run against PostgreSQL. PostgreSQL migration/runtime, live licensed-feed behavior, visual browser alert display, external channels, indicator/order-fill alerts, and calendar coverage beyond the verified 2026 demo year remain unverified.

## Authenticated mobile visual follow-up — 2026-10-22

- At 360 px, authenticated Overview, Scanner, Backtest, Reference P&L, Trade journal, and Risk & discipline were visually reviewed in Hindi; sign-in and sign-up were reviewed in both English and Hindi. No horizontal overflow appeared.
- The scanner now uses localized UI copy when the backend reports that no licensed provider is configured; its English backend status no longer leaks into the Hindi UI. The visible page continued to show fail-closed status, no scan, no licensed data, and no synthetic candles.
- The simple/advanced mock-order dialog fit the mobile viewport. Escape closed it and restored focus. All four user attestations stayed unchecked and Review remained disabled; no mock order was submitted.
- Dashboard verification passed **53/53 tests**, `pnpm --filter @algotrade/dashboard typecheck` passed, and `pnpm --filter @algotrade/website build` passed using an isolated Next.js output directory. This does not verify Docker/PostgreSQL/Redis, browser order submission/cancellation, the Chrome extension runtime, live market data, or broker execution.

## Analysis indicator values in the dashboard — 2026-10-22

- Carried the shared Python engine's EMA 20/50/200, ADX 14, RSI 14, MACD/signal/histogram, ATR 14, and average/relative volume context through the internal Java response, authenticated Spring API, OpenAPI schema, shared TypeScript contract, and Hindi/English Analysis card. No indicator formulas were duplicated. Missing warm-up values stay `null` and render as `—`; malformed/missing internal indicator fields fail closed. Synthetic data still suppresses bias, zones, and scenarios and cannot trigger alerts.
- Verification: Java 21 Maven suite **129 passed, 0 failures/errors/skips**; Checkstyle **0 violations**; dashboard tests **49/49**; all workspace typechecks and website/extension/desktop production builds passed. The Next.js production build also passed using an isolated `distDir` while the development app used its default `.next` folder.
- The dashboard is only showing historical indicator calculations over the existing synthetic fixture. Licensed provider rights, fresh-data runtime, indicator-based alert rules, authenticated visual/keyboard QA, and PostgreSQL execution remain unverified.

## Alert storage-rights gate — 2026-10-22

- Added `ALERTS_STORAGE_RIGHTS_CONFIRMED`, default `false`, to the alert market-data gate. Evaluation now requires the configured source plus explicit persistent-storage, derived-analytics, and end-user-display confirmations; a source's display or analytics permission cannot stand in for storage permission.
- A provider-candle evaluator test verifies missing storage rights pause a rule without creating an event. Java 21 Maven suite: **129 passed, 0 failures/errors/skips**; Checkstyle **0 violations**; Spring Boot JAR package succeeded.
- This configuration is only an enforcement gate; it is not evidence that any source contract grants these rights. No provider is configured, and real PostgreSQL/market-data integration remains unverified.
