# AlgoTrade Hub

A phased, mock-first platform foundation for Indian market analysis and trading workflows. This repository contains the **Phase 1 backend, Phase 2 market-data foundation, Phase 3 analysis engine, Phase 4 web/Chrome MVP, local-safe Phase 5 trader-depth features, and a mock-only Phase 6 Windows desktop scaffold**. Desktop work is currently paused. Live broker orders are not implemented and cannot be submitted.

For the latest plain-language progress, verified checks, remaining work, and an explanation of disk usage, see [current project status (Hindi)](docs/project-status-hi.md).
For a copy-ready product brief that captures the current decisions and build gates, see the [AlgoTrade Hub master prompt](docs/ALGO-TRADE-HUB-MASTER-PROMPT.md).

## Phase 1 status

- Spring Boot 4.1.1 backend on Java 21.
- PostgreSQL migrations and Redis-backed authentication throttling.
- Signup/login, short-lived JWT access tokens, rotating hashed refresh tokens, logout, and refresh-token reuse detection that revokes the active token family.
- Authentication throttling fails closed if Redis is unavailable, and repairs or rejects a counter whose 15-minute expiry is missing after an interrupted request.
- Single-user mode closes signup atomically after the initial account is provisioned; additional signup requires explicitly disabling that mode.
- Broker adapter contract, synthetic mock broker, and an inert Zerodha boundary.
- Synthetic NSE/BSE cash and F&O demo instruments; all quote values are marked synthetic.
- Manual mock order submit/cancel, mandatory protective stop validation, idempotency key handling, lot/tick/quantity/notional/trade/daily risk controls, plus a verified NSE/BSE cash and F&O holiday calendar covering 2025-11-05 through 2026-12-31, opening cooldown, cutoff, and accepted-entry cap. Calendar-dependent behavior fails closed outside its covered dates. Stricter policy changes apply immediately; relaxed values remain pending until the next supported session. See [calendar coverage and limitations](docs/market-calendar.md).
- Persistent per-account kill switch; it blocks new mock orders, attempts/retries cancellation of unresolved mock orders, and refuses deactivation until they are resolved.
- Audit events, correlation IDs, health checks, Docker Compose, CI workflow, and unit plus HTTP integration tests.

## Phase 2 status

- Provider-neutral trade-tick contract with master-version, source, session, freshness, tick-size, and quantity validation.
- Restart-safe event receipts, deterministic one-minute candle aggregation, bounded out-of-order handling, explicit gaps, and retryable database writes.
- Fixed historical chart fixture dated 2000-01-03. Demo quote responses explicitly report `SIMULATED` and `fresh: false`.
- Versioned instrument master and resumable, rate-limited history-import boundary. No real source is registered until written usage rights are confirmed.

See [Phase 1 setup](docs/phase-1-setup.md), [architecture](docs/architecture.md), [threat model](docs/threat-model.md), [deployment options](docs/deployment-options.md), [API notes](docs/api.md), the [OpenAPI 3.1 contract](docs/openapi.yaml), [Phase 2 design](docs/phase-2-plan.md), [Phase 3 design](docs/phase-3-design.md), and the [edge-case register](docs/edge-case-register.md).

When Docker Desktop is unavailable, the loopback-only [Docker-free local mock demo](docs/local-demo.md) can run the Spring API with ephemeral H2 data. It is for UI practice only and does not replace PostgreSQL/Redis verification.

## Phase 3 status

- Private FastAPI analysis service with pinned Python dependencies and internal bearer-token authentication.
- Deterministic EMA/ADX/RSI/MACD/ATR/volume calculations, documented supply/demand zones, descriptive reaction statistics, and strict freshness/completeness/right gates.
- Versioned cache keys, bounded 90-second cache, concurrent-miss sharing, stale-result suppression, bounded precompute batch, internal rate limit, and p50/p95 metrics.
- The authenticated Spring backend now bridges selected-instrument analysis to the private Python service. Synthetic input can be analyzed for development, but the UI shows only a simulated, non-actionable summary; bias and zones stay suppressed until licensed rights-cleared data is configured.

## Phase 4 status

- Shared React trading workspace powers a responsive Next.js installable PWA and a Chrome Manifest V3 side panel.
- Signup/login, instruments and synthetic fixture chart, mock order review/confirmation with a four-part discipline checklist, recent mock order management, position sizing, persisted risk controls, kill switch, account-scoped server-side price-crossing, zone-touch and volume-spike alerts, local-only trade journal with self-reported behavior review, and browser-only CSV backtesting.
- English/Hindi toggle, keyboard-usable forms, explicit mock/simulated labels, risk/lot/tick/notional checks, refresh-token rotation on safe GET retry only, and duplicate-order protection through stable client order IDs.
- PWA service worker caches only its public manifest and icon. It never intercepts or caches authenticated API data.
- The dashboard requests analysis through the authenticated Spring API; the internal Python-service token never reaches the browser. Backend-derived 5m/15m/1h/1d bars are analysis inputs only, not charted or presented as licensed market data.
- No licensed data feed is configured. Server-managed price and zone alerts remain paused; synthetic historical candles cannot trigger them or produce a trading bias. Journal entries are stored in browser local storage and can be exported or deleted. Backtests use only a user-selected CSV kept in memory; the data is not uploaded or persisted.

See [Phase 4 design and local run instructions](docs/phase-4-mvp.md).
Phase 4 verification details are in the [Phase 4 report](docs/test-report-phase-4.md).

### Product landing page and Chrome preview

- `/` is the product landing page. `/app` keeps the existing mock-first trading workspace, and `/install` explains how to load the local developer-preview ZIP in Chrome.
- The landing page presents Zerodha, Angel One, Upstox and Dhan as planned connectors with links to official API documentation. None are currently connected.
- The extension has a Broker setup view describing account/API, authentication, rights, order lifecycle and recovery checks. It does not collect broker credentials or issue live requests.
- `pnpm build` refreshes the extension bundle, packages it into `apps/website/public/downloads/algotrade-hub-extension-preview.zip`, then builds the website so its download matches that bundle. `pnpm package:extension-preview` can also package an already-built extension. The ZIP is a local Developer-mode preview that requires the local API, not a Chrome Web Store or public customer release.
- Paper features use the current mock backend and/or user-provided local CSV. Sample chart candles are synthetic. Live market data, broker fills/positions, subscriptions and live orders are unavailable; no subscription payment is collected.
- Default `pnpm build` and `pnpm typecheck` cover the dashboard, website and extension. Desktop checks are separate (`build:desktop`, `typecheck:all`) while desktop development is paused.

## Phase 5 progress

- Backtests now report expectancy, profit factor, exposure, daily Sharpe with its assumptions, and separate anchored walk-forward windows.
- A local-only executed-trade CSV report uses FIFO to calculate reference realized P&L by segment, including partial fills, shorts, reversals, and remaining inventory.
- Tradebook exports include a formula-safe summary/match/open-inventory CSV and a print-only report that can be saved as PDF from the browser. These are not tax calculations.
- Scanner opens in a fail-closed unavailable state because no licensed feed or backend scan route exists. Mock data is not scanned.
- Order review has keyboard focus trapping, Escape handling, and focus restoration. Responsive compact navigation exposes every tab and keeps sign-out/alerts available.
- No smart order or real scanner output is enabled without official broker capability and licensed fresh market data. Tax calculations and 360 px visual/E2E runtime verification remain gated. See [Phase 5 scope and status](docs/phase-5-trader-depth.md) and the [Phase 5 verification report](docs/test-report-phase-5.md).
- Phase 6's Tauri trust boundary, local/cloud options, credential lifecycle, static-IP dependency, crash recovery, and signed-update plan are documented in the [desktop plan](docs/phase-6-desktop-plan.md). The desktop scaffold remains in the repository, but desktop development is paused. Its historical test report is not evidence of broker readiness. See [desktop run instructions](apps/desktop/README.md) and the [Phase 6 verification report](docs/test-report-phase-6.md).

Phase 2 validation details are in [the Phase 2 report](docs/test-report-phase-2.md); Phase 3 design and verification are in [the analysis design](docs/phase-3-design.md) and [the Phase 3 report](docs/test-report-phase-3.md).

## Safety boundary

`TRADING_MODE` is fixed to `MOCK` in the local compose configuration. The order table enforces `mode = 'MOCK'`; the order service is wired to `MockBrokerAdapter`; `ZerodhaAdapterBoundary` has no HTTP client or credentials and throws on order calls. Demo symbols and quotes are synthetic. Do not connect a brokerage account or use the demo quotes for decisions.

## Next phases

Phase 5 local/mock-safe coding is complete; runtime QA still needs Docker-backed order flow. Landing and extension onboarding are the current active product work; desktop is paused. Licensed scanner data, broker evidence for smart orders, current exchange calendars/instrument masters, provider rights and verified charges/taxes remain external gates. The CSV backtest is a constrained educational example, not a broker execution simulator. Any live manual or automated broker workflow remains separately gated for a later phase. A licensed market-data provider remains an unresolved external dependency; synthetic samples are not a replacement for it.
