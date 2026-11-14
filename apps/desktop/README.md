# AlgoTrade Hub Desktop

Windows-first Tauri 2 shell for the shared AlgoTrade Hub dashboard.

## Safety and connection boundary

- The frontend uses the fixed local API `http://127.0.0.1:8080`; it does not accept arbitrary server URLs.
- The API must be the existing local mock backend. No broker credentials, broker URLs, live-order client, background runner, autostart, updater, filesystem access, or shell access are included.
- The app does not queue or replay actions when the API is unavailable. The dashboard reports the request failure; reconcile the current mock state before manually trying again.
- `sessionStorage` holds only the app's short-lived authenticated session for this window. A separate account-scoped `localStorage` record preserves an uncertain mock order until its exact request is reconciled. It contains no credentials; broker credentials never belong in the webview.

## Run in development

1. Start the local services following [Phase 1 setup](../../docs/phase-1-setup.md). The documented command passes `--env-file .env` explicitly.
2. Install workspace packages from the repository root with `pnpm install`.
3. Run `pnpm dev:desktop` from the repository root.

The API CORS list must include `http://tauri.localhost` for the Windows production webview and `http://127.0.0.1:1420` for development. The provided Docker Compose file appends both origins to any local custom CORS list.

## Windows build and current status

The Windows x64 Tauri release executable has been built and launched in this checkout. It uses the generated Tauri icons and a fixed local mock API origin. The current executable was rebuilt on 2026-10-22 after dashboard stale-response and API refresh/session protection; the earlier executable launch smoke check predates that rebuild. The tested helper is `scripts/build-desktop-xwin.ps1`; it relies on this checkout's ignored `.work` cache (portable Rust MSVC, cargo-xwin, LLVM, and the downloaded Windows SDK/CRT). Keep that ignored local toolchain available to rerun the helper.

For a fresh machine, follow the [official Tauri Windows prerequisites](https://v2.tauri.app/start/prerequisites/) (Rust MSVC, Microsoft C++ Build Tools/Windows SDK, and WebView2), then run `pnpm build:desktop`. The portable helper is a local build route for this checkout, not the standard setup for other machines.

After `pnpm --filter @algotrade/desktop build` or `pnpm --filter @algotrade/desktop tauri build`, run `pnpm --filter @algotrade/desktop test:safety`. It checks the fixed localhost API, CSP allowlists, Tauri capabilities/plugins, and generated frontend bundle for broker endpoints or credential constants.

The `.exe` is unsigned, no installer or public release is produced, and the CI workflow has not run. Visual/keyboard QA and Docker-backed mock API flows remain unverified; see the [Phase 6 verification report](../../docs/test-report-phase-6.md).
