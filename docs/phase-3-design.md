# Phase 3 design: deterministic analysis

## Scope

Build the analysis boundary in `services/analysis` with FastAPI, pandas, and NumPy. The service accepts only authenticated internal requests containing completed candles. The Phase 4 Spring bridge now performs the product integration so browser and user tokens never reach this service directly; only the internal service token is used server-side.

## API and trust boundary

- `POST /internal/v1/analyze` validates the instrument, active tick size, master version, UTC candle timestamps, OHLC bounds, volume, freshness, completeness, and source rights.
- `GET /analyze/{instrument_id}?timeframe=15m` returns only the most recent precomputed response. Missing cache data is explicit; it does not invent candles or silently call an unconfigured source.
- `POST /internal/v1/precompute` accepts at most 100 requests and limits computation concurrency to four.
- `GET /internal/v1/metrics` reports calculation count and p50/p95 calculation latency.
- Every internal route requires a configured random bearer token of at least 32 characters and is limited to 120 requests per minute per credential in each process. Health liveness is public to the private container network; the analysis port is not published to the host.
- `X-Correlation-ID` is validated or regenerated and returned on success and error responses. Invalid request details are reduced to a stable safe error code.

## Deterministic calculations

- EMA 20/50/200, Wilder ATR/RSI/ADX 14, MACD 12/26/9, and 20-candle volume context are calculated with pandas/NumPy. Warm-up values remain null; NaN and infinity never leave the service.
- The authenticated Spring analysis response carries those same indicator values through to the Phase 4 card without reimplementing calculations. The synthetic demo can show them for inspection, while all directional output stays suppressed; missing warm-up values remain null and appear as unavailable rather than zero.
- Provider analysis is bound to one exact `ANALYSIS_PROVIDER_SOURCE_ID` and remains disabled unless storage, cache, end-user-display, and derived-analytics rights are each explicitly confirmed. All flags default to false. A confirmation is an operator record after written source terms have been reviewed; setting an environment flag does not establish contractual permission.
- Provider candles must all have provider quality. Spring independently checks source identity, freshness, requested-timeframe completeness, all four timeframe series, and corporate-action verification before exposing directional bias or zones. Gaps are passed through to the analysis engine (up to 1,000 intervals); either the backend or engine can suppress output. Synthetic data remains non-actionable regardless of provider flags.
- Corporate-action verification is a separate default-off gate. If it is absent or the source marks a corporate-action warning, indicators may remain visible with a warning, but bias, zones, and directional summary are withheld.
- Supply/demand zones use ATR(14), an impulse body greater than 1.5 ATR, and a 1–3 candle base whose range is at most 0.75 ATR. Re-entry counts touches; a finalized close beyond the far edge invalidates the zone.
- Zone ranking is a documented heuristic, not a probability: freshness 25%, impulse strength 25%, impulse volume 15%, current trend agreement 20%, and higher-timeframe alignment 15%. The nearest three valid zones per side are returned.
- Historical reaction statistics count completed zone re-entries followed by a close 0.5 detection-ATR beyond the near edge before invalidation within 20 candles. A fraction is withheld below five samples. Survivorship and regime limits are always disclosed.
- A directional bias requires fresh, complete, rights-cleared data, EMA200 warm-up across 5m/15m/1h/1d, and agreement from at least three timeframes without opposite evidence. Otherwise the bias is neutral and the signal is suppressed. Scenarios are illustrative, use a 0.25 ATR stop buffer and a 2:1 pre-cost target, and never become orders.
- OI, PCR, delivery, depth, sector, and index context remain explicitly unavailable until a licensed source is connected.

## Data quality and caching

- Requests are limited to 5,000 candles per timeframe and 4 MiB. Only finalized candles are accepted; timestamps are UTC and strictly ordered; all OHLC values must align to the supplied instrument tick size.
- Warnings cover simulated/stale data, gaps, incomplete series, future timestamps, outliers, corporate actions, zero-volume context, and insufficient warm-up history.
- Cache key includes instrument, timeframe, a content-derived data version, algorithm version, and configuration version. Cache uses a 90-second TTL and a 1,000-entry bound. Concurrent identical misses share one calculation. Cache expiration returns an explicit miss; age-based stale analysis is returned with signal, bias, and scenario suppressed.
- Cache is process-local. Multi-instance cache coordination and incremental candle recomputation are deferred until the service is connected to the backend and Redis integration is justified.

## Limits

Phase 2 currently provides only a two-hour synthetic chart fixture, so it cannot warm up EMA 200 or produce a usable bias. The provider-analysis path now queries a separate 350-calendar-day window of finalized one-minute bars (maximum 100,000 raw bars) without widening the chart API's seven-day cap. It checks daily session continuity against maintained NSE/BSE cash and equity-derivatives notices from 5 November 2025 through 31 December 2026, recognizes listed holidays, rejects unsupported dates and the unmodeled 8 November 2026 Muhurat session, omits an in-progress trailing bucket, and caps engine payloads at 5,000 candles per timeframe. A 200-day EMA still requires at least 200 valid daily candles; incomplete history remains non-actionable. Simulated data always suppresses signals and scenarios. The provider-rights path has only deterministic fake-source tests; there is no licensed real-time/history provider, corporate-action adjustment feed, event calendar, or broker connection. This service is an internal analysis component, not financial advice or an execution engine.
