from __future__ import annotations

import asyncio
import statistics
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tests"))

from analysis_app.cache import AnalysisCache  # noqa: E402
from analysis_app.engine import analyze  # noqa: E402
from test_analysis import NOW, make_request  # noqa: E402


def main() -> None:
    request = make_request()
    for _ in range(5):
        analyze(request, NOW)
    samples: list[float] = []
    for _ in range(100):
        started = time.perf_counter()
        analyze(request, NOW)
        samples.append((time.perf_counter() - started) * 1000)
    ordered = sorted(samples)
    cached_samples = asyncio.run(measure_cache(request))
    cached_ordered = sorted(cached_samples)
    print({
        "bar_count": sum(len(series.candles) for series in request.timeframes.values()),
        "runs": len(samples),
        "p50_ms": round(statistics.median(ordered), 3),
        "p95_ms": round(ordered[int(0.95 * (len(ordered) - 1))], 3),
        "max_ms": round(max(ordered), 3),
        "cache_hit_p50_ms": round(statistics.median(cached_ordered), 3),
        "cache_hit_p95_ms": round(cached_ordered[int(0.95 * (len(cached_ordered) - 1))], 3),
    })


async def measure_cache(request) -> list[float]:
    cache = AnalysisCache()

    async def compute():
        return analyze(request, NOW)

    await cache.get_or_compute(request, compute)
    samples = []
    for _ in range(100):
        started = time.perf_counter()
        await cache.get_or_compute(request, compute)
        samples.append((time.perf_counter() - started) * 1000)
    return samples


if __name__ == "__main__":
    main()
