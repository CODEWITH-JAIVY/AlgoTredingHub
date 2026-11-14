# Analysis service

Python/FastAPI service for deterministic, informational analysis. It does not place orders and does not connect to market-data providers. The Spring backend sends validated candle series to this internal service; the browser never receives its internal token. Synthetic development input suppresses bias and scenarios.

## Local run

From this directory, use Python 3.12 and install the pinned dependencies:

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt -r requirements-test.txt
$env:ANALYSIS_INTERNAL_TOKEN = 'use-a-random-secret-at-least-32-characters'
uvicorn analysis_app.main:app --reload --port 8000
```

For production, set a unique random token through the secret manager. The service refuses internal requests when the token is absent or shorter than 32 characters. Do not expose its port directly to browsers or the public internet.

## API

- `GET /health/live` — liveness only.
- `GET /health/ready` — readiness; requires a configured internal token.
- `POST /internal/v1/analyze` — authenticated, validates candle series, computes analysis, and caches the versioned response.
- `POST /internal/v1/precompute` — authenticated batch of up to 100 instrument/timeframe requests; bounded to four concurrent calculations.
- `GET /analyze/{instrument_id}?timeframe=15m` — authenticated latest cached analysis; returns 404 when nothing has been precomputed.
- `GET /internal/v1/metrics` — authenticated computation count and p50/p95 latency.

Internal routes require `Authorization: Bearer <ANALYSIS_INTERNAL_TOKEN>`. Responses carry a validated or regenerated `X-Correlation-ID`. Request data uses UTC timestamps, finalized candles, one shared instrument-master version, tick-aligned positive OHLC, and explicit storage/cache/display/derived-analysis rights. Synthetic candles are accepted only for development analysis and always suppress market signals.

Only 5m, 15m, 1h, and 1d series are accepted. Missing OI, PCR, delivery, depth, sector, and index data are reported as unavailable. Analysis is descriptive and is never an order instruction.

## Checks

Run `python -m unittest discover -s tests -v`. Tests use deterministic locally generated candles; they make no network or broker calls.
