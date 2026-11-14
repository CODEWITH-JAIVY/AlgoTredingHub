# Docker-free local mock demo

This optional mode lets the Next.js site and Chrome side panel reach the mock-only Spring API when Docker Desktop is unavailable. It is for local UI practice and review only; it is not a replacement for the PostgreSQL/Redis Compose environment and cannot be used for live trading.

## Start

From the repository root, start the backend in one PowerShell terminal:

```powershell
.\scripts\run-local-demo.ps1
```

The script starts Spring Boot on `127.0.0.1:8080`, generates a fresh JWT signing secret in memory, fixes the order mode to `MOCK`, and runs offline from the repository's existing Maven cache. Keep that terminal open. If Maven reports a missing cached artifact, stop here and use the Compose setup after Docker is available; the script does not download dependencies.

In a second terminal, start the web UI if it is not already running:

```powershell
pnpm dev:web
```

Open `http://127.0.0.1:3000/`, create the first local account, and sign in. The backend accepts only loopback requests and the configured local web/desktop origins. Analysis remains unavailable unless its separate private service is running.

## Data and safety limits

- H2 is in memory. Accounts, sessions, policies, and mock orders are erased whenever the backend stops.
- Authentication throttling is process-local, bounded to 4,096 identities, and limited to ten attempts per identity/action per 15-minute window. The normal profile continues to require Redis and fails closed when Redis is unavailable.
- The local profile fails startup if it is combined with the `live` profile, binds away from `127.0.0.1`, leaves `MOCK` mode, or enables the live-order flag.
- The order service still has no live endpoint. The Zerodha adapter remains an inert boundary. Quotes and candles are labelled synthetic and are never current market data.
- This profile uses Hibernate `create-drop`, not the production Flyway migrations. Passing this local UI check does not verify PostgreSQL migration behavior or production readiness.

## Latest verification

Verified on 2026-10-21 and rechecked on 2026-10-22 (Asia/Kolkata): backend startup and /actuator/health returned UP; the Next.js page returned HTTP 200. A real browser session completed first-account signup, sign-in, dashboard loading (four synthetic instruments), and risk-control loading. The analysis panel correctly stayed unavailable because its separate private service was not running. An attempted mock order outside the weekday demo window was rejected by the backend and no order was created. The local profile's first-signup gate seed is covered by LocalDemoStartupIntegrationTest.

The browser also activated the kill switch on an empty mock account and displayed ACTIVE with zero confirmed cancellations and zero unresolved orders. Restarting the backend cleared the in-memory test account and switch state; the browser now shows the clean sign-in page. An authenticated H2 HTTP integration test now also creates a mock order, activates the kill switch, verifies cancellation and blocks a new order, then deactivates the switch without reopening the cancelled order. This does not verify PostgreSQL/Redis persistence or browser-side order-confirmation visuals. Licensed market data, a broker, and live trading remain unverified. H2 is in memory and the demo remains mock-only. See [current project status](project-status-hi.md).
