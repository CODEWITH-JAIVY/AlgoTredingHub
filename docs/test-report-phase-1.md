# Phase 1 test report

## Tests added

- `RiskServiceTest`: risk bounds, specific order-field validation, IST daily-state rollover, future-dated state rejection, kill-switch rejection, maximum accepted mock order entries, opening cooldown, order-entry cutoff, and proof that queued relaxations are inactive before their effective date and applied before later order checks.
- Historical Phase 1 test report: `RiskPolicyTest`, `RiskPolicyControllerTest`, `RiskPolicyMigrationTest`, and `WeekdayDemoSessionCalendarTest` covered immediate tightening, queued relaxation, mixed updates, cancellation, effective-date behavior, pending-state API mapping, weekend skipping, existing-row defaults through V4/V5, and database constraints. The calendar tests were subsequently replaced by 2026 exchange-calendar coverage; see [market-calendar.md](market-calendar.md) and [Phase 4 report](test-report-phase-4.md).
- `OrderServiceTest`: same-key retry idempotency without another daily-entry increment, changed-payload key reuse rejection, accepted-entry counting, broker-rejection non-counting, mock-only order persistence and risk-lock call.
- `AuthServiceTest`, `AuthRateLimiterTest`, and `AuthLifecycleIntegrationTest`: signup password byte limits and normalization, generic login rejection audit, refresh rotation/replay/expiry, family revocation, unavailable-account cleanup, Redis null counter/TTL/expiry responses, missing-TTL recovery, outages, attempt limits, hashed identifiers, persisted successor revocation, concurrent first-signup serialization, concurrent open-order and daily-entry limit enforcement, unauthenticated private-route rejection, exact CORS allowlisting including bearer-header preflight, cross-account order-list/cancel isolation, accepted-entry retention after cancellation, and public actuator info access.
- `BrokerAdapterSafetyTest`: mock acknowledgement/cancel behavior, disabled Zerodha order calls, and exclusion of the mock adapter under the `live` profile.
- `KillSwitchControllerTest`: repeated activation, cancellation failure, active-state save call, unresolved audit details, and unresolved counts after deactivation.
- Tests use local mocks, synthetic instruments, and an in-memory H2 lifecycle context; none can call a broker endpoint.

## Execution status

- **Full backend suite:** Passed — 90 tests, 0 failures, 0 errors, 0 skipped (Java 21, Maven Wrapper). This run also verified the Spring application context after explicitly selecting the production RiskService constructor.
- **Phase 1 Checkstyle:** Passed — 0 violations across the `audit`, `auth`, `broker`, `common`, `config`, `orders`, and `risk` source packages and tests.
- **Full backend Checkstyle:** Passed — 0 violations across all backend source packages and tests.
- **Package:** Passed — the final `package checkstyle:check` run created the Spring Boot executable JAR at `services/backend/target/backend-0.1.0-SNAPSHOT.jar`.
- **Auth lifecycle HTTP/H2 integration:** Passed; verifies refresh-family replay revocation, exactly-one initial signup under concurrency, open-order and daily-entry risk-cap enforcement under concurrency, private-route authentication, bearer-header CORS preflight, public actuator info, cross-account order-list/cancel isolation, accepted-entry retention after cancellation, and risk-policy active/pending values plus invalid-body rejection with the required daily discipline fields.
- **V4/V5 migration smoke tests:** Passed against H2 in PostgreSQL compatibility mode; existing policy and daily-state rows receive safe defaults, valid schedules pass, and invalid pending values, cutoff/cooldown combinations, and daily entry counts reject. This does not replace the pending PostgreSQL migration run.
- **Compose configuration:** Passed — `docker compose ... config --quiet` parsed both `.env.example` and the existing private `.env` without printing environment values.
- **PostgreSQL/Redis integration:** Not run. Both Docker contexts return `permission denied` for their named pipes; launching Docker Desktop did not restore API access, `wsl.exe --status` returns `WSL/E_ACCESSDENIED`, and no local PostgreSQL or Redis listener is available. PostgreSQL migrations and real Redis-backed flows still need a local stack run.
- **Broker safety:** All test orders use a mocked adapter and synthetic instruments. No live broker endpoint or exchange data is configured.

## Follow-up verification — 2026-10-21

- Full backend Maven package and Checkstyle passed on Java 21: **102 tests passed, 0 failures, 0 errors, 0 skipped; 0 Checkstyle violations**. The executable Spring Boot JAR was produced.
- Command: `.\mvnw.cmd -o -B '-Dmaven.repo.local=C:\Users\skkar\OneDrive\Desktop\AlgoTredHub\.work\maven-repo' package checkstyle:check` (run from `services/backend`, with `JAVA_HOME` set to Java 21).
- PostgreSQL and Redis container-backed verification remains pending because Docker's named pipe is not accessible in this environment; the successful suite uses H2 and deterministic mocks.

## Phase 1 exit criteria

| Criterion | Evidence | Status |
|---|---|---|
| Java 21 backend compiles, tests pass, and executable package is produced | Full suite: 90 passed, 0 failed/errors/skipped; package succeeded | Passed |
| Auth lifecycle handles password bounds, generic rejection, rotation, replay, expiry, and unavailable accounts | Auth service tests plus H2 lifecycle test | Passed locally |
| Auth throttling never proceeds without a confirmed 15-minute Redis window | Null counter, missing/repairable TTL, TTL lookup failure, Redis error, attempt ceiling, and hashed identifier tests | Passed with mocks; real Redis pending |
| Order and risk paths remain account-scoped and mock-only, with idempotency, field validation, and protective controls | H2 API ownership test verifies cross-account list/cancel isolation; order/risk tests, mock/Zerodha adapter safety tests, mock-only adapter wiring, `MOCK` database constraint | Passed on H2; PostgreSQL order lifecycle remains pending |
| PostgreSQL migrations, registration race, persisted risk/kill-switch state, and restart behavior work on the supported local stack | H2 tests pass, including V4/V5 migration smoke tests; Docker engine named pipe still returns permission denied | Pending PostgreSQL/Redis runtime |
| Health/readiness and fail-closed behavior are verified with live local dependencies | Compose-backed API and dependency failure checks not run | Pending environment runtime |
| Setup, architecture, API, threat model, and edge-case documentation are present | Linked from the README | Passed |

The backend implementation and JVM checks pass. Phase 1 is not fully signed off until the PostgreSQL/Redis Compose criteria above are exercised.

## Commands executed

From `services/backend` in PowerShell, with `JAVA_HOME` set to a Java 21 installation:

Final command for the 90-test package and Checkstyle verification:

```powershell
$env:JAVA_HOME = 'C:\Users\skkar\.jdks\ms-21.0.11'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:JAVA_TOOL_OPTIONS = '-Duser.home=C:\Users\skkar\OneDrive\Desktop\AlgoTredHub\.work\maven-user-home'
$env:MAVEN_OPTS = '-Dmaven.repo.local=C:\Users\skkar\OneDrive\Desktop\AlgoTredHub\.work\maven-repo'
.\mvnw.cmd -B -ntp package checkstyle:check
```

Earlier Phase 1 verification commands:

```powershell
$repoRoot = (Resolve-Path ..\..).Path
$env:MAVEN_USER_HOME = Join-Path $repoRoot '.work\maven-home'
$mavenRepo = Join-Path $repoRoot '.work\maven-repo'
.\mvnw.cmd -o -B "-Dmaven.repo.local=$mavenRepo" test
.\mvnw.cmd -o -B "-Dmaven.repo.local=$mavenRepo" package
.\mvnw.cmd -o -B "-Dmaven.repo.local=$mavenRepo" '-Dtest=RiskPolicyMigrationTest' test
.\mvnw.cmd -o -B "-Dmaven.repo.local=$mavenRepo" `
  '-Dcheckstyle.includeTestSourceDirectory=true' `
  '-Dcheckstyle.includes=**/audit/**,**/auth/**,**/broker/**,**/common/**,**/config/**,**/orders/**,**/risk/**' `
  checkstyle:check
.\mvnw.cmd -o -B "-Dmaven.repo.local=$mavenRepo" `
  '-Dcheckstyle.includeTestSourceDirectory=true' `
  checkstyle:check
```

Compose configuration only (does not start containers):

```powershell
docker compose --env-file .env.example -f infra/docker-compose.yml config --quiet
docker compose --env-file .env -f infra/docker-compose.yml config --quiet
```

The repository CI workflow uses Java 21 to execute `./mvnw -B test` on push and pull request. Run locally after Docker Desktop is available:

```powershell
cd services/backend
.\mvnw.cmd test
```

## Follow-up verification — 2026-10-21

- Full Java 21 backend suite after adding the HTTP mock-order/kill-switch lifecycle test: **107 tests passed, 0 failures, 0 errors, 0 skipped; 0 Checkstyle violations**.
- The new H2-backed HTTP test covers signup, initial inactive switch, accepted MOCK order, switch activation and confirmed cancellation, blocked follow-up submission with KILL_SWITCH_ACTIVE, deactivation, and retention of the cancelled order plus the daily accepted-entry count.
- AuthLifecycleIntegrationTest now uses a fixed supported IST session date (2026-10-21 at 10:00) so the order lifecycle test remains deterministic and does not depend on when the suite is run.
- PostgreSQL/Redis process-restart verification remains pending because the Docker engine is not accessible. No broker or live order endpoint is enabled.
## Kill-switch cancellation-status follow-up — 2026-10-21

- Kill-switch cancellation now counts an order as confirmed only when the broker adapter explicitly returns CANCELLED. A returned OPEN/PARTIALLY_FILLED/REJECTED/UNKNOWN status remains unresolved and leaves the persisted order state untouched.
- The new UNKNOWN-response regression test passes; latest full Java 21 backend suite: **108 tests passed, 0 failures, 0 errors, 0 skipped; 0 Checkstyle violations**.
- This protects the mock adapter boundary. Real broker cancellation and reconciliation semantics remain gated and unimplemented.