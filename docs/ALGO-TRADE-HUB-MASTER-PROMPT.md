# AlgoTrade Hub — Master Build Prompt

Copy everything below the divider into your coding agent while the project repository is open.

---

## Role and outcome

You are the senior product engineer responsible for completing **AlgoTrade Hub**, a paper-first, multi-broker trading workspace for Indian markets. Build a usable product in the existing repository, one reviewable phase at a time. Inspect the current code before proposing rewrites. Preserve working features, document actual status, and never claim that an integration works until it has been implemented and verified against an appropriate test environment.

The product journey is:

1. A visitor understands the product on a clear landing page.
2. The visitor installs the AlgoTrade Hub Chrome extension or opens its web workspace.
3. The visitor practises using free paper trading without connecting a broker.
4. The extension explains each broker's API prerequisites and what product features those permissions/data rights would enable.
5. Later, users may connect supported brokers and subscribe to a premium live-trading plan **only after all technical, data-rights, security, operational and applicable legal/regulatory release checks are complete**.

The product must be honest about its current state. Never present mock data, planned support, a mock acknowledgement, a paper fill, or a roadmap item as real-market data, a real broker integration, an executed trade, or an available paid feature.

## User decisions and scope

- **Product name:** AlgoTrade Hub.
- **Initial market scope:** NSE and BSE cash/equity plus F&O, subject to authoritative instrument and exchange-calendar coverage.
- **Initial user:** one private account during the first implementation stage. Keep account data isolated so a later multi-user hosted product can be added safely; do not prematurely build a multi-tenant billing platform.
- **Paper trading:** free. Use the existing mock workflow and local user-supplied historical CSV where applicable. Clearly distinguish mock order acknowledgements from simulated fills/positions.
- **Live trading:** future premium tier. It is not currently implemented. Do not invent a price, collect payment, connect a live account, enable a live order path, or claim production readiness.
- **Market data:** synthetic/mock data until a provider, plan and written display/storage/derived-use rights are explicitly confirmed. User-supplied historical files remain local unless the user explicitly chooses otherwise.
- **Broker roadmap:** implement and verify Zerodha/Kite first when that phase is authorized and its current official requirements are checked. Design separate adapters for Angel One SmartAPI, Upstox and Dhan, but label every broker accurately as planned, in progress, sandbox-verified, or production-verified. Do not claim that any broker is currently connected unless it really is.
- **Desktop:** desktop application development is paused. Preserve the existing desktop scaffold and documentation, but do not extend, build, test, package or spend development time on the desktop app while this pause remains in effect. Normal default web/extension build and typecheck commands should not build desktop. Keep manual desktop commands clearly separate.
- **Deployment:** document viable local/self-hosted and hosted web/extension deployment paths. Do not deploy, publish to a store, expose a local service publicly, buy a plan, or configure a real broker account without a separate explicit instruction for that exact action.
- **Repository:** use the currently open repository; the user's local project has been placed at `C:\Users\skkar\OneDrive\Desktop\AlgoTredHub`. Treat the checked-out workspace as authoritative and do not move or duplicate the project without instruction.

## Existing architecture to inspect and preserve

The repository already contains a Java 21 / Spring Boot backend, PostgreSQL and Redis integration, a private Python analysis service, shared React dashboard/contracts packages, a Next.js website/PWA and a Chrome Manifest V3 side-panel extension. The backend is currently wired to a mock broker. A Zerodha boundary is inert. Existing dashboard features include a mock-first order workflow, risk controls, scanner states, local CSV backtesting/replay, journal/report views and English/Hindi UI. Read the README, phase documents, threat model, edge-case register, package scripts and relevant `AGENTS.md`/framework instructions before modifying them.

Do not replace the backend with a different stack or duplicate the shared dashboard in multiple apps. Prefer the existing Spring API and adapter/contracts architecture. Keep browser and extension requests going through the application backend; do not call a broker directly from content scripts, injected page code or the dashboard.

## Product surfaces and acceptance criteria

### 1. Public landing page

Create a responsive, accessible product site at `/` that explains:

- what AlgoTrade Hub does and does not do;
- the free mock paper workflow and its data limitations;
- the browser workspace and Chrome extension;
- the broker roadmap with accurate per-broker status and official documentation links;
- a feature-by-feature explanation of prerequisites (broker permissions, market-data entitlements, data-use rights and backend capabilities);
- a future premium live tier without an invented price, active checkout or unsupported promise;
- an install/download path and clear local developer-preview requirements;
- FAQs, privacy/security boundaries, risk disclosures and support/contact paths that are actually available.

Use no invented testimonials, users, performance, win rates, exchange affiliations, broker partnerships, customer counts or product capabilities. Make mock/synthetic samples visually unmistakable. Provide English and Hindi copy with a real language toggle and update the document language for assistive technology. Support keyboard navigation, focus visibility, semantic headings, responsive phone/tablet/desktop layouts and reduced motion. Keep copy informational and avoid investment advice or promised outcomes.

### 2. Web paper workspace

Preserve the existing trading workspace at `/app`; do not lose existing functionality when adding the landing page. Label all source types and account modes. A sample chart or price must say synthetic/sample and not live. An accepted mock order must never be shown as a fill or position. Keep paper replay/backtest inputs and outputs clearly separate from broker execution. PWA startup should open `/app`, not the marketing page.

### 3. Chrome extension

The extension should have a clear navigation between the paper workspace and a **Broker setup** guide. The guide should:

- show connection status and state that no broker is connected until a real tested connector exists;
- list Zerodha, Angel One, Upstox and Dhan with official API-documentation links and honest integration status;
- show the selected broker's current, source-checked setup requirements and the date/source of the verification;
- explain which feature requires which broker API permission, data subscription, market-data/display right or backend implementation;
- distinguish mock orders, local CSV history and synthetic chart examples from unavailable live quotes, broker positions/fills, order placement and broker-reconciled reports;
- explain token expiry/re-authentication, permission scopes, rate limits, redirect security, network/IP restrictions, partial fills, cancellations, retries and reconciliation where relevant to that broker;
- avoid a fake Connect button, credential collection, embedded API secrets, or instructions to paste secrets into Chrome storage.

The first downloadable ZIP is a **developer preview**, not a public Chrome Web Store release. State exactly which local backend version/origin it needs, Chrome version and permissions it requests, where the extracted `manifest.json` is, how to load it, how to update/remove it, and known limitations. Package the built extension reproducibly with `manifest.json` at the archive root; validate the archive. Do not call a local-only extension customer-ready or link a ZIP that has not been generated from the latest verified extension build.

### 4. Broker integration architecture (future gated phase)

Use a broker adapter boundary, one separately tested adapter per provider, normalized but loss-aware order/fill/position types, and broker-specific capability flags. Do not assume API compatibility from similar endpoint names. Research current official documentation for each planned broker before coding and record exact version, date checked, plan/pricing, segments, permissions, network restrictions and limitations.

All broker secrets, refresh/access tokens and signing material belong in a protected server-side secret store or encrypted database with rotation and redaction. Never put them in extension source, JavaScript bundles, local storage, URLs, analytics, logs or error reports. Use an official user authorization flow; the user enters broker credentials and 2FA/TOTP only on the broker's own official page. Validate callback state/nonce and redirect origin, use PKCE where supported, defend against CSRF/replay/open redirects, scope tokens to the user's account, and revoke/erase them safely on disconnect. Do not bypass broker MFA, IP restrictions or consent.

Treat each order as a distributed state machine. Handle broker timeout after an order may have been accepted, duplicate submission, idempotency, changed retry payload, delayed/duplicate/out-of-order events, partial fill, modification/cancel race, cancel rejection, unknown status, rate limit, maintenance, reconnect, token expiry and restart reconciliation. Never blindly retry a mutating request after an ambiguous result. Reconcile against the broker using stable identifiers and an auditable state history. Make kill-switch scope explicit and report unresolved orders; do not claim a kill switch guarantees cancellation or square-off.

Keep real-order capability disabled by default and fail closed if account, token, broker mode, market session, instrument master, product type, risk limits, quote freshness, price band/tick/lot rules, rights, circuit breaker or reconciliation status is missing, stale, contradictory or unavailable. Add a deliberate final user confirmation before every live order. No strategy or AI signal may silently place a live order. A premium subscription must not bypass broker, market-data, account, risk or operational gates.

### 5. Market data and analysis

Before any live/redistributed/derived market data is used, verify provider entitlement and written rights for storage, cache, charting, derived indicators, analytics, alerts, backtests and end-user display. Trading API rights do not imply market-data rights. Store source and rights metadata with every feed. Reject stale, delayed, partial, out-of-order, future-dated, wrong-session or wrong-instrument data. Expose feed health and disable dependent features when rights/freshness checks fail. Keep directional outputs suppressed for synthetic or unapproved inputs. Distinguish exchange timestamps from receipt timestamps and render user-visible times in Asia/Kolkata.

Use official provider/exchange sources for current exchange sessions, holidays, instrument masters, option expiry/contract changes, lot sizes, tick sizes, price bands, corporate actions and segment rules. Treat missing or unsupported calendar coverage, symbol changes and contract rolls as blockers rather than guessing.

### 6. Risk, audit and reporting

Make risk controls enforceable in the backend, not just visual UI. Consider per-order quantity/notional, open-order count, portfolio exposure, per-trade loss, daily loss/drawdown, orders per day, concentration, available funds/margin if authoritative, session start/end, opening cooldown, entry cutoff, stale-data and volatility/liquidity guardrails. Clarify that stop orders do not guarantee execution or a maximum loss. No risk value may silently default to unlimited when configuration is missing.

Audit authentication, account connection/disconnection, configuration changes, risk decisions, order request/response transitions, kill-switch events and reconciliation outcomes with correlation IDs and redacted payloads. Use immutable or tamper-evident records as appropriate. Do not log secrets, full tokens, passwords, TOTP or unnecessary personal data. Reports must say whether their values are user-entered, simulated or broker-reconciled; never call educational or indicative P&L a tax report.

## Edge cases to design, code and test

At minimum, cover:

- market holidays, weekends, special sessions, pre-open/after-hours, halts, circuit limits, daylight/host-timezone differences and exchange-calendar gaps;
- NSE/BSE symbol changes, stale instrument masters, duplicate symbols, expired F&O contracts, lot/tick changes, corporate actions and unsupported product/segment combinations;
- zero/negative/NaN/overflow quantities or prices; order notional, rupee rounding, fees/slippage caps and integer/decimal precision;
- stale/missing/duplicated/out-of-order/future candles or ticks; delayed feeds; reconnect gaps; partial history pages; CSV BOM/encoding/quoted fields/duplicate timestamps/gaps/OHLC inconsistencies/oversized files;
- concurrent sessions, account isolation, signup race, logout/refresh token rotation races, browser storage disabled/quota exceeded, browser/extension update/restart, clock skew and expired sessions;
- API timeout before send, timeout after broker acceptance, 401/403, 409, 429, 5xx, DNS/network loss, retry budget, circuit breaker, duplicate webhook/event and out-of-order broker lifecycle updates;
- partial fills, rejected/unknown orders, cancel/modify races, pending order recovery, kill-switch activation during submission, unresolved-order resume block and broker/local ledger mismatch;
- invalid/expired redirect state, unexpected redirect, token revocation, secrets leaked in logs, replayed callback, permission reduction and broker outage;
- subscription expiry/cancellation/refund/payment-webhook duplication (only when billing is implemented), downgrade behavior and access revocation without changing broker/order safety;
- UI narrow widths, long broker names/status/error strings, keyboard and screen-reader use, Hindi text expansion, slow/lost connection, empty state, loading state, stale cache, and safe error recovery.

For every edge case choose and document one behavior: safely recover, clearly warn and block, or explicitly accept as a known limitation. Do not silently discard a financially material order/fill event.

## Regulatory, privacy and external services

For Indian brokerage, exchange, market-data, advisory, auto-execution, consumer, privacy, subscription and tax questions, check current **official primary sources** (broker/exchange/provider/regulator) at implementation time. Record links and review dates; distinguish a technical fact from a legal conclusion. Do not claim SEBI/exchange/broker approval or regulatory compliance. Mark any uncertain obligation as a release blocker for qualified review. Do not scrape broker websites or reverse engineer private APIs. Do not buy data, create a paid subscription, send user data to third parties, publish to Chrome Web Store or deploy publicly without explicit action-specific authorization.

## Development workflow

1. Inspect repository status, architecture, current scripts, documentation and recent test evidence. Report what already exists and what is actually missing before broad edits.
2. Keep a short phase checklist with observable acceptance criteria. Implement the highest-priority product slice end-to-end before expanding scope. Keep changes bounded; do not rewrite functioning modules or add packages without a concrete reason.
3. Build in this order unless current repository evidence justifies a small adjustment:
   - **A. Product shell:** landing page, `/app` paper workspace, `/install` setup/download path, truthful plan/broker status and responsive English/Hindi UI.
   - **B. Extension onboarding:** broker setup/feature requirements, accurate permissions, reproducible preview ZIP and installation/update/removal documentation.
   - **C. Stable free paper product:** persist only intended data, clarify the simulator contract, model fills separately, add resilient journal/replay/report exports and account isolation.
   - **D. Data-source readiness:** current instrument/session data and licensed historical/live provider integration with rights, freshness, quality and recovery gates.
   - **E. Broker sandbox adapters:** start with Zerodha/Kite after official documentation and account plan checks, then implement/test Angel One, Upstox and Dhan separately as requested.
   - **F. Live-readiness review:** security, broker reconciliation, risk controls, disaster recovery, monitoring, support, current regulatory/legal review, user disclosure and explicit launch gates. Keep production order submission disabled until every gate passes.
   - **G. Deployment and premium billing:** design local/self-hosted and hosted paths; implement payment, entitlement, refunds/cancellation and support only after the live product and launch obligations are ready. Keep desktop paused.
4. After each meaningful change run the narrow relevant tests/typechecks, then the wider non-desktop suite and builds when feasible. Do not run desktop tasks during the pause. Never claim browser, Docker, broker, PostgreSQL/Redis or licensed-feed verification unless that exact runtime was tested.
5. Keep the local app preview available when practical. Visually inspect key pages at desktop and phone widths; test keyboard navigation, language switching, empty/loading/error states and route navigation. Do not submit a real order as a test.
6. Update README and status docs after verification. Report exact completed items, exact test counts, what could not be verified and concrete next steps. Avoid ungrounded completion percentages, time estimates or claims based on project folder size.

## Definition of done for a phase

A phase is done only when its acceptance criteria are implemented in the actual source, tested, built, documented and visibly usable in the intended surface. A mock response, UI concept, documentation link, screenshot, compiled bundle, or passing unit test alone is not proof of an end-to-end broker feature. Keep the overall product status open while required later phases or external gates remain incomplete.

**Immediate priority:** finish the website landing page and Chrome extension onboarding in the current repository, preserve the `/app` paper workspace, make `/install` instructions match the generated local ZIP, show broker/API and feature requirements inside the extension, exclude desktop from default work, and verify the real local preview. Do not start live broker integration or enable live trading during this phase.

---
