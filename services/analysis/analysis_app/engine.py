from __future__ import annotations

import hashlib
import json
import math
import time
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_CEILING, ROUND_FLOOR, ROUND_HALF_UP

import pandas as pd

from .indicators import calculate, trend
from .models import (
    AnalysisRequest, AnalysisResponse, DataFreshness, HistoricalReactionStats,
    IndicatorValues, PlainLanguageSummary, Scenario, Timeframe, TimeframeEvidence, Zone,
)
from .zones import detect_zones, historical_reactions


ALGORITHM_VERSION = "analysis-v1.0.0"
STALE_MULTIPLIER = 3
MIN_STALE_WINDOW = timedelta(minutes=15)


def request_data_version(request: AnalysisRequest) -> str:
    encoded = json.dumps(request.model_dump(mode="json"), sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def _age_is_stale(timestamp: datetime, timeframe: Timeframe, now: datetime) -> bool:
    if timestamp > now + timedelta(seconds=5):
        return True
    return now - timestamp > max(timedelta(seconds=timeframe.seconds * STALE_MULTIPLIER), MIN_STALE_WINDOW)


def _round_tick(value: float, tick_size: Decimal, rounding: str) -> float:
    modes = {"down": ROUND_FLOOR, "up": ROUND_CEILING, "nearest": ROUND_HALF_UP}
    units = (Decimal(str(value)) / tick_size).quantize(Decimal("1"), rounding=modes[rounding])
    return float(units * tick_size)


def _scenario(request: AnalysisRequest, bias: str, zones: list[Zone], indicators: IndicatorValues,
              last_close: float) -> Scenario | None:
    if bias not in {"BULLISH", "BEARISH"} or indicators.atr14 is None or indicators.atr14 <= 0:
        return None
    matching_side = "DEMAND" if bias == "BULLISH" else "SUPPLY"
    matching = [zone for zone in zones if zone.side == matching_side and not zone.invalidated]
    if not matching:
        return None
    zone = min(matching, key=lambda item: abs((item.low + item.high) / 2 - last_close))
    if abs((zone.low + zone.high) / 2 - last_close) > 2.5 * indicators.atr14:
        return None

    entry = last_close
    risk_distance = max(0.25 * indicators.atr14, float(request.tick_size))
    if bias == "BULLISH":
        stop = _round_tick(min(zone.low - 0.25 * indicators.atr14, entry - risk_distance), request.tick_size, "down")
        risk = entry - stop
        target = _round_tick(entry + 2 * risk, request.tick_size, "up")
        side = "BUY"
    else:
        stop = _round_tick(max(zone.high + 0.25 * indicators.atr14, entry + risk_distance), request.tick_size, "up")
        risk = stop - entry
        target = _round_tick(entry - 2 * risk, request.tick_size, "down")
        side = "SELL"
    if risk <= 0 or target <= 0:
        return None
    realized_ratio = abs(target - entry) / risk
    return Scenario(
        side=side, entry_reference=entry, stop_reference=stop, target_reference=target,
        risk_reward_ratio=round(realized_ratio, 6),
        assumptions=[
            "Illustrative levels use the last finalized candle close and the nearest valid same-direction zone.",
            "Stop buffer is 0.25 ATR; target is rounded outward to a 2:1 pre-cost risk/reward reference.",
            "Tick size is from the supplied instrument-master version; fees, spread, slippage, gaps, and fills are excluded.",
            "This scenario is informational and is not an order instruction or guaranteed outcome.",
        ],
        calculation_version="scenario-v1.0.0",
    )


def _summary(freshness: DataFreshness, bias: str, actionable: bool, zones: list[Zone], warnings: list[str]) -> PlainLanguageSummary:
    if freshness == DataFreshness.SIMULATED or "SYNTHETIC_DATA_NOT_FOR_MARKET_DECISIONS" in warnings:
        return PlainLanguageSummary(
            english=["These candles are simulated, so no market signal is shown.",
                     "The values describe a development fixture and are not live market data."],
            hindi=["Yeh candles simulated hain, isliye koi market signal nahi dikhaya ja raha hai.",
                   "Yeh sirf development sample hai, live market data nahi."],
        )
    if not actionable:
        return PlainLanguageSummary(
            english=["A market bias is withheld because the data or timeframe evidence is incomplete, stale, or conflicting.",
                     "Indicators and warnings are shown for context only."],
            hindi=["Data ya timeframe evidence adhura, purana, ya alag-alag hai; isliye bias nahi diya gaya hai.",
                   "Indicators aur warnings sirf jaankari ke liye hain."],
        )
    direction = "upward" if bias == "BULLISH" else "downward"
    direction_hi = "upar" if bias == "BULLISH" else "neeche"
    same_side = "DEMAND" if bias == "BULLISH" else "SUPPLY"
    nearest = next((zone for zone in zones if zone.side == same_side), None)
    if nearest:
        state = "fresh" if nearest.fresh else f"touched {nearest.touch_count} times"
        en_zone = f"Price is near a {state} {same_side.lower()} zone."
        hi_zone = f"Price {state} {same_side.lower()} zone ke paas hai."
    else:
        en_zone = "No nearby valid zone was found on this timeframe."
        hi_zone = "Is timeframe par paas mein koi valid zone nahi mila."
    return PlainLanguageSummary(
        english=[f"The available timeframe readings show an {direction} bias; this is descriptive, not predictive.", en_zone],
        hindi=[f"Available timeframes mein trend {direction_hi} ki taraf dikh raha hai; yeh pakki bhavishyavani nahi hai.", hi_zone],
    )


def analyze(request: AnalysisRequest, now: datetime | None = None) -> AnalysisResponse:
    started = time.perf_counter()
    evaluated_at = now or datetime.now(timezone.utc)
    if evaluated_at.tzinfo is None or evaluated_at.utcoffset() != timedelta(0):
        raise ValueError("Analysis clock must be timezone-aware UTC.")

    requested = request.timeframes[request.requested_timeframe]
    indicators, data, atr = calculate(requested.candles)
    last_timestamp = requested.candles[-1].timestamp
    last_close = float(requested.candles[-1].close)
    warnings: list[str] = []

    evidence: list[TimeframeEvidence] = []
    trends: dict[Timeframe, str] = {}
    for timeframe in Timeframe:
        series = request.timeframes.get(timeframe)
        if series is None:
            trends[timeframe] = "INSUFFICIENT_HISTORY"
            evidence.append(TimeframeEvidence(
                timeframe=timeframe, trend="INSUFFICIENT_HISTORY", data_timestamp=None,
                source_id=None, freshness=None, complete=False,
                reasons=["No candle series was supplied for this timeframe."],
            ))
            continue
        values = indicators if timeframe == request.requested_timeframe else calculate(series.candles)[0]
        trend_value = trend(values.ema20, values.ema50, values.ema200)
        trends[timeframe] = trend_value
        reasons = []
        if trend_value == "INSUFFICIENT_HISTORY":
            reasons.append("At least 50 finalized candles are needed for a 20/50 EMA trend reading.")
        elif trend_value == "NEUTRAL":
            reasons.append("EMA20, EMA50, and EMA200 are not directionally stacked.")
        if series.freshness != DataFreshness.FRESH:
            reasons.append(f"Data freshness is {series.freshness.value.lower()}.")
        if not series.complete or series.gaps:
            reasons.append("The supplied series is incomplete or contains recorded gaps.")
        evidence.append(TimeframeEvidence(
            timeframe=timeframe, trend=trend_value, data_timestamp=series.candles[-1].timestamp,
            source_id=series.source_id, freshness=series.freshness,
            complete=series.complete and not series.gaps, reasons=reasons,
        ))
        if _age_is_stale(series.candles[-1].timestamp, timeframe, evaluated_at):
            warnings.append("STALE_DATA")
        if series.candles[-1].timestamp > evaluated_at + timedelta(seconds=5):
            warnings.append("CANDLE_TIMESTAMP_IN_FUTURE")
        if series.freshness == DataFreshness.STALE or series.freshness == DataFreshness.UNAVAILABLE:
            warnings.append("STALE_OR_UNAVAILABLE_SOURCE")
        if series.source_id == "SYNTHETIC_DEMO" or series.freshness == DataFreshness.SIMULATED:
            warnings.append("SYNTHETIC_DATA_NOT_FOR_MARKET_DECISIONS")
        if not series.complete:
            warnings.append("INCOMPLETE_CANDLE_SERIES")
        if series.gaps:
            warnings.append("CANDLE_GAPS_DETECTED")
        if series.corporate_action_warning:
            warnings.append("CORPORATE_ACTIONS_UNVERIFIED")

    if indicators.ema200 is None:
        warnings.append("INSUFFICIENT_HISTORY_EMA200")
    if indicators.adx14 is None:
        warnings.append("INSUFFICIENT_HISTORY_ADX")
    if indicators.atr14 is None:
        warnings.append("INSUFFICIENT_HISTORY_ATR")
    if indicators.rsi14 is None or indicators.macd_signal is None:
        warnings.append("INSUFFICIENT_HISTORY_MOMENTUM")
    if indicators.volume_average20 is None or indicators.volume_average20 == 0:
        warnings.append("VOLUME_CONTEXT_UNAVAILABLE")
    elif indicators.latest_volume_ratio is not None and indicators.latest_volume_ratio >= 5:
        warnings.append("ABNORMAL_VOLUME_SPIKE")
    elif indicators.latest_volume_ratio is not None and 0 <= indicators.latest_volume_ratio < 0.1:
        warnings.append("LOW_RELATIVE_VOLUME")
    if requested.candles[-1].timestamp > evaluated_at + timedelta(seconds=5):
        warnings.append("CANDLE_TIMESTAMP_IN_FUTURE")
    if len(data) >= 20:
        previous_close = data["close"].shift(1)
        true_ranges = pd.concat([
            data["high"] - data["low"],
            (data["high"] - previous_close).abs(),
            (data["low"] - previous_close).abs(),
        ], axis=1).max(axis=1, skipna=True)
        baseline = float(true_ranges.iloc[-20:-1].median())
        if baseline > 0 and float(true_ranges.iloc[-1]) > 8 * baseline:
            warnings.append("POSSIBLE_PRICE_OUTLIER")

    higher_trends = [trends[timeframe] for timeframe in Timeframe if timeframe != request.requested_timeframe]
    zones = detect_zones(requested.candles, request.requested_timeframe, data, atr,
                         trends[request.requested_timeframe], higher_trends, last_close)
    reaction_stats = historical_reactions(requested.candles, data, atr, request.requested_timeframe)

    all_timeframes = len(request.timeframes) == len(Timeframe)
    enough_trends = sum(value != "INSUFFICIENT_HISTORY" for value in trends.values()) == len(Timeframe)
    quality_blocks = {
        "STALE_DATA", "STALE_OR_UNAVAILABLE_SOURCE", "SYNTHETIC_DATA_NOT_FOR_MARKET_DECISIONS",
        "INCOMPLETE_CANDLE_SERIES", "CANDLE_GAPS_DETECTED", "CORPORATE_ACTIONS_UNVERIFIED",
        "POSSIBLE_PRICE_OUTLIER", "CANDLE_TIMESTAMP_IN_FUTURE", "VOLUME_CONTEXT_UNAVAILABLE",
        "LOW_RELATIVE_VOLUME",
    }
    eligible_data = (all_timeframes and enough_trends and indicators.ema200 is not None
                     and indicators.atr14 is not None and indicators.adx14 is not None
                     and indicators.rsi14 is not None and indicators.macd_signal is not None
                     and not quality_blocks.intersection(warnings)
                     and all(series.freshness == DataFreshness.FRESH and series.complete and not series.gaps
                             and series.derived_analytics_allowed and series.display_allowed
                             for series in request.timeframes.values()))
    bull = sum(value == "BULLISH" for value in trends.values())
    bear = sum(value == "BEARISH" for value in trends.values())
    if eligible_data and bull >= 3 and bear == 0:
        bias = "BULLISH"
        bias_reasons = [f"{bull} of 4 timeframe EMA readings are bullish and none are bearish."]
    elif eligible_data and bear >= 3 and bull == 0:
        bias = "BEARISH"
        bias_reasons = [f"{bear} of 4 timeframe EMA readings are bearish and none are bullish."]
    else:
        bias = "NEUTRAL"
        if bull and bear:
            warnings.append("TIMEFRAME_CONFLICT")
            bias_reasons = [f"Timeframe readings conflict ({bull} bullish, {bear} bearish); bias is withheld."]
        elif not enough_trends:
            warnings.append("INSUFFICIENT_MULTI_TIMEFRAME_HISTORY")
            bias_reasons = ["At least three timeframe trend readings are needed before a bias can be shown."]
        elif not eligible_data:
            bias_reasons = ["Data quality, rights, freshness, or completeness requirements are not met."]
        else:
            bias_reasons = ["The timeframe readings do not show a three-timeframe directional agreement."]

    actionable = bias in {"BULLISH", "BEARISH"} and eligible_data
    if not actionable:
        warnings.append("SIGNAL_SUPPRESSED")
    scenario = _scenario(request, bias, zones, indicators, last_close) if actionable else None
    if actionable and scenario is None:
        warnings.append("NO_VALID_SCENARIO")
    freshness = requested.freshness
    if _age_is_stale(last_timestamp, request.requested_timeframe, evaluated_at):
        freshness = DataFreshness.STALE
    if requested.freshness == DataFreshness.FRESH and freshness == DataFreshness.STALE:
        warnings.append("STALE_DATA")
    stable_warnings = list(dict.fromkeys(warnings))
    optional_metrics = {
        "open_interest_change": "UNAVAILABLE_LICENSED_SOURCE_NOT_CONFIGURED",
        "put_call_ratio": "UNAVAILABLE_LICENSED_SOURCE_NOT_CONFIGURED",
        "delivery_percentage": "UNAVAILABLE_LICENSED_SOURCE_NOT_CONFIGURED",
        "depth_imbalance": "UNAVAILABLE_LICENSED_SOURCE_NOT_CONFIGURED",
        "sector_context": "UNAVAILABLE_LICENSED_SOURCE_NOT_CONFIGURED",
        "index_context": "UNAVAILABLE_LICENSED_SOURCE_NOT_CONFIGURED",
    }
    elapsed_ms = round((time.perf_counter() - started) * 1000, 3)
    return AnalysisResponse(
        algorithm_version=ALGORITHM_VERSION, config_version=request.config_version,
        instrument_id=request.instrument_id, requested_timeframe=request.requested_timeframe,
        data_timestamp=last_timestamp, source_id=requested.source_id,
        master_version=requested.master_version, freshness=freshness,
        complete=requested.complete and not requested.gaps,
        signal_status="AVAILABLE" if actionable else "SUPPRESSED", actionable=actionable,
        bias=bias, bias_reasons=bias_reasons, indicators=indicators,
        timeframe_evidence=evidence, zones=zones, historical_reactions=reaction_stats,
        scenario=scenario, optional_metrics=optional_metrics, warnings=stable_warnings,
        summary=_summary(freshness, bias, actionable, zones, stable_warnings),
        data_version=request_data_version(request), computed_at=evaluated_at,
        computation_ms=elapsed_ms,
    )


def mark_cached_response_stale(response: AnalysisResponse, now: datetime) -> AnalysisResponse:
    if not _age_is_stale(response.data_timestamp, response.requested_timeframe, now):
        return response
    warnings = list(dict.fromkeys([*response.warnings, "STALE_DATA", "SIGNAL_SUPPRESSED"]))
    return response.model_copy(update={
        "freshness": DataFreshness.STALE,
        "actionable": False,
        "signal_status": "SUPPRESSED",
        "bias": "NEUTRAL",
        "bias_reasons": ["Cached analysis became stale; a fresh candle update is required."],
        "scenario": None,
        "warnings": warnings,
        "summary": PlainLanguageSummary(
            english=["The cached candles are stale, so no market signal is shown.",
                     "Wait for a fresh, complete update before reviewing the analysis."],
            hindi=["Cached candles purane hain, isliye koi market signal nahi dikhaya ja raha hai.",
                   "Analysis dekhne se pehle fresh aur complete update zaroori hai."],
        ),
    })


def mark_cached_response_superseded(response: AnalysisResponse) -> AnalysisResponse:
    warnings = list(dict.fromkeys([*response.warnings, "SUPERSEDED_DATA_VERSION", "SIGNAL_SUPPRESSED"]))
    return response.model_copy(update={
        "actionable": False,
        "signal_status": "SUPPRESSED",
        "bias": "NEUTRAL",
        "bias_reasons": ["A newer candle series is already available for this instrument and timeframe."],
        "scenario": None,
        "warnings": warnings,
        "summary": PlainLanguageSummary(
            english=["A newer candle series is available, so this older analysis is not shown as current.",
                     "Use the latest cached analysis for the current data version."],
            hindi=["Isse nayi candle series aa chuki hai, isliye yeh purana analysis current nahi dikhaya ja raha.",
                   "Current data version ke liye latest cached analysis dekhein."],
        ),
    })
