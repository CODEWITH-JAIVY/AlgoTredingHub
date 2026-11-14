# Deployment options

AlgoTrade Hub is designed for a local-first mock workflow today, with a cloud path documented for a later private pilot. Neither option connects a live broker or licensed market-data provider.

## Local installation — recommended for Phase 4

Run the database, Redis, Spring API, and private Python analysis service with Docker Compose. Run the Next.js PWA locally and load the Chrome side panel as an unpacked extension. PostgreSQL and Redis bind to loopback; the analysis service is only reachable inside the Compose network. Browser requests go to the local API, which remains hard-wired to the mock broker. For a temporary UI preview when Docker is unavailable, use the ephemeral, loopback-only [local demo profile](local-demo.md); it does not verify PostgreSQL/Redis behavior and must not be used for live trading.

```text
Chrome PWA / Side Panel
          │ localhost HTTP
          ▼
       Spring API ───── PostgreSQL (loopback)
          │                  Redis (loopback)
          └── private ── Analysis service
```

Setup: run `scripts/setup-local.ps1` once to create ignored `.env` secrets, then follow [Phase 4 local run instructions](phase-4-mvp.md). After loading the unpacked extension, replace the extension-origin placeholder in `.env` with its exact generated ID. Keep `APP_SINGLE_USER_MODE=true`.

This is the easiest way to develop and review the current product. Local Docker, PostgreSQL migrations, and Redis-backed authentication must be running for signup and login. The test-profile H2 database is disposable and does not replace this stack.

## Cloud private pilot — design only

When remote access is needed, use private managed infrastructure and keep the data plane isolated:

1. Serve the PWA over HTTPS from a static/SSR web host under a dedicated application domain.
2. Put the Spring API behind HTTPS ingress with strict rate limits and an exact CORS allowlist. Use a backend-for-frontend (BFF) or equivalent secure session design before exposing accounts remotely; the current clients keep bearer tokens in `sessionStorage` and are not the final hosted-session design.
3. Put PostgreSQL and Redis on private subnets with no public listener. Enable encryption at rest, automated backups, tested restore, rotation through a managed secret store, least-privilege service identities, and health/alerting.
4. Keep the Python analysis service private to the application network. Do not expose its internal token or port to browsers.
5. Keep `APP_SINGLE_USER_MODE=true` for a personal pilot. The database schema is account-scoped, but tenant isolation and operational controls still need a security review before opening registration to more users.
6. Treat the Chrome extension as a separate release: add only the intended HTTPS API host permission, use the exact origin policy, publish privacy disclosures, and do not store broker credentials in the extension.

```text
Browser PWA / approved extension
             │ HTTPS, authenticated session
             ▼
     Web edge / BFF ── Spring API
                           ├── private managed PostgreSQL
                           ├── private managed Redis
                           └── private analysis service
```

The cloud option is not ready to deploy until the hosted session design, provider data rights, backup/restore, domain and TLS, monitoring, incident response, account recovery, privacy/compliance review, and the extension's remote API permissions are implemented and reviewed. No cloud account, DNS, infrastructure, or public endpoint is configured by this project.

## Choose by use case

| Need | Local | Cloud private pilot |
|---|---|---|
| Development and mock testing | Ready after Docker is running | Unnecessary |
| Use without internet | Possible after services and assets are installed | No |
| Remote access from multiple devices | No | Intended after the listed hardening work |
| Data ownership | Local database and browser storage | Managed database with backups and retention policy |
| Broker execution / licensed feed | Not available | Not available; requires separate approved work |

Use local deployment for the current phase. Revisit cloud hosting after the product's session, privacy, and operations work is complete.
