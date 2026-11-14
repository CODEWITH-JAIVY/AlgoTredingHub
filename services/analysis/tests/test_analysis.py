from __future__ import annotations

import asyncio
import json
import unittest
from datetime import datetime, time, timedelta, timezone
from decimal import Decimal
from zoneinfo import ZoneInfo

from pydantic import ValidationError

from analysis_app.cache import AnalysisCache
from analysis_app.engine import analyze, request_data_version
from analysis_app.models import (
    AnalysisRequest, Candle, CandleSeries, DataFreshness, GapInterval, Timeframe,
)


NOW = datetime(2026, 10, 20, 10, 0, tzinfo=timezone.utc)
MASTER = "DEMO-1"
IST = ZoneInfo("Asia/Kolkata")


def make_series(timeframe: Timeframe, count: int = 230, freshness: DataFreshness = DataFreshness.FRESH,
                complete: bool = True, gaps: list[GapInterval] | None = None,
                bearish: bool = False) -> CandleSeries:
    interval = timeframe.seconds // 60
    sessions: list[datetime] = []
    day = NOW.astimezone(IST).date()
    while len(sessions) < count:
        if day.weekday() < 5:
            if timeframe == Timeframe.D1:
                candidate_times = [time(9, 15)]
            else:
                candidate_times = []
                for offset in range(0, 375 - interval + 1, interval):
                    minute_of_day = 9 * 60 + 15 + offset
                    candidate_times.append(time(minute_of_day // 60, minute_of_day % 60))
            for candidate_time in candidate_times:
                stamp = datetime.combine(day, candidate_time, tzinfo=IST)
                if stamp.astimezone(timezone.utc) <= NOW:
                    sessions.append(stamp.astimezone(timezone.utc))
        day -= timedelta(days=1)
    timestamps = sorted(sessions)[-count:]

    candles: list[Candle] = []
    previous = Decimal("120.00") if bearish else Decimal("100.00")
    base_start = count - 5
    impulse_index = count - 2
    for index, stamp in enumerate(timestamps):
        open_price = previous
        if base_start <= index < impulse_index:
            close_price = open_price
            high = close_price + Decimal("0.05")
            low = close_price - Decimal("0.05")
        elif index == impulse_index:
            close_price = open_price + (Decimal("-0.55") if bearish else Decimal("0.55"))
            high = max(open_price, close_price) + Decimal("0.05")
            low = min(open_price, close_price) - Decimal("0.05")
        else:
            close_price = open_price + (Decimal("-0.05") if bearish else Decimal("0.05"))
            high = max(open_price, close_price) + Decimal("0.10")
            low = min(open_price, close_price) - Decimal("0.10")
        candles.append(Candle(timestamp=stamp, open=open_price, high=high, low=low, close=close_price,
                              volume=100 + index, finalized=True))
        previous = close_price

    simulated = freshness == DataFreshness.SIMULATED
    return CandleSeries(
        source_id="SYNTHETIC_DEMO" if simulated else "LICENSED_TEST_SOURCE",
        master_version=MASTER, freshness=freshness, complete=complete,
        storage_allowed=not simulated, cache_allowed=not simulated,
        display_allowed=not simulated, derived_analytics_allowed=not simulated,
        gaps=gaps or [], candles=candles,
    )


def make_request(freshness: DataFreshness = DataFreshness.FRESH, complete: bool = True,
                 count: int = 230, bearish_timeframes: tuple[Timeframe, ...] = ()) -> AnalysisRequest:
    data = {timeframe: make_series(timeframe, count=count, freshness=freshness, complete=complete,
                                   bearish=timeframe in bearish_timeframes)
            for timeframe in Timeframe}
    return AnalysisRequest(instrument_id="7b774ec2-a516-4a29-b2b4-3945256c7c2a", tick_size=Decimal("0.05"),
                           requested_timeframe=Timeframe.M15, timeframes=data)


class AnalysisEngineTests(unittest.TestCase):
    def test_fresh_complete_multitimeframe_data_produces_deterministic_bias_and_scenario(self) -> None:
        request = make_request()
        first = analyze(request, NOW)
        second = analyze(request, NOW)
        self.assertEqual(first.bias, "BULLISH")
        self.assertTrue(first.actionable)
        self.assertEqual(first.signal_status, "AVAILABLE")
        self.assertIsNotNone(first.scenario)
        self.assertGreater(first.scenario.risk_reward_ratio, 0)
        self.assertEqual(first.data_version, second.data_version)
        self.assertEqual(first.indicators, second.indicators)
        self.assertEqual(first.zones, second.zones)
        json.dumps(first.model_dump(mode="json"), allow_nan=False)

    def test_simulated_data_returns_metrics_but_suppresses_bias_and_scenario(self) -> None:
        result = analyze(make_request(freshness=DataFreshness.SIMULATED), NOW)
        self.assertEqual(result.freshness, DataFreshness.SIMULATED)
        self.assertEqual(result.bias, "NEUTRAL")
        self.assertFalse(result.actionable)
        self.assertIsNone(result.scenario)
        self.assertIn("SYNTHETIC_DATA_NOT_FOR_MARKET_DECISIONS", result.warnings)
        self.assertIn("simulated", result.summary.english[0].lower())

    def test_stale_incomplete_and_conflicting_data_suppress_signal(self) -> None:
        request = make_request(complete=False)
        result = analyze(request, NOW)
        self.assertFalse(result.actionable)
        self.assertIn("INCOMPLETE_CANDLE_SERIES", result.warnings)
        old = analyze(make_request(), NOW + timedelta(days=10))
        self.assertEqual(old.freshness, DataFreshness.STALE)
        self.assertEqual(old.bias, "NEUTRAL")
        self.assertIn("STALE_DATA", old.warnings)
        mixed = analyze(make_request(bearish_timeframes=(Timeframe.H1, Timeframe.D1)), NOW)
        self.assertEqual("NEUTRAL", mixed.bias)
        self.assertFalse(mixed.actionable)
        self.assertIn("TIMEFRAME_CONFLICT", mixed.warnings)

    def test_recorded_gap_outlier_and_future_candle_suppress_signal(self) -> None:
        request = make_request()
        data = dict(request.timeframes)
        series = data[Timeframe.M15]
        gap = GapInterval(start=series.candles[-2].timestamp, end=series.candles[-1].timestamp)
        data[Timeframe.M15] = CandleSeries.model_validate({**series.model_dump(), "gaps": [gap]})
        gap_result = analyze(request.model_copy(update={"timeframes": data}), NOW)
        self.assertIn("CANDLE_GAPS_DETECTED", gap_result.warnings)
        self.assertFalse(gap_result.actionable)

        series = make_series(Timeframe.M15)
        last = series.candles[-1]
        outlier = Candle(**{**last.model_dump(), "high": last.high + Decimal("50.00")})
        outlier_series = CandleSeries.model_validate({**series.model_dump(), "candles": [*series.candles[:-1], outlier]})
        data = dict(request.timeframes)
        data[Timeframe.M15] = outlier_series
        outlier_result = analyze(request.model_copy(update={"timeframes": data}), NOW)
        self.assertIn("POSSIBLE_PRICE_OUTLIER", outlier_result.warnings)
        self.assertFalse(outlier_result.actionable)

        future = analyze(request, NOW - timedelta(minutes=30))
        self.assertIn("CANDLE_TIMESTAMP_IN_FUTURE", future.warnings)
        self.assertFalse(future.actionable)

    def test_warmup_indicators_remain_null_and_zero_volume_is_explicit(self) -> None:
        request = make_request(count=30)
        result = analyze(request, NOW)
        self.assertIsNone(result.indicators.ema50)
        self.assertIsNone(result.indicators.ema200)
        self.assertIn("INSUFFICIENT_HISTORY_EMA200", result.warnings)
        data = dict(request.timeframes)
        series = data[Timeframe.M15]
        zeros = [Candle(**{**candle.model_dump(), "volume": 0}) for candle in series.candles]
        data[Timeframe.M15] = CandleSeries.model_validate({**series.model_dump(), "candles": zeros})
        zero_request = request.model_copy(update={"timeframes": data})
        zero_result = analyze(zero_request, NOW)
        self.assertEqual(0.0, zero_result.indicators.volume_average20)
        self.assertIn("VOLUME_CONTEXT_UNAVAILABLE", zero_result.warnings)

    def test_rights_master_version_tick_alignment_and_finalization_are_validated(self) -> None:
        series = make_series(Timeframe.M15)
        changed = series.model_copy(update={"derived_analytics_allowed": False})
        with self.assertRaises(ValidationError):
            AnalysisRequest(instrument_id="7b774ec2-a516-4a29-b2b4-3945256c7c2a", tick_size=Decimal("0.05"),
                            requested_timeframe=Timeframe.M15, timeframes={Timeframe.M15: changed})
        with self.assertRaises(ValidationError):
            Candle(timestamp=NOW, open=Decimal("0"), high=Decimal("1"), low=Decimal("0"),
                   close=Decimal("1"), volume=0, finalized=True)
        with self.assertRaises(ValidationError):
            Candle(timestamp=datetime(2026, 10, 20, 10, 0), open=Decimal("1"), high=Decimal("1"),
                   low=Decimal("1"), close=Decimal("1"), volume=0, finalized=True)
        with self.assertRaises(ValidationError):
            CandleSeries.model_validate({**series.model_dump(), "candles": [
                *series.candles[:-1], series.candles[-1].model_copy(update={"finalized": False})
            ]})
        misaligned = series.candles[-1]
        misaligned_series = CandleSeries.model_validate({**series.model_dump(), "candles": [
            *series.candles[:-1], Candle(**{**misaligned.model_dump(), "close": misaligned.close + Decimal("0.01")})
        ]})
        with self.assertRaises(ValidationError):
            AnalysisRequest(instrument_id="7b774ec2-a516-4a29-b2b4-3945256c7c2a", tick_size=Decimal("0.05"),
                            requested_timeframe=Timeframe.M15, timeframes={Timeframe.M15: misaligned_series})
        mismatched = {timeframe: make_series(timeframe) for timeframe in Timeframe}
        mismatched[Timeframe.M5] = CandleSeries.model_validate(
            {**make_series(Timeframe.M5).model_dump(), "master_version": "DEMO-2"}
        )
        with self.assertRaises(ValidationError):
            AnalysisRequest(instrument_id="7b774ec2-a516-4a29-b2b4-3945256c7c2a", tick_size=Decimal("0.05"),
                            requested_timeframe=Timeframe.M15, timeframes=mismatched)

    def test_cache_key_changes_with_input_and_only_returns_finite_json(self) -> None:
        first = make_request()
        second = make_request(count=229)
        self.assertNotEqual(request_data_version(first), request_data_version(second))
        payload = analyze(first, NOW).model_dump(mode="json")
        json.dumps(payload, allow_nan=False)


class AnalysisCacheTests(unittest.IsolatedAsyncioTestCase):
    async def test_concurrent_identical_misses_share_one_calculation(self) -> None:
        cache = AnalysisCache(ttl_seconds=2, max_entries=4)
        request = make_request(freshness=DataFreshness.SIMULATED)
        calls = 0

        async def compute():
            nonlocal calls
            calls += 1
            await asyncio.sleep(0.02)
            return analyze(request, NOW)

        results = await asyncio.gather(*[cache.get_or_compute(request, compute) for _ in range(6)])
        self.assertEqual(calls, 1)
        self.assertEqual(sum(cached for _, cached in results), 5)
        self.assertEqual(len({item.data_version for item, _ in results}), 1)

    async def test_expired_entry_is_not_returned_as_current(self) -> None:
        cache = AnalysisCache(ttl_seconds=0.01, max_entries=4)
        request = make_request(freshness=DataFreshness.SIMULATED)

        async def compute():
            return analyze(request, NOW)

        await cache.get_or_compute(request, compute)
        await asyncio.sleep(0.02)
        self.assertIsNone(await cache.latest(request.instrument_id, request.requested_timeframe))

    async def test_late_old_series_does_not_replace_newer_latest_result(self) -> None:
        cache = AnalysisCache(ttl_seconds=2, max_entries=4)
        newer = make_request(freshness=DataFreshness.SIMULATED)
        older_series = {
            timeframe: CandleSeries.model_validate({
                **series.model_dump(),
                "candles": [candle.model_copy(update={"timestamp": candle.timestamp - timedelta(days=1)})
                            for candle in series.candles],
            })
            for timeframe, series in newer.timeframes.items()
        }
        older = newer.model_copy(update={"timeframes": older_series})

        async def compute_newer():
            return await asyncio.to_thread(analyze, newer, NOW)

        async def compute_older():
            return await asyncio.to_thread(analyze, older, NOW)

        await cache.get_or_compute(newer, compute_newer)
        await cache.get_or_compute(older, compute_older)
        latest = await cache.latest(newer.instrument_id, newer.requested_timeframe)
        self.assertEqual(newer.timeframes[newer.requested_timeframe].candles[-1].timestamp,
                         latest.data_timestamp)


if __name__ == "__main__":
    unittest.main()
