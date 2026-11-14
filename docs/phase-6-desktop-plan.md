# Phase 6 — Desktop surface and trust boundary

Status: mock-only Tauri source scaffold, Windows x64 release executable, launch smoke check, English/Hindi authentication-screen browser smoke check, dictionary-backed English/Hindi copy across the dashboard, and five automated trust-boundary checks are complete. The shared React dashboard typechecks and builds. Authenticated visual review, native-window resizing/keyboard QA, Docker-backed workflow checks, restart recovery, signing, broker credentials, and a background runner remain out of scope or gated.

Checked: 2026-10-21. Broker/provider conditions below are based on the linked official pages and must be rechecked before any integration or deployment; this document is not legal advice.

## Source scaffold status

- `apps/desktop` reuses the shared dashboard with a fixed `http://127.0.0.1:8080` API origin. It does not read an arbitrary API URL from environment variables. New order tickets are refused while the required API reads report disconnected.
- The Tauri v2 app has one resizable window, no shell/filesystem/updater/autostart plugins, no custom Rust commands, and no enabled IPC capabilities. The production CSP allows only bundled assets and the local mock API; the development CSP adds only the loopback Vite HMR socket.
- Before sending a mock order, the dashboard persists the exact account-scoped request locally. An ambiguous submit is looked up by client order ID and exact order fields; new tickets stay blocked until reconciliation, and an explicit retry reuses the same ID. Corrupt or unavailable recovery storage fails closed.
- Backend CORS includes the Windows production origin `http://tauri.localhost` and the loopback Vite development origin. Compose appends those origins even when the local `.env` supplies a custom web/extension origin list.
- `pnpm --filter @algotrade/desktop typecheck` and `pnpm --filter @algotrade/desktop build` pass. `tauri info` confirms the config is recognized and WebView2 is installed.
- `pnpm --filter @algotrade/desktop test:safety` checks the fixed frontend API origin, exact production/development CSP connection allowlists, an empty native capability list and inactive installer bundling, absence of Rust plugins/custom IPC, and the built JavaScript bundle for broker endpoints/credential constants. Five checks pass locally. The Windows workflow runs this suite after the Tauri build; this does not replace native runtime/network observation or an independent security review.
- `.github/workflows/desktop-windows.yml` provides a Windows 2022 native build path with locked pnpm dependencies, frontend typecheck, mock-order/backtest tests, Tauri build, and a 7-day executable artifact. It has not run because this folder has no Git metadata or GitHub remote configured.
- `Cargo.lock` pins 407 resolved Rust packages. The existing square SVG brand icon was converted to Tauri's platform icon set, including the required Windows `.ico` resource.
- The native x64 MSVC release build succeeds through `scripts/build-desktop-xwin.ps1`, using an isolated, ignored `.work` toolchain (Rust MSVC, cargo-xwin, and portable LLVM). The executable was rebuilt on 2026-10-22 with the zone-touch alert UI; it is 8,472,064 bytes with SHA-256 `749DD6CBB217CD32B288763124682B0A4DD4CECFBC22FA40D0C493C455D98BDC`. An earlier build was checked as a 64-bit Windows GUI binary and launched with the window title `AlgoTrade Hub`; this rebuild was not launched for another smoke check. This portable cross-build route is specific to this checkout; it is not a replacement for the standard Tauri Windows prerequisites on a fresh development machine. The executable is unsigned and not an installer.

## Decisions carried forward

- Windows is the first desktop development target because the owner is developing on Windows. Other operating systems are not promised until their CI builds and release checks pass.
- The current product remains single-account, mock-first, and limited to NSE/BSE cash and F&O demo instruments. Zerodha has not been configured.
- Local Docker and a future cloud private-pilot shape are both documented. No public service is configured.
- Phase 6 must not create a live order route. Phase 7 remains separately gated for manual and automated live orders.

## Recommended desktop boundary

Use Tauri 2 as a Windows desktop shell that reuses `@algotrade/dashboard`. The webview is a presentation/control surface and talks to the existing Spring API. The current local mode points only to the localhost mock API. A future hosted mode must use an HTTPS BFF/session design; it must not reuse the current browser `sessionStorage` bearer-token design as a public hosted session.

The Tauri frontend receives no broker API secret, access token, refresh token, or direct broker endpoint. Do not add a desktop-side order loop, strategy runner, browser automation, or broker login automation in Phase 6. No Tauri filesystem, shell, updater, or autostart permissions are needed for the mock-only shell. If a later capability is added, grant only its narrow capability to the intended window. Tauri's permissions are denied unless explicitly granted in its capabilities model.

```text
Windows Tauri UI (shared React dashboard)
        │
        ├── local mode: localhost Spring API ── mock broker
        │                Docker Postgres/Redis/Python services
        │
        └── future hosted mode: HTTPS BFF ── private Spring API/data services

No broker secret/token in either webview path.
No order task starts when the window is hidden, closed, or the OS starts.
```

The first desktop deliverable is a mock-only desktop client. Local services continue to be provisioned through Docker Compose for now; Tauri should not silently start Docker or install background services. If the API is unreachable, show disconnected status and refuse submissions rather than queueing actions for later replay.

## Broker and network boundary

Zerodha's official Kite documentation describes a one-time request-token exchange that uses the API secret, and says the access token normally expires at 6 AM the next day. The secret and resulting access token must stay server-side in a future broker connector; a normal webview/desktop bundle is not a safe secret store. Zerodha also says it has no Kite Connect sandbox, so all adapter verification must use deterministic fakes and contract tests, never a real order as a test. See the [official login flow](https://kite.trade/docs/connect/v3/user/) and [Kite Connect FAQ](https://support.zerodha.com/category/trading-and-markets/general-kite/kite-api/articles/kite-connect-api-faqs).

Zerodha's current support page says API order placement requires a whitelisted static IP effective 1 April 2026. Its official FAQ still says 1 April 2025; the dates conflict, so support must confirm the applicable date and change procedure before integration. Both pages require a static egress IP for API orders. The FAQ says only immediate-family accounts may share an IP, and IP changes may be limited to once per calendar week. Do not assume a dynamic home connection is suitable. A cloud static-egress route is only an option after broker terms, hosting, data protection, and account-owner constraints are reviewed. See the [static-IP instructions](https://support.zerodha.com/category/trading-and-markets/general-kite/kite-api/articles/static-ip) and [official API FAQ](https://support.zerodha.com/category/trading-and-markets/general-kite/kite-api/articles/kite-connect-api-faqs).

The current [Kite Connect terms](https://kite.trade/terms/) say the API is not meant for fully automated trades without manual intervention and says necessary exchange approvals should be sought for full automation. They also restrict public display/redistribution of live Kite market data and its use for mock-trading apps. Therefore, keep the mock/sample feed fully separate from any future live provider, and do not route live Kite data into the current mock-workflow screens unless the data-use rights explicitly permit it. Obtain broker/exchange and qualified legal review before changing this boundary.

The consolidated provider facts, current plan pricing, rate limits, order/partial-fill behavior, duplicate-submit ambiguity, static-IP date discrepancy, and integration gates are tracked in [zerodha-kite-review.md](zerodha-kite-review.md). Recheck the linked official pages before any future provider integration because these terms and limits can change.

## Credential lifecycle for a later approved integration

1. Broker login is initiated only through the official login flow; the app never collects the broker password or automates the broker website.
2. The one-time request token is received by the registered backend redirect, exchanged server-side using the API secret, and discarded after exchange.
3. Keep credentials in a managed secret store/KMS-backed design for hosted operation. For a private local-only setup, select an OS credential vault only after documenting its backup/revocation behavior; do not invent custom encryption or store a secret in `.env`, a webview, extension storage, logs, URLs, or telemetry.
4. Since the access token expires daily, require the user to re-authenticate when required. Logout/revocation must clear the broker token from the secret store and invalidate the app's link state.
5. Credential rotation, account unlink, application deletion, and broker token revocation are explicit actions with audit events. No background process may refresh a broker session without a documented official permission to do so.

This lifecycle is design-only. No broker API secret or token is present in the project.

## Hosting and execution options

| Option | Desktop calls | Order-capable egress | Phase 6 recommendation |
|---|---|---|---|
| Local development | `http://127.0.0.1` mock API and local Docker services | No live order route | Use for mock-only development and UI review |
| Local personal live candidate | Local backend/API | ISP-provided static IP only if broker allows and the exact egress can be whitelisted | Not enabled; re-authentication, uptime, IP changes, and crash recovery need broker review |
| Cloud private-pilot candidate | HTTPS BFF and API; private data services | A dedicated static egress only if broker, account, legal and security reviews approve it | Design only; no infrastructure or credentials configured |

Do not make the UI choose an arbitrary API URL. Local builds allow only localhost. A hosted build has a fixed HTTPS API origin and a strict origin policy. If a backend/BFF is unavailable, authenticated requests stop and no order is replayed.

## Failure handling and recovery

- **Window close / OS restart:** Phase 6 has no active order process, queued order, or hidden scheduler. The UI reconnects on next launch and fetches the server's current mock state.
- **Backend disconnect:** disable submit, preserve a visible disconnected state, and do not replay a prior request automatically.
- **Submit timeout or uncertain HTTP response (408, 409, 429, or 5xx):** retain the exact pending request, reconcile by client order ID and full payload/mode, and only offer a manual same-ID retry while status is unresolved. Recovery accepts only the UUID v4 ID shape generated by the app; malformed local records remain blocked for manual order-history review.
- **Kill switch:** show “request sent”, “confirmed cancelled”, or “unresolved” separately. A UI response alone never claims that all exposure is closed. Keep the switch active until open, partially filled, and unknown orders resolve; allow a user-confirmed cancellation retry while unresolved orders remain.
- **Crash or update:** do not resume order activity. The next startup must query current state before re-enabling mock controls.
- **Updates:** do not configure auto-update in the mock prototype. Before distribution, generate and protect an offline/CI-held private signing key, pin the public key, verify signed installers, test rollback/recovery, and publish signed release notes. Tauri's updater requires signatures; its private key must never enter the repository or app.
- **Startup behavior:** no autostart by default. If requested later, make it an explicit opt-in for opening the UI only; never use OS startup consent to start trading.

## Phase 6 acceptance criteria

1. The Windows desktop window launches the shared UI and can be resized/scaled without losing keyboard navigation.
2. It calls only the localhost mock API; no broker URL, API key, API secret, access token, or live order client appears in the bundle or runtime logs.
3. Mock order confirmation and kill-switch status remain clear in the desktop surface; the desktop has no unattended execution path.
4. The app recovers from service disconnect and process restart without replaying a submit.
5. Default Tauri capabilities are minimal; no filesystem/shell/autostart/updater permission is enabled until its use and tests are in scope.
6. Windows dev build and automated checks pass. No signed or public release is claimed until signing and independent security/recovery checks are complete.

## Environment dependency

This workstation has WebView2 but no Visual Studio C++ Build Tools or Windows SDK. The local build was completed with `cargo-xwin` and its ignored `.work` SDK/CRT cache plus portable LLVM; the helper script works only while those local ignored tools remain present. For a fresh checkout, follow the [official Tauri Windows prerequisites](https://v2.tauri.app/start/prerequisites/) and run `pnpm build:desktop`. Do not switch silently to Electron: it is a material architecture change and increases the bundled runtime/attack surface. No release-signing key has been generated.

## Official references checked 2026-10-20

- [Kite Connect login flow, request-token exchange, and access-token expiry](https://kite.trade/docs/connect/v3/user/)
- [Kite Connect API rate limits and error codes](https://kite.trade/docs/connect/v3/exceptions/)
- [Kite Connect static-IP instructions](https://support.zerodha.com/category/trading-and-markets/general-kite/kite-api/articles/static-ip)
- [Kite Connect FAQ: no sandbox, plans, data use, static IP, market protection, and automation](https://support.zerodha.com/category/trading-and-markets/general-kite/kite-api/articles/kite-connect-api-faqs)
- [Kite Connect Terms](https://kite.trade/terms/)
- [Tauri updater signature requirements](https://v2.tauri.app/plugin/updater/)
- [Tauri capability permissions](https://v2.tauri.app/security/capabilities/)
- [Tauri autostart plugin](https://v2.tauri.app/plugin/autostart/)
- [Tauri Windows prerequisites: Rust, Microsoft C++ Build Tools, and WebView2](https://v2.tauri.app/start/prerequisites/)

