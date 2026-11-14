# AlgoTrade Hub threat model

Date: 2026-10-21

## Scope and assumptions

This model covers the current mock-first product and its local development deployment. The initial deployment is for one account. PostgreSQL and Redis bind to loopback in the local Compose setup. Market inputs are synthetic or explicitly unavailable. No broker credentials, live market-data credentials, live order route, or hosted service are configured.

The local workstation is inside the trust boundary. A compromised workstation, operating system, or browser profile can expose locally stored credentials and data; the application cannot defend against a fully compromised host. Cloud hosting, multiple independent tenants, production key management, and live trading need a separate review before they are enabled.

## Assets

- Account passwords, access JWTs, refresh-token material, and refresh-session state.
- Account ownership, risk limits, kill-switch state, mock orders, and audit events.
- Database, Redis, JWT signing, and future provider credentials in local configuration.
- Instrument metadata, data-source rights metadata, synthetic candles, and analysis outputs.
- The invariant that no development or test action can place a live broker order.

## Trust boundaries

1. Browser, extension, or desktop UI to the versioned backend API.
2. Backend API to PostgreSQL and Redis.
3. Backend order service to the broker adapter contract.
4. Future market-data provider to normalized ingestion and history import.
5. Local workstation to any future hosted infrastructure or broker network.

## Threats and current controls

| Threat | Current controls | Residual risk / evidence needed |
|---|---|---|
| Password guessing, account enumeration, or repeated signup | Generic invalid-login response; Redis-based 15-minute throttling; single-user registration gate; BCrypt; password byte bound; auth rejection audit omits secrets | Real Redis outage/throttle behavior and PostgreSQL concurrent first-signup behavior need Compose verification; hosting needs a trusted-proxy/IP policy |
| Stolen or replayed refresh token | Opaque random refresh tokens; only SHA-256 hashes persisted; atomic rotation; replay revokes active sessions in the family; logout revokes the supplied session | A stolen access JWT remains valid until its 10-minute expiry; device compromise and secure client storage remain risks |
| Cross-account object access or altered risk state | Authenticated API, account-scoped repository queries, server-side validation, risk checks, and database uniqueness/ownership constraints; H2 API coverage verifies cross-account order-list/cancel isolation | PostgreSQL-backed authorization and ownership runtime checks remain pending |
| Duplicate order request, changed retry payload, concurrent risk reservation | Stable client order ID, idempotent same-payload response, conflict on changed payload, locked risk policy, mandatory protective stop | Broker timeout ambiguity and reconciliation apply only when a future live adapter is separately reviewed; current adapter is synthetic |
| Accidental or malicious live order | Order service injects only `MockBrokerAdapter`; Zerodha boundary is inert and throws; order schema constrains mode to `MOCK`; API has no live-order route; live credentials are absent | Verify the complete deployed package and future adapter changes independently before any live capability is considered |
| Kill-switch failure or false claim of liquidation | Kill state persists in PostgreSQL, blocks new mock orders, and reports cancellation attempts/unresolved mock orders; response does not claim real positions were squared off | PostgreSQL restart and degraded/cancel-failure scenarios need Compose integration; there is no real-position reconciliation |
| Redis outage or broken throttle expiry | Auth endpoints fail closed with 503 if Redis errors, returns no counter, or cannot read the window TTL; a counter left without TTL by an interrupted first request is repaired or rejected | Redis service behavior and operational recovery need a local integration run; availability is intentionally sacrificed over unthrottled auth |
| Malicious, stale, synthetic, or unlicensed market data | No real provider is enabled; fixtures are marked synthetic/stale; ingestion validates timestamps, tick size, master version, gaps, and duplicates; analysis suppresses signals for synthetic or stale input | Exchange calendar, corporate-action treatment, written feed rights, provider failure, and data-source reconciliation remain future gates |
| Secret leakage through repository, logs, or test fixtures | `.env` is ignored; committed env file contains placeholders; JWT startup validation; auth audit stores stable rejection codes rather than credentials or tokens | Local host access can expose `.env`; rotation, managed secret storage, log review, and an independent security review are required before hosting |
| Denial of service or oversized inputs | Request validation, bounded history/import and analysis work, Redis auth limits, upload bounds, and fail-closed dependency handling | Distributed limits, load testing, and multi-instance coordination are not implemented |
| Dependency or build artifact compromise | Dependencies are pinned/locked where supported; CI workflows are defined | This checkout has no Git metadata or remote, so CI and release provenance are unverified; desktop binary is unsigned |

## Security decisions

- Keep the default operating mode mock-only and do not configure real broker credentials.
- Keep registration in single-user mode and bind local service ports to loopback.
- Fail closed when authentication throttling cannot be enforced.
- Treat a refresh-token replay as family compromise and revoke its active successors.
- Mark unavailable, stale, synthetic, or rights-unconfirmed data so it cannot produce an actionable signal.
- Do not claim the kill switch cancels real broker orders or squares off positions.
- Require separate broker, legal/compliance, security, operations, and explicit user gates before live order capability.

This is an implementation-focused threat model, not an independent penetration test or production security approval.
