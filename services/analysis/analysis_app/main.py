from __future__ import annotations

import asyncio
import hashlib
import hmac
import os
import time
from collections import defaultdict, deque
from datetime import datetime, timezone
from typing import Annotated
from uuid import UUID, uuid4

from fastapi import Depends, FastAPI, HTTPException, Request, Security, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, Field

from .cache import AnalysisCache, LatencyMetrics
from .engine import ALGORITHM_VERSION, analyze, mark_cached_response_stale, mark_cached_response_superseded
from .models import AnalysisRequest, AnalysisResponse, Timeframe


MAX_BODY_BYTES = 4 * 1024 * 1024
_bearer = HTTPBearer(auto_error=False)
cache = AnalysisCache(ttl_seconds=90.0, max_entries=1000)
metrics = LatencyMetrics(max_samples=1000)
compute_semaphore = asyncio.Semaphore(4)


class InternalRateLimiter:
    def __init__(self, limit: int = 120, window_seconds: int = 60) -> None:
        self.limit = limit
        self.window_seconds = window_seconds
        self._events: dict[str, deque[float]] = defaultdict(deque)
        self._lock = asyncio.Lock()

    async def allow(self, credential: str) -> bool:
        now = time.monotonic()
        fingerprint = hashlib.sha256(credential.encode("utf-8")).hexdigest()
        async with self._lock:
            events = self._events[fingerprint]
            while events and events[0] <= now - self.window_seconds:
                events.popleft()
            if len(events) >= self.limit:
                return False
            events.append(now)
            if len(self._events) > 128:
                for key in list(self._events):
                    if not self._events[key]:
                        self._events.pop(key, None)
            return True


rate_limiter = InternalRateLimiter()

app = FastAPI(title="AlgoTrade Hub Analysis", version=ALGORITHM_VERSION,
              docs_url=None, redoc_url=None, openapi_url=None)


async def require_internal_auth(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Security(_bearer)],
) -> None:
    expected = os.getenv("ANALYSIS_INTERNAL_TOKEN", "")
    if len(expected) < 32:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                            detail={"code": "ANALYSIS_AUTH_NOT_CONFIGURED"})
    provided = "" if credentials is None else credentials.credentials
    if not hmac.compare_digest(expected, provided):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED,
                            detail={"code": "INVALID_INTERNAL_CREDENTIALS"},
                            headers={"WWW-Authenticate": "Bearer"})
    if not await rate_limiter.allow(provided):
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                            detail={"code": "ANALYSIS_RATE_LIMITED"})


@app.middleware("http")
async def enforce_content_length(request: Request, call_next):
    try:
        correlation_id = str(UUID(request.headers.get("x-correlation-id", "")))
    except ValueError:
        correlation_id = str(uuid4())
    content_length = request.headers.get("content-length")
    if request.method in {"POST", "PUT", "PATCH"} and content_length is None:
        response = JSONResponse(status_code=411, content={"code": "CONTENT_LENGTH_REQUIRED"})
        response.headers["X-Correlation-ID"] = correlation_id
        return response
    if content_length is not None:
        try:
            declared_size = int(content_length)
            if declared_size < 0:
                response = JSONResponse(status_code=400, content={"code": "INVALID_CONTENT_LENGTH"})
                response.headers["X-Correlation-ID"] = correlation_id
                return response
            if declared_size > MAX_BODY_BYTES:
                response = JSONResponse(status_code=413, content={"code": "REQUEST_TOO_LARGE"})
                response.headers["X-Correlation-ID"] = correlation_id
                return response
        except ValueError:
            response = JSONResponse(status_code=400, content={"code": "INVALID_CONTENT_LENGTH"})
            response.headers["X-Correlation-ID"] = correlation_id
            return response
    response = await call_next(request)
    response.headers["X-Correlation-ID"] = correlation_id
    return response


@app.exception_handler(RequestValidationError)
async def safe_validation_error(_request: Request, _error: RequestValidationError) -> JSONResponse:
    return JSONResponse(status_code=422, content={"detail": {"code": "INVALID_ANALYSIS_REQUEST"}})


@app.get("/health/live")
async def liveness() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/health/ready")
async def readiness() -> dict[str, str]:
    if len(os.getenv("ANALYSIS_INTERNAL_TOKEN", "")) < 32:
        raise HTTPException(status_code=503, detail={"code": "ANALYSIS_AUTH_NOT_CONFIGURED"})
    return {"status": "READY", "algorithm_version": ALGORITHM_VERSION}


async def _compute(request: AnalysisRequest) -> AnalysisResponse:
    async with compute_semaphore:
        result = await asyncio.to_thread(analyze, request)
        metrics.record(result.computation_ms)
        return result


async def _cached_analysis(payload: AnalysisRequest) -> AnalysisResponse:
    result, _was_cached = await cache.get_or_compute(payload, lambda: _compute(payload))
    latest = await cache.latest(payload.instrument_id, payload.requested_timeframe)
    if latest is not None and latest.data_timestamp > result.data_timestamp:
        return mark_cached_response_superseded(result)
    return result


@app.post("/internal/v1/analyze", response_model=AnalysisResponse)
async def analyze_and_cache(
    payload: AnalysisRequest,
    _: Annotated[None, Depends(require_internal_auth)],
) -> AnalysisResponse:
    return await _cached_analysis(payload)


class PrecomputeBatch(BaseModel):
    requests: list[AnalysisRequest] = Field(min_length=1, max_length=100)


@app.post("/internal/v1/precompute")
async def precompute(
    payload: PrecomputeBatch,
    _: Annotated[None, Depends(require_internal_auth)],
) -> dict[str, object]:
    results = await asyncio.gather(*[_cached_analysis(item) for item in payload.requests])
    return {"processed": len(results), "results": results}


@app.get("/analyze/{instrument_id}", response_model=AnalysisResponse)
async def get_analysis(
    instrument_id: UUID,
    timeframe: Timeframe = Timeframe.M15,
    _: Annotated[None, Depends(require_internal_auth)] = None,
) -> AnalysisResponse:
    result = await cache.latest(instrument_id, timeframe)
    if result is None:
        raise HTTPException(status_code=404, detail={"code": "ANALYSIS_NOT_PRECOMPUTED"})
    return mark_cached_response_stale(result, datetime.now(timezone.utc))


@app.get("/internal/v1/metrics")
async def analysis_metrics(
    _: Annotated[None, Depends(require_internal_auth)],
) -> dict[str, object]:
    return {"algorithm_version": ALGORITHM_VERSION, "latency": metrics.snapshot(),
            "cache_entries": len(cache._entries), "cache_policy": "90-second TTL; max 1000 entries; process-local"}
