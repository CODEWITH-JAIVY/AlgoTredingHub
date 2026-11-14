# Phase 4 — Web and Chrome MVP

## Product surfaces

- `apps/website`: Next.js 16 responsive web app with an installable PWA manifest, public app icon, and a service worker that caches only those two public assets.
- `apps/extension`: Chrome Manifest V3 side-panel extension (Chrome 116+) that opens from the toolbar and shares the same React dashboard.
- The product landing page is `/`; the existing mock-first workspace remains at `/app`; `/install` explains loading the developer preview ZIP. The PWA manifest opens `/app`.
- The extension top switcher exposes the paper workspace and broker setup guide. Zerodha, Angel One, Upstox and Dhan are documented as roadmap items only; there is no broker credential form, live feed or live order action.
- `packages/dashboard`: one client UI, instrument/watchlist, synthetic candle chart, order confirmation and discipline checklist, sizing calculator, risk controls, server-managed price-alert rules, browser-local journal, and an imported-data-only backtest.
- `packages/contracts`: typed backend API client and response/request shapes.

The dashboard uses the local Spring API and never calls the internal Python analysis service or a broker directly. The authenticated Spring API forwards eligible analysis requests to the private service; its internal token stays server-side. The service currently receives only the synthetic historical fixture dated 03 January 2000. The UI labels the result as simulated and non-actionable, and never shows synthetic bias or zones. The backend derives complete 5m/15m/1h/1d bars for analysis; this does not add higher-timeframe chart data or imply licensed/current prices.

The extension ZIP is a developer preview for local testing only. It must be built from `apps/extension` and packaged with `pnpm package:extension-preview`; the downloadable ZIP requires the local backend at `127.0.0.1:8080`, Chrome Developer mode and a configured extension origin for local CORS. It is not a Chrome Web Store release or a hosted customer product. Paper orders are handled by the mock adapter; a mock `OPEN` acknowledgement does not mean a simulated fill, position or broker order. No live-order endpoint is available.

## Start locally on Windows

From the repository root:

```powershell
pnpm install
pnpm --filter @algotrade/website typecheck
pnpm --filter @algotrade/extension typecheck
pnpm --filter @algotrade/website test
pnpm --filter @algotrade/extension test
pnpm build
```

On Windows hosts where pnpm cannot use links, run `pnpm install --store-dir .work/pnpm-store --package-import-method=copy` from the root instead.

Create local backend secrets if `.env` does not exist:

```powershell
.\scripts\setup-local.ps1
```

Start the database/backend/analysis stack:

```powershell
docker compose --env-file .env -f infra/docker-compose.yml up --build
```

Run the PWA in a second terminal and open `http://localhost:3000`:

```powershell
pnpm dev:web
```

The PWA defaults to `http://127.0.0.1:8080` only in development. For a production build, set the public `NEXT_PUBLIC_API_BASE_URL` to an HTTPS API origin or a same-origin path such as `/api` before running `next build`. This value is compiled into the browser bundle and must never contain credentials or secrets. Missing or invalid production configuration does not fall back to HTTP localhost.

Create the extension bundle:

```powershell
pnpm --filter @algotrade/extension build
```

Open `chrome://extensions`, enable Developer mode, and load `apps/extension/dist`. Copy the generated extension ID into `.env` as `chrome-extension://<id>` in `CORS_ALLOWED_ORIGINS`, then restart Compose. CORS uses exact origins. The extension API target and host permission are restricted to `http://127.0.0.1:8080`; it has no content script, tab access, broker credentials, or background network polling.

Create the initial local account, then use that account in both surfaces. Single-user registration is enabled by default and is closed atomically after the first successful signup. Tokens are held in tab-scoped `sessionStorage`; closing a browser tab ends that surface's session. The extension and PWA use separate session keys. Journal data is browser-local; alert rules and in-app events are server-persisted and account-scoped. Export the journal before clearing browser data.

## Behavior and safeguards

- Every order needs a separate review step and a second confirmation. Requests use a stable `clientOrderId`; a retry after an ambiguous network failure reuses that key. The backend remains wired to `MockBrokerAdapter` and does not expose live order submission.
- The mock-order API records an accepted request as `OPEN` and does not create execution fills or positions. The order panel keeps this distinction visible; modeled fills and positions are available only in the local Backtest → Paper Replay workflow.
- The shared order dialog provides simple and advanced views in English and Hindi. Expanded details show the account, instrument, side, mock mode, synthetic reference price, quantity, stop, estimated notional/risk, lot/tick sizes, and active backend limits; the checklist and final confirmation stay required in both views. The language control exposes its selected state and updates the document language for assistive technology.
- Recent mock-order timestamps render with the explicit Asia/Kolkata timezone even when the browser machine uses another timezone; invalid timestamps show as unavailable.
- Quantity is constrained by the selected lot size, account quantity cap, notional cap, and suggested per-trade risk budget. Instrument prices shown in this phase are synthetic. The order preview labels its reference as simulated.
- The order review requires four separate acknowledgements (written setup, protective stop, quantity and rupee risk, and mock-data awareness) before the second confirmation. The backend independently validates its order and risk rules.
- The review also states that live zone freshness, trend agreement, risk:reward, event risk, loss cooldown, and daily-loss lock are not evaluated because there is no licensed feed or authoritative broker fill/P&L source. Backend checks available here are mock limits and time gates only; this disclosure is not a live pre-trade approval.
- No automatic order retry is performed. The API client only attempts refresh-token rotation and one retry for a safe GET request. Mutating requests are never replayed automatically.
- Price alert rules are server-persisted and one-shot. The server can evaluate only finalized one-minute candles that pass the configured provider-rights and freshness gate; all gates default off, so today's rules are paused. Synthetic samples cannot trigger them. In-app events are durable; external channels are not configured. See [Phase 4 alerts](phase-4-alerts.md).
- Journal outcomes and P&L are manual notes, not broker fills or reconciled performance. The journal captures optional IST trade time, quantity, planned/actual stops and targets, and an explicit stop-removal acknowledgement; its rolling 7-day/30-day behavior review groups only user-reported values and never gates orders. Browser storage and JSON export are user-controlled.
- Journal entries may include a self-reported emotion tag. It is not inferred from trading activity and does not claim to measure discipline. Automatic trade capture, entry/exit screenshots, and analysis snapshots are unavailable until a permitted data source and authoritative fill lifecycle exist.
- Backtesting accepts only the user's local CSV file and never uploads or persists the data. It requires 100–50,000 complete one-minute OHLCV rows, at most 5 MB, strictly ordered unique timestamps, valid OHLC/tick/volume values, and regular-session IST timestamps. Intraday gaps fail closed; gaps between dates are allowed. A data-rights checkbox is an acknowledgement, not a rights check.
- The example is a deterministic long-only EMA 20/50 crossover with a 2×ATR stop, next-bar-open entries, configurable per-side fee and slippage estimates, whole-lot risk sizing, and session-end close. It is a browser-side educational simulation; it does not use the demo feed or backend analysis.
- The backtest page includes a tab-local paper execution replay workflow for market, limit, and stop-market orders. It models mandatory next-bar eligibility plus configurable latency, shared participation limits and partial fills, adverse spread/slippage on marketable orders, estimated fees, close-time cancellation, DAY/GTC behavior, and end-of-history expiry. It bounds each replay to 50 staged UI orders, five million order/bar operations, and 50,000 accounting fills. Orders and fills stay in browser memory; an optional JSON export contains order/fill/P&L results but not the CSV. Simulated fills feed the shared FIFO engine for realized P&L and open-position mark-to-market at the final imported close. The open net mark subtracts entry fees and excludes estimated exit costs. This replay never calls the mock-order endpoint or a broker.
- Malformed local journal entries are ignored and journal storage is capped at 2,000 entries. Server limits are 100 active alert rules and 100 events per list response.
- Risk policy and kill-switch state are backend-persisted per account. Activating the switch asks the backend mock adapter to cancel open mock orders and reports unresolved order count.
- Risk policy settings include a server-enforced maximum of accepted mock order entries per IST day, an opening delay, and a latest new-order time. Tightening applies immediately; relaxing waits for the next demo trading date. The daily counter is serialized by a database row lock and is not incremented for idempotent retries or explicit broker rejects. Accepted submissions still count if later cancelled. Until fills exist, this is an order-entry cap, not a completed-trade count.
- The public service worker never reads tokens, caches application API calls, or stores private account responses.

## Current boundaries

- Market-data provider rights are not confirmed; no licensed provider adapter is registered.
- Only the backend's 1-minute demo candles can be charted. The analysis workflow is wired end-to-end but synthetic output remains simulated and non-actionable, with bias and zone details suppressed. Higher-timeframe charts, live quotes, zone overlays, and actual alert evaluation remain withheld until eligible data and their workflows exist. The browser backtest only accepts user-selected CSV history; no licensed history is bundled.
- Backtest assumptions omit taxes and statutory charges, margin/shorting, corporate actions/dividends, exchange holidays and special sessions, benchmark comparison, walk-forward validation, survivorship-bias correction, and broker-specific execution. The selected instrument's lot/tick metadata must match the imported file; the app cannot verify that match.
- Paper replay calculates provisional local FIFO P&L but does not persist positions, reserve capital, enforce portfolio-wide risk, reconcile orders, or model exit charges against open inventory. OHLC bars cannot model quote queues or intrabar event ordering. Browser visual QA of the authenticated replay page remains pending while the Docker-backed API is unavailable.
- The mock account's policy, order state, alert rules, and in-app alert events are server-side. Journal entries remain browser-local and do not synchronize across devices.
- The mock session calendar covers regular 2026 sessions and verified full-day holidays only; it omits special sessions, early closes, unscheduled halts, and broker-specific hours. The separate CSV backtest still does not apply exchange calendars. Loss-streak cooldown and loss-based daily lock still need authoritative fill and realized-P&L events; manual journal P&L is not used to gate orders. See [market-calendar.md](market-calendar.md).
- No real pre-trade gate currently evaluates live zone freshness, trend alignment, reward-to-risk, event risk, loss cooldown, or daily realized-loss lock. Those checks stay unavailable until their licensed/authoritative data inputs and backend enforcement exist; the order-review warning describes this limitation in English and Hindi.
- `APP_SINGLE_USER_MODE=false` allows additional signups for a future multi-user deployment. Keep it enabled for the current private single-account setup.
