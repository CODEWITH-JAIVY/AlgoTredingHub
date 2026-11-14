# Phase 2 plan: market data and candles

## Decisions and scope

- The user selected mock/sample data until a licensed market-data source is confirmed. No NSE/BSE or broker data endpoint is called in this phase.
- Keep a provider-neutral ingestion contract. The only enabled provider is `SYNTHETIC_DEMO`; it is visibly synthetic and is never described as live, exchange-verified, or suitable for order decisions.
- Use `Instant` in UTC in storage and API timestamps. Exchange session display uses `Asia/Kolkata`.
- Support finalized one-minute candles from validated trade ticks. Do not claim larger timeframes until aggregation, session calendars, and source corrections are verified.
- Keep the current in-progress one-minute candle separate from finalized history. Record gaps as metadata; never fill missing intervals with invented prices.
- Treat the instrument master as versioned, time-sensitive input. Inactive, unknown, or stale-version instruments fail ingestion closed.
- Do not enable non-display analytics, sharing, redistribution, or order use for any real market-data source until its written agreement is reviewed for those uses.

## Data-source review

Checked 2026-10-20 against official exchange pages. NSE says subscriber use is governed by a relevant agreement that specifies permitted handling, display, transmission, and dissemination; redistribution is restricted except as agreed. BSE lists real-time, delayed, historical, and reference products with signup, plan, agreement, and pricing steps. These pages establish that source-specific terms and commercial permissions matter, but they do not grant AlgoTrade Hub any rights. The selected source, storage/cache/display/derived-data rights, fees, segments, and end-user model remain unconfirmed. No market feed can be enabled until those are resolved in writing.

The proposed first broker/data provider, Zerodha Kite Connect, has a separate technical and terms review in [zerodha-kite-review.md](zerodha-kite-review.md). That review does not enable Kite data or grant usage rights; synthetic demo remains the only configured source.

- [NSE Data Sharing & Usage Policy](https://www.nseindia.com/static/market-data/nse-data-policy)
- [BSE Self Data Feed](https://marketdata.bseindia.com/)
- [BSE Information Products tariff](https://www.bseindia.com/downloads1/Information_Products_Pricing_Sheet.pdf)

## Planned API and schema

- Extend instrument catalogue responses with master version and source metadata while withholding provider tokens.
- Replace the misleading fixed-price quote timestamp with explicit synthetic/freshness metadata.
- Add authenticated `GET /api/v1/market/candles` for one-minute data. A request must provide instrument, timeframe, and bounded range; unsupported intervals and ranges are rejected.
- Add versioned Postgres migrations for instrument-master snapshots, finalized candles, detected gaps, restart-safe tick receipts, and resumable history-import checkpoints.
- A history source must declare license/use metadata and request limits. Import ranges use minute-aligned inclusive-start/exclusive-end boundaries. Import pages are idempotent, checkpointed, and limited per job and per provider across jobs/instances. The provider agreement must permit persistent storage and end-user display; redistribution and derived analytics stay separately gated. Provider candles are checked for instrument-master version, chronological order, minute alignment, OHLC integrity, price-column precision, and exchange tick size. Page exhaustion is distinct from range completeness: only a final page that explicitly confirms full requested-range coverage can complete a job; a null candle list or incomplete final range fails closed. With no licensed source configured, the importer is inert.

## Reliability and edge cases

Validate the instrument identity/version, exchange tick size, positive prices and trade quantities, nonnegative candle volume, timestamp skew, session eligibility, source identity, and sequence/event id. Deduplicate before aggregation. Keep normalized receipts for seven days and replay the last eight minutes after a restart. Order ticks by exchange timestamp and sequence, tolerate only a bounded late-arrival window, and reject ticks for already finalized bars for reconciliation rather than silently rewriting history. Hold the per-instrument buffer through the candle write; a failed Postgres write leaves the bar pending for retry. Handle minute boundaries in Asia/Kolkata while persisting UTC. Preserve zero-volume candles only when explicitly provided by an approved historical source; do not fabricate empty candles. Flag current/incomplete candles, stale data, gaps, and source limitations in every response. The demo calendar covers verified regular NSE/BSE cash and equity-derivatives sessions from 2025-11-05 through 2026-12-31, fails closed outside that range, and excludes unmodeled special sessions; it is not an official continuously maintained status feed and cannot enable any live adapter by configuration alone. See [market-calendar.md](market-calendar.md).

## Acceptance criteria

1. Java tests cover normalization, tick/lot precision, minute boundary conversion, duplicate delivery, out-of-order events, late-event rejection, gap detection, and current-versus-final candle state.
2. Historical import tests prove same-range retries do not duplicate records, page checkpoints resume correctly, global provider throttling applies across jobs, malformed OHLC/tick-size pages are rejected, and partial imports stay incomplete.
3. API output never calls synthetic data live/fresh or current market prices; unsupported/unavailable data carries an explicit reason and quality state.
4. Backend Maven tests and package pass. PostgreSQL migration/startup is run when Docker is available and is reported separately from unit tests.
5. No provider credential, exchange payload, or real market-data request is introduced.
