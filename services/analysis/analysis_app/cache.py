from __future__ import annotations

import asyncio
import time
from collections import OrderedDict
from collections.abc import Awaitable, Callable
from uuid import UUID

from .engine import ALGORITHM_VERSION, request_data_version
from .models import AnalysisRequest, AnalysisResponse, Timeframe


class AnalysisCache:
    """Bounded process-local cache. Cache keys include instrument, data, algorithm, and config versions."""

    def __init__(self, ttl_seconds: float = 90.0, max_entries: int = 1000) -> None:
        self.ttl_seconds = ttl_seconds
        self.max_entries = max_entries
        self._entries: OrderedDict[str, tuple[AnalysisResponse, float]] = OrderedDict()
        self._latest: dict[str, str] = {}
        self._key_locks: dict[str, asyncio.Lock] = {}
        self._registry_lock = asyncio.Lock()

    @staticmethod
    def _cache_key(request: AnalysisRequest) -> str:
        return "|".join((str(request.instrument_id), request.requested_timeframe.value,
                         request_data_version(request), ALGORITHM_VERSION, request.config_version))

    @staticmethod
    def _latest_key(instrument_id: UUID, timeframe: Timeframe) -> str:
        return f"{instrument_id}|{timeframe.value}"

    async def get_or_compute(self, request: AnalysisRequest,
                             compute: Callable[[], Awaitable[AnalysisResponse]]) -> tuple[AnalysisResponse, bool]:
        key = self._cache_key(request)
        current = self._get_key(key)
        if current is not None:
            return current, True
        async with self._registry_lock:
            lock = self._key_locks.setdefault(key, asyncio.Lock())
        try:
            async with lock:
                current = self._get_key(key)
                if current is not None:
                    return current, True
                response = await compute()
                self._store(key, request, response)
                return response, False
        finally:
            async with self._registry_lock:
                if self._key_locks.get(key) is lock and not lock.locked():
                    self._key_locks.pop(key, None)

    async def latest(self, instrument_id: UUID, timeframe: Timeframe) -> AnalysisResponse | None:
        key = self._latest.get(self._latest_key(instrument_id, timeframe))
        return None if key is None else self._get_key(key)

    def _get_key(self, key: str) -> AnalysisResponse | None:
        item = self._entries.get(key)
        if item is None:
            return None
        response, expires = item
        if time.monotonic() >= expires:
            self._entries.pop(key, None)
            for latest_key, latest_value in list(self._latest.items()):
                if latest_value == key:
                    self._latest.pop(latest_key, None)
            return None
        self._entries.move_to_end(key)
        return response

    def _store(self, key: str, request: AnalysisRequest, response: AnalysisResponse) -> None:
        self._entries[key] = (response, time.monotonic() + self.ttl_seconds)
        self._entries.move_to_end(key)
        latest_key = self._latest_key(request.instrument_id, request.requested_timeframe)
        previous_key = self._latest.get(latest_key)
        previous = self._entries.get(previous_key) if previous_key is not None else None
        if previous is None or previous[0].data_timestamp <= response.data_timestamp:
            self._latest[latest_key] = key
        while len(self._entries) > self.max_entries:
            evicted, _ = self._entries.popitem(last=False)
            for latest_key, latest_value in list(self._latest.items()):
                if latest_value == evicted:
                    self._latest.pop(latest_key, None)


class LatencyMetrics:
    def __init__(self, max_samples: int = 1000) -> None:
        self._samples: list[float] = []
        self._max_samples = max_samples
        self.computations = 0

    def record(self, duration_ms: float) -> None:
        self.computations += 1
        self._samples.append(duration_ms)
        if len(self._samples) > self._max_samples:
            del self._samples[: len(self._samples) - self._max_samples]

    @staticmethod
    def _percentile(samples: list[float], percentile: float) -> float | None:
        if not samples:
            return None
        ordered = sorted(samples)
        position = (len(ordered) - 1) * percentile
        low = int(position)
        high = min(low + 1, len(ordered) - 1)
        ratio = position - low
        return round(ordered[low] * (1 - ratio) + ordered[high] * ratio, 3)

    def snapshot(self) -> dict[str, float | int | None]:
        return {
            "computation_count": self.computations,
            "sample_count": len(self._samples),
            "p50_ms": self._percentile(self._samples, 0.50),
            "p95_ms": self._percentile(self._samples, 0.95),
        }
