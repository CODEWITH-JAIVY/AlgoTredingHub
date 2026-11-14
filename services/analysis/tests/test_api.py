from __future__ import annotations

import asyncio
import os
import unittest
from unittest.mock import patch
from datetime import timedelta
from uuid import UUID, uuid4

import httpx

from analysis_app.main import InternalRateLimiter, app
from analysis_app.models import CandleSeries, DataFreshness
from test_analysis import make_request


class AnalysisApiTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.token = "test-analysis-internal-token-32-bytes-minimum"
        self.environment = patch.dict(os.environ, {"ANALYSIS_INTERNAL_TOKEN": self.token})
        self.environment.start()
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")

    async def asyncTearDown(self) -> None:
        await self.client.aclose()
        self.environment.stop()

    async def test_private_analysis_write_and_cached_read_require_internal_token(self) -> None:
        request = make_request(freshness=DataFreshness.SIMULATED)
        path = f"/analyze/{request.instrument_id}?timeframe=15m"
        denied = await self.client.post("/internal/v1/analyze", json=request.model_dump(mode="json"))
        self.assertEqual(401, denied.status_code)

        headers = {"Authorization": f"Bearer {self.token}"}
        written = await self.client.post("/internal/v1/analyze", json=request.model_dump(mode="json"), headers=headers)
        self.assertEqual(200, written.status_code, written.text)
        self.assertEqual("SUPPRESSED", written.json()["signal_status"])
        self.assertIn("SYNTHETIC_DATA_NOT_FOR_MARKET_DECISIONS", written.json()["warnings"])

        read = await self.client.get(path, headers=headers)
        self.assertEqual(200, read.status_code, read.text)
        self.assertFalse(read.json()["actionable"])

    async def test_missing_internal_secret_keeps_service_unready(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            response = await self.client.get("/health/ready")
        self.assertEqual(503, response.status_code)

    async def test_get_miss_is_explicit_and_does_not_fetch_unconfigured_data(self) -> None:
        headers = {"Authorization": f"Bearer {self.token}"}
        response = await self.client.get("/analyze/ed4cb678-8bd8-4c5c-9aef-f781f1306d10?timeframe=1h", headers=headers)
        self.assertEqual(404, response.status_code)
        self.assertEqual("ANALYSIS_NOT_PRECOMPUTED", response.json()["detail"]["code"])

    async def test_oversized_request_is_rejected_before_model_parsing(self) -> None:
        response = await self.client.post("/internal/v1/analyze", headers={
            "Authorization": f"Bearer {self.token}", "Content-Length": "4194305",
        })
        self.assertEqual(413, response.status_code)

    async def test_invalid_correlation_id_is_regenerated(self) -> None:
        response = await self.client.get("/health/live", headers={"X-Correlation-ID": "invalid"})
        self.assertEqual(200, response.status_code)
        UUID(response.headers["X-Correlation-ID"])

    async def test_late_old_series_is_suppressed_and_latest_cache_stays_newer(self) -> None:
        headers = {"Authorization": f"Bearer {self.token}"}
        newer = make_request(freshness=DataFreshness.SIMULATED).model_copy(update={"instrument_id": uuid4()})
        older_series = {
            timeframe: CandleSeries.model_validate({
                **series.model_dump(),
                "candles": [candle.model_copy(update={"timestamp": candle.timestamp - timedelta(days=1)})
                            for candle in series.candles],
            })
            for timeframe, series in newer.timeframes.items()
        }
        older = newer.model_copy(update={"timeframes": older_series})
        newer_result = await self.client.post("/internal/v1/analyze", json=newer.model_dump(mode="json"), headers=headers)
        older_result = await self.client.post("/internal/v1/analyze", json=older.model_dump(mode="json"), headers=headers)
        self.assertEqual(200, newer_result.status_code)
        self.assertEqual(200, older_result.status_code)
        self.assertIn("SUPERSEDED_DATA_VERSION", older_result.json()["warnings"])
        latest = await self.client.get(f"/analyze/{newer.instrument_id}?timeframe=15m", headers=headers)
        self.assertEqual(newer_result.json()["data_version"], latest.json()["data_version"])


class InternalRateLimiterTests(unittest.IsolatedAsyncioTestCase):
    async def test_rate_limit_is_per_credential_and_recovers_after_window(self) -> None:
        limiter = InternalRateLimiter(limit=2, window_seconds=0.02)
        self.assertTrue(await limiter.allow("credential-a"))
        self.assertTrue(await limiter.allow("credential-a"))
        self.assertFalse(await limiter.allow("credential-a"))
        self.assertTrue(await limiter.allow("credential-b"))
        await asyncio.sleep(0.03)
        self.assertTrue(await limiter.allow("credential-a"))


if __name__ == "__main__":
    unittest.main()
