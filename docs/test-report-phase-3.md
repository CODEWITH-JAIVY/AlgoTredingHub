# Phase 3 verification report

Date: 2026-10-20 (Asia/Kolkata)

## Results

- Analysis service suite: **17 tests passed**. Coverage includes indicator warm-up/nulls, zero volume, bad OHLC, tick alignment, mixed master versions, finalized-only input, stale/simulated/incomplete/gapped/future/outlier data, conflicting timeframes, deterministic zones/scenarios, cache expiry/concurrent misses/out-of-order updates, internal auth, rate limits, correlation IDs, and request-size rejection.
- API smoke tests used an in-process ASGI transport and made no network, market-data, or broker calls.
- Docker Compose configuration parsed successfully with `.env.example`.
- Local performance sample: Python 3.12.14 on Windows, 230 bars for each of 5m/15m/1h/1d (920 total), 5 warm-ups and 100 measured iterations. Uncached calculation p50 **39.441 ms**, p95 **42.570 ms**, max **55.308 ms**. Process-local cache hit p50 **2.633 ms**, p95 **2.867 ms**. These are local measurements, not a production SLA or container benchmark.
- Pinned runtime packages: FastAPI 0.152.0, Uvicorn 0.53.0, pandas 3.0.1, NumPy 2.3.5, and Pydantic 2.14.0; test HTTP client is httpx 0.28.1.

## Commands

With the pinned `services/analysis/requirements-test.txt` installed:

```powershell
python -m unittest discover -s tests -v
python scripts/measure_latency.py
```

From the repository root, compose syntax check:

```powershell
docker compose --env-file .env.example -f infra/docker-compose.yml config --quiet
```

## Not verified

- Docker Desktop's Linux engine denied connection to `dockerDesktopLinuxEngine`, so the analysis image was not built or run in Compose.
- The authenticated Spring bridge to the analysis service is implemented in Phase 4. Its Docker-backed end-to-end runtime has not been verified because Docker's Linux engine is unavailable.
- Current Phase 2 candles are synthetic. The service intentionally suppresses signal/scenario output for them. No licensed source, corporate-action feed, event calendar, or official holiday/special-session calendar is connected.
- The cache and request limiter are per-process. Multi-instance Redis coordination is not enabled.

## Follow-up verification — 2026-10-21

- Re-ran `python -m unittest discover -s tests -v` with the repository's pinned dependencies: **17 tests passed, 0 failures** on Python 3.12.14.
- The Spring backend package/Checkstyle run also passed 102 tests; see the [Phase 1 report](test-report-phase-1.md).
- Compose-backed analysis service and authenticated browser integration remain unverified; demo inputs continue to suppress market bias and scenario output.

## Provider-analysis rights follow-up — 2026-10-22

- Added a default-off, exact-source analysis gate. Provider analysis requires separate storage, cache, end-user-display, and derived-analytics confirmations; provider-quality candles are mandatory. Synthetic data never becomes actionable through these flags.
- Spring now independently withholds directional output when the source is stale, any requested or higher-timeframe series is incomplete, gaps are present, or corporate-action status is unverified. Source gaps are forwarded to the Python engine, bounded to 1,000 intervals. Withheld responses use neutral safety copy rather than passing through a contradictory directional summary.
- Focused Spring analysis command and result: **9 passed**, Checkstyle **0 violations**.

```powershell
$env:JAVA_HOME = (Resolve-Path '.work\toolchain\jdk-21.0.12.1+1').Path
& '.work\maven-home\wrapper\dists\apache-maven-3.9.12-bin\78a7b4ce\apache-maven-3.9.12\bin\mvn.cmd' -o '-Dmaven.repo.local=.work/m2-repository' -f 'services\backend\pom.xml' '-Dtest=AnalysisServiceTest' test checkstyle:check
```

- Full Spring backend verification: **135 passed, 0 failures, 0 errors, 0 skipped**, Checkstyle **0 violations**, JAR package succeeded.

```powershell
$env:JAVA_HOME = (Resolve-Path '.work\toolchain\jdk-21.0.12.1+1').Path
& '.work\maven-home\wrapper\dists\apache-maven-3.9.12-bin\78a7b4ce\apache-maven-3.9.12\bin\mvn.cmd' -o '-Dmaven.repo.local=.work/m2-repository' -f 'services\backend\pom.xml' test checkstyle:check package
```
- At the time of the provider-rights follow-up, the chart request and candle API were limited to two hours/seven days, so EMA200 daily warm-up and usable four-timeframe provider output remained unavailable; this was made an explicit suppression case rather than weakening the gate. The later bounded-history change is recorded below.

## Bounded-history and session-calendar follow-up — 2026-10-22

- Provider analysis now reads a separate 350-calendar-day, 100,000-minute-bar maximum history window; the public chart query remains capped at seven days. The unfinalized live minute is excluded, and each analysis-engine timeframe is capped to the latest 5,000 candles.
- The maintained regular-session calendar now covers verified NSE/BSE equity cash and equity-derivatives dates from 2025-11-05 through 2026-12-31. It recognizes listed holidays, treats a missing regular session across dates as incomplete, and fails closed across unmodeled 2026-11-08 Muhurat. Expected partial trailing buckets are dropped only after validating their matured minute prefix; the 15-minute final H1 stub is not treated as a full hour.
- Added checks for the separate query limit, calendar boundaries, known holiday adjacency, a missing trading day, the special-session gap, partial current buckets, and the shortened final hourly stub.
- Focused history/calendar/OpenAPI checks: **25 passed**. Full Java 21 backend suite: **142 passed, 0 failures, 0 errors, 0 skipped**. Checkstyle: **0 violations**. Spring Boot JAR packaging succeeded. The first full-suite attempt was blocked by sandbox loopback restrictions; the final run passed after local network permission was granted.
- The licensed provider, real historical feed, corporate-action adjustment feed, and PostgreSQL/Redis runtime are still unverified. Tests use deterministic in-memory/fake records only, so no provider directional signal is enabled by this change.

## Database-side history bounds — 2026-10-22

- Candle queries now apply exact-source filtering in the database and request at most the configured candle limit plus one row. This keeps chart and 350-day analysis reads bounded before Java collects/maps results; overflow still fails with `CANDLE_LIMIT_EXCEEDED`.
- Gap queries are also paginated and source-filtered. The response includes at most 1,000 gap intervals; when more exist it retains `DATA_GAPS_PRESENT` and adds `DATA_GAP_LIST_TRUNCATED`.
- Focused candle-service tests: **7 passed**. Full Java 21 backend suite: **144 passed, 0 failures, 0 errors, 0 skipped**. Checkstyle: **0 violations**. A first full-suite run hit the sandbox loopback restriction; the repeat after local test access was granted passed.
- These checks use H2 and deterministic test records. PostgreSQL query plans/performance and a licensed provider remain unverified.

## Persisted paging integration verification — 2026-10-22

- Added a Spring/H2 integration case that stores overlapping candles and gap records from two sources, then exercises the service's exact-source pageable queries and confirms the response contains only the requested source.
- Focused market-data context tests: **3 passed**. Full Java 21 backend suite: **145 passed, 0 failures, 0 errors, 0 skipped**. Checkstyle: **0 violations**. The current Spring Boot JAR packages successfully.
- H2 confirms repository query behavior and source filtering; production PostgreSQL query plans, large-history latency, and licensed-provider runtime remain open.

## Latest combined backend verification — 2026-10-22

- After adding minute-aligned import boundaries, explicit full-range completion confirmation, null-page rejection, and the persisted source-filter query test, the full Java 21 backend suite passed: **149 tests, 0 failures, 0 errors, 0 skipped**.
- Checkstyle reported **0 violations** and the Spring Boot executable JAR packaged successfully. Loopback was enabled only for local integration-test traffic.
- Licensed historical data, PostgreSQL/Redis runtime, and production query performance remain unverified.
