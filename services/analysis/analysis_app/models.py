from __future__ import annotations

from datetime import datetime, timedelta, time as local_time
from decimal import Decimal
from enum import Enum
from typing import Literal
from uuid import UUID
from zoneinfo import ZoneInfo

from pydantic import BaseModel, ConfigDict, Field, model_validator


class Timeframe(str, Enum):
    M5 = "5m"
    M15 = "15m"
    H1 = "1h"
    D1 = "1d"

    @property
    def seconds(self) -> int:
        return {self.M5: 300, self.M15: 900, self.H1: 3600, self.D1: 86400}[self]


class DataFreshness(str, Enum):
    FRESH = "FRESH"
    STALE = "STALE"
    SIMULATED = "SIMULATED"
    UNAVAILABLE = "UNAVAILABLE"


class Candle(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    timestamp: datetime
    open: Decimal
    high: Decimal
    low: Decimal
    close: Decimal
    volume: int = Field(ge=0)
    finalized: bool

    @model_validator(mode="after")
    def valid_ohlc(self) -> Candle:
        if self.timestamp.tzinfo is None or self.timestamp.utcoffset() != timedelta(0):
            raise ValueError("Candle timestamp must include UTC timezone information.")
        prices = (self.open, self.high, self.low, self.close)
        if any(not price.is_finite() or price <= 0 for price in prices):
            raise ValueError("OHLC prices must be finite and positive.")
        if self.high < max(self.open, self.close) or self.low > min(self.open, self.close) or self.high < self.low:
            raise ValueError("Candle OHLC range is inconsistent.")
        return self


class GapInterval(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    start: datetime
    end: datetime

    @model_validator(mode="after")
    def valid_interval(self) -> GapInterval:
        if (self.start.tzinfo is None or self.end.tzinfo is None
                or self.start.utcoffset() != timedelta(0) or self.end.utcoffset() != timedelta(0)
                or self.end <= self.start):
            raise ValueError("Gap interval must be positive and expressed in UTC.")
        return self


class CandleSeries(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    source_id: str = Field(min_length=1, max_length=50)
    master_version: str = Field(min_length=1, max_length=64)
    freshness: DataFreshness
    complete: bool
    storage_allowed: bool = False
    cache_allowed: bool = False
    display_allowed: bool = False
    derived_analytics_allowed: bool = False
    corporate_action_warning: bool = False
    gaps: list[GapInterval] = Field(default_factory=list, max_length=1000)
    candles: list[Candle] = Field(min_length=1, max_length=5000)

    @model_validator(mode="after")
    def chronological_unique_candles(self) -> CandleSeries:
        previous: datetime | None = None
        for candle in self.candles:
            if not candle.finalized:
                raise ValueError("Analysis accepts finalized candles only.")
            if previous is not None and candle.timestamp <= previous:
                raise ValueError("Candles must be strictly chronological and unique.")
            previous = candle.timestamp
        previous_gap_end: datetime | None = None
        for gap in self.gaps:
            if previous_gap_end is not None and gap.start < previous_gap_end:
                raise ValueError("Gap intervals must be chronological and non-overlapping.")
            previous_gap_end = gap.end
        return self


class AnalysisRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    instrument_id: UUID
    tick_size: Decimal = Field(gt=0, max_digits=12, decimal_places=6)
    requested_timeframe: Timeframe
    timeframes: dict[Timeframe, CandleSeries] = Field(min_length=1, max_length=4)
    config_version: str = Field(default="default-v1", min_length=1, max_length=32)

    @model_validator(mode="after")
    def requested_series_present(self) -> AnalysisRequest:
        if self.requested_timeframe not in self.timeframes:
            raise ValueError("Requested timeframe data is required.")
        versions = {series.master_version for series in self.timeframes.values()}
        if len(versions) != 1:
            raise ValueError("All timeframe series must use the same instrument-master version.")
        for timeframe, series in self.timeframes.items():
            if not (series.storage_allowed and series.cache_allowed and series.display_allowed
                    and series.derived_analytics_allowed):
                if series.source_id != "SYNTHETIC_DEMO":
                    raise ValueError("Analysis requires confirmed storage, cache, display, and derived-analytics rights.")
            for candle in series.candles:
                local = candle.timestamp.astimezone(ZoneInfo("Asia/Kolkata"))
                if local.second != 0 or local.microsecond != 0:
                    raise ValueError("Candle timestamps must align to whole exchange minutes.")
                if timeframe == Timeframe.D1:
                    if local.time() != local_time(9, 15):
                        raise ValueError("Daily candles must start at 09:15 Asia/Kolkata for the supported regular session.")
                else:
                    minute_of_day = local.hour * 60 + local.minute
                    offset = minute_of_day - (9 * 60 + 15)
                    interval_minutes = timeframe.seconds // 60
                    if offset < 0 or offset > 375 - interval_minutes or offset % interval_minutes != 0:
                        raise ValueError("Candle timestamp is not aligned to the supported Asia/Kolkata session bucket.")
                if any(price % self.tick_size != 0 for price in (candle.open, candle.high, candle.low, candle.close)):
                    raise ValueError("Candle OHLC values must align to the active instrument tick size.")
        return self


class IndicatorValues(BaseModel):
    ema20: float | None = None
    ema50: float | None = None
    ema200: float | None = None
    adx14: float | None = None
    rsi14: float | None = None
    macd: float | None = None
    macd_signal: float | None = None
    macd_histogram: float | None = None
    atr14: float | None = None
    volume_average20: float | None = None
    latest_volume_ratio: float | None = None


class Zone(BaseModel):
    zone_id: str
    side: Literal["DEMAND", "SUPPLY"]
    source_timeframe: Timeframe
    low: float
    high: float
    detected_at: datetime
    touch_count: int
    fresh: bool
    invalidated: bool
    invalidation_rule: str
    impulse_strength_atr: float
    impulse_volume_ratio: float | None
    confidence_score: float
    confidence_method: str


class TimeframeEvidence(BaseModel):
    timeframe: Timeframe
    trend: Literal["BULLISH", "NEUTRAL", "BEARISH", "INSUFFICIENT_HISTORY"]
    data_timestamp: datetime | None
    source_id: str | None
    freshness: DataFreshness | None
    complete: bool
    reasons: list[str]


class HistoricalReactionStats(BaseModel):
    sample_size: int
    favorable_reactions: int
    favorable_fraction: float | None
    methodology: str
    limitations: list[str]


class Scenario(BaseModel):
    side: Literal["BUY", "SELL"]
    entry_reference: float
    stop_reference: float
    target_reference: float
    risk_reward_ratio: float
    assumptions: list[str]
    calculation_version: str


class PlainLanguageSummary(BaseModel):
    english: list[str]
    hindi: list[str]


class AnalysisResponse(BaseModel):
    schema_version: str = "1.0"
    algorithm_version: str
    config_version: str
    instrument_id: UUID
    requested_timeframe: Timeframe
    data_timestamp: datetime
    source_id: str
    master_version: str
    freshness: DataFreshness
    complete: bool
    signal_status: Literal["AVAILABLE", "SUPPRESSED"]
    actionable: bool
    bias: Literal["BULLISH", "NEUTRAL", "BEARISH"]
    bias_reasons: list[str]
    indicators: IndicatorValues
    timeframe_evidence: list[TimeframeEvidence]
    zones: list[Zone]
    historical_reactions: HistoricalReactionStats
    scenario: Scenario | None
    optional_metrics: dict[str, str]
    warnings: list[str]
    summary: PlainLanguageSummary
    data_version: str
    computed_at: datetime
    computation_ms: float
