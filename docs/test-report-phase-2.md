# Phase 2 verification report

Date: 2026-10-22 (Asia/Kolkata)

## Results

- Phase 2 market-data suite: **25 tests passed, 0 failures, 0 errors, 0 skipped** across candle aggregation, historical import, instrument catalogue, candle query, application context, tick normalization, and restart recovery.
- Full backend suite: **90 tests passed, 0 failures, 0 errors, 0 skipped** on Java 21.
- Full backend Checkstyle, including test sources: **0 violations**.
- Spring application context test: passed on the `test` profile with an in-memory H2 database and synthetic instrument/candle fixture.
- Recovery tests prove the latest finalized candle is restored before recent same-version ticks are replayed, and receipts from an old instrument-master version are ignored.
- Backend package: passed; executable artifact created at `services/backend/target/backend-0.1.0-SNAPSHOT.jar`.
- No live broker order or real market-data request was made.

## Commands

From `services/backend`, with Java 21 configured:

```powershell
$repoRoot = (Resolve-Path ..\..).Path
$env:MAVEN_USER_HOME = Join-Path $repoRoot '.work\maven-home'
$mavenRepo = Join-Path $repoRoot '.work\maven-repo'
.\mvnw.cmd -o -B "-Dmaven.repo.local=$mavenRepo" `
  '-Dtest=CandleAggregatorTest,HistoricalImportServiceTest,InstrumentControllerTest,MarketCandleServiceTest,MarketDataContextTest,MarketTickNormalizerTest,MarketDataRecoveryServiceTest' test
.\mvnw.cmd -o -B "-Dmaven.repo.local=$mavenRepo" package
.\mvnw.cmd -o -B "-Dmaven.repo.local=$mavenRepo" `
  '-Dcheckstyle.includeTestSourceDirectory=true' checkstyle:check
```

## Not verified

- Docker API access returns `permission denied` for both `dockerDesktopLinuxEngine` and `docker_engine`; starting Docker Desktop did not restore access, and `wsl.exe --status` returns `WSL/E_ACCESSDENIED`. Therefore PostgreSQL/Redis Compose startup, Flyway migration V2/V5 against PostgreSQL, Postgres row-lock rate-limit behavior, and database-backed outage/restart integration were not verified.
- H2 context startup confirms dependency wiring and generated test schema only; it does not validate the PostgreSQL-specific Flyway SQL.
- A licensed market-data source and written rights for its data uses remain unconfirmed. Only deterministic synthetic sample data is available.

## Historical import contract hardening — 2026-10-22

- Import ranges now require whole-minute inclusive-start/exclusive-end boundaries; a sub-minute request is rejected before instrument/provider work.
- A provider page distinguishes `exhausted` from `rangeComplete`. A job only completes when the final page confirms the whole requested range was checked; a null candle list or exhausted-but-incomplete page fails closed and does not persist that page's candles.
- Historical import service checks: **11 passed**. Latest full Java 21 backend suite: **149 passed, 0 failures, 0 errors, 0 skipped**. Checkstyle: **0 violations**. Spring Boot JAR package succeeded.
- These tests use deterministic fake pages and H2 only. No licensed provider has been enabled, and PostgreSQL locking/provider coverage semantics remain unverified.

## Durable receipt recovery follow-up — 2026-10-22

- Tick receipts now move through `RECEIVED`, `BUFFERED`, `FINALIZED`, or `RECONCILIATION_REQUIRED`. Ingestion records rejection reasons, finalized candle writes complete matching buffered receipts in the same database transaction, and restart recovery only replays recent recoverable receipts. Expired, inactive-instrument, unsupported-source, changed-master, and aggregator-rejected receipts are marked for reconciliation.
- Migration V9 marks old receipts `LEGACY_RECEIPT_STATE_UNKNOWN` so recovery does not guess whether old ticks were accepted. The H2 migration smoke test confirms the legacy backfill and outcome-shape constraint.
- Focused Java tests: **6 passed, 0 failures, 0 errors** across ingestion, restart recovery, and V9 migration. Checkstyle including test sources: **0 violations**.
- Full backend suite from the specified OneDrive project path: **153 passed, 0 failures, 0 errors, 0 skipped** after scoped localhost/network permission allowed the internal analysis service to bind. The earlier restricted run's 18 socket errors were environmental and were cleared by the successful full run.
- Backend Checkstyle including tests: **0 violations**. Spring Boot package succeeded at `services/backend/target/backend-0.1.0-SNAPSHOT.jar`.
- The PostgreSQL migration and actual PostgreSQL/Redis restart behavior remain unverified. Live orders and licensed market-data requests remain disabled.

## H2-compatible duplicate claim and final verification — 2026-10-22

- H2 local-demo ingestion now uses a standard SQL insert-only claim. Duplicate event keys are detected safely without database-specific `ON CONFLICT` SQL or an assigned-ID repository merge/upsert.
- The H2 lifecycle integration test covers first claim, buffering, finalization, and duplicate claim. Full backend suite: **154 tests passed, 0 failures, 0 errors, 0 skipped**.
- Checkstyle including test sources: **0 violations**. Spring Boot package succeeded at `services/backend/target/backend-0.1.0-SNAPSHOT.jar`.
- No broker credentials, real market-data requests, or live orders were used. PostgreSQL/Redis runtime and the PostgreSQL migration still need Docker-backed verification.
