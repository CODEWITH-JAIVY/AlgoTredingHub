from __future__ import annotations

import hashlib
from datetime import datetime

import numpy as np
import pandas as pd

from .indicators import trend
from .models import Candle, HistoricalReactionStats, Timeframe, TimeframeEvidence, Zone


CONFIDENCE_METHOD = "v1 weighted score: freshness 25%, impulse strength 25%, impulse volume 15%, trend agreement 20%, higher-timeframe alignment 15%; not a probability."
REACTION_METHOD = "For each completed post-detection zone re-entry, count a favorable close 0.5x detection ATR beyond the near edge before a close beyond the far edge, within 20 bars."


def _zone_is_touched(row: pd.Series, low: float, high: float) -> bool:
    return float(row["high"]) >= low and float(row["low"]) <= high


def detect_zones(
    candles: list[Candle],
    timeframe: Timeframe,
    data: pd.DataFrame,
    atr: pd.Series,
    current_trend: str,
    higher_timeframe_trends: list[str],
    current_price: float,
) -> list[Zone]:
    if len(candles) < 18:
        return []

    volume_average = data["volume"].rolling(20, min_periods=20).mean()
    candidates: list[tuple[Zone, float]] = []
    for impulse_index in range(17, len(candles)):
        previous_atr = atr.iloc[impulse_index - 1]
        if pd.isna(previous_atr) or previous_atr <= 0:
            continue
        impulse = data.iloc[impulse_index]
        body = abs(float(impulse["close"] - impulse["open"]))
        strength = body / float(previous_atr)
        if strength <= 1.5 or impulse["close"] == impulse["open"]:
            continue

        base_indices: list[int] = []
        for base_index in range(impulse_index - 1, max(-1, impulse_index - 4), -1):
            base_atr = atr.iloc[base_index]
            if pd.isna(base_atr) or base_atr <= 0:
                break
            if float(data.iloc[base_index]["high"] - data.iloc[base_index]["low"]) > 0.75 * float(base_atr):
                break
            base_indices.append(base_index)
        if not base_indices:
            continue
        base = data.iloc[base_indices]
        zone_low = float(base["low"].min())
        zone_high = float(base["high"].max())
        if zone_high <= zone_low:
            continue

        side = "DEMAND" if impulse["close"] > impulse["open"] else "SUPPLY"
        invalidated = False
        touches = 0
        was_inside = False
        for later_index in range(impulse_index + 1, len(candles)):
            row = data.iloc[later_index]
            inside = _zone_is_touched(row, zone_low, zone_high)
            if inside and not was_inside:
                touches += 1
            if side == "DEMAND" and float(row["close"]) < zone_low:
                invalidated = True
                break
            if side == "SUPPLY" and float(row["close"]) > zone_high:
                invalidated = True
                break
            was_inside = inside

        impulse_volume_mean = volume_average.iloc[impulse_index]
        impulse_volume_ratio: float | None = None
        if not pd.isna(impulse_volume_mean) and impulse_volume_mean > 0:
            impulse_volume_ratio = round(float(impulse["volume"] / impulse_volume_mean), 6)

        direction = "BULLISH" if side == "DEMAND" else "BEARISH"
        trend_score = 1.0 if current_trend == direction else (0.5 if current_trend in {"NEUTRAL", "INSUFFICIENT_HISTORY"} else 0.0)
        available_higher = [value for value in higher_timeframe_trends if value != "INSUFFICIENT_HISTORY"]
        if not available_higher or all(value == "NEUTRAL" for value in available_higher):
            higher_score = 0.5
        else:
            agreeing = sum(value == direction for value in available_higher)
            higher_score = agreeing / len(available_higher)
        freshness_score = max(0.0, 1.0 - min(touches, 4) / 4) if touches else 1.0
        impulse_score = min(1.0, strength / 3.0)
        volume_score = 0.5 if impulse_volume_ratio is None else min(1.0, impulse_volume_ratio / 2.0)
        score = (0.25 * freshness_score + 0.25 * impulse_score + 0.15 * volume_score
                 + 0.20 * trend_score + 0.15 * higher_score)
        stamp = candles[impulse_index].timestamp
        digest = hashlib.sha256(f"{timeframe.value}|{stamp.isoformat()}|{side}|{zone_low:.8f}|{zone_high:.8f}".encode()).hexdigest()[:16]
        zone = Zone(
            zone_id=f"{timeframe.value}-{digest}", side=side, source_timeframe=timeframe,
            low=round(zone_low, 6), high=round(zone_high, 6), detected_at=stamp,
            touch_count=touches, fresh=touches == 0, invalidated=invalidated,
            invalidation_rule=("A finalized candle close below the zone low invalidates demand." if side == "DEMAND"
                               else "A finalized candle close above the zone high invalidates supply."),
            impulse_strength_atr=round(strength, 6), impulse_volume_ratio=impulse_volume_ratio,
            confidence_score=round(score, 6), confidence_method=CONFIDENCE_METHOD,
        )
        if not invalidated:
            distance = abs((zone_low + zone_high) / 2 - current_price)
            candidates.append((zone, distance))

    selected: list[Zone] = []
    for side in ("DEMAND", "SUPPLY"):
        nearest = sorted((item for item in candidates if item[0].side == side), key=lambda item: item[1])[:3]
        selected.extend(zone for zone, _ in sorted(nearest, key=lambda item: (-item[0].confidence_score, item[1])))
    return selected


def historical_reactions(candles: list[Candle], data: pd.DataFrame, atr: pd.Series,
                         timeframe: Timeframe) -> HistoricalReactionStats:
    opportunities = 0
    favorable = 0
    for impulse_index in range(17, len(candles) - 2):
        detection_atr = atr.iloc[impulse_index - 1]
        if pd.isna(detection_atr) or detection_atr <= 0:
            continue
        impulse = data.iloc[impulse_index]
        if abs(float(impulse["close"] - impulse["open"])) <= 1.5 * float(detection_atr):
            continue
        side = "DEMAND" if impulse["close"] > impulse["open"] else "SUPPLY"
        base_rows = []
        for base_index in range(impulse_index - 1, max(-1, impulse_index - 4), -1):
            base_atr = atr.iloc[base_index]
            if pd.isna(base_atr) or base_atr <= 0:
                break
            row = data.iloc[base_index]
            if float(row["high"] - row["low"]) > 0.75 * float(base_atr):
                break
            base_rows.append(row)
        if not base_rows:
            continue
        zone_low = min(float(row["low"]) for row in base_rows)
        zone_high = max(float(row["high"]) for row in base_rows)
        if zone_high <= zone_low:
            continue

        was_inside = False
        for touch_index in range(impulse_index + 1, len(candles) - 1):
            row = data.iloc[touch_index]
            inside = _zone_is_touched(row, zone_low, zone_high)
            if inside and not was_inside:
                invalid_on_touch = (float(row["close"]) < zone_low if side == "DEMAND"
                                    else float(row["close"]) > zone_high)
                if invalid_on_touch:
                    opportunities += 1
                    break
                outcome: bool | None = None
                favorable_edge = (zone_high + 0.5 * float(detection_atr) if side == "DEMAND"
                                  else zone_low - 0.5 * float(detection_atr))
                end = min(len(candles), touch_index + 21)
                for outcome_index in range(touch_index + 1, end):
                    close = float(data.iloc[outcome_index]["close"])
                    invalid = close < zone_low if side == "DEMAND" else close > zone_high
                    won = close >= favorable_edge if side == "DEMAND" else close <= favorable_edge
                    if invalid or won:
                        outcome = won and not invalid
                        break
                if outcome is not None:
                    opportunities += 1
                    favorable += int(outcome)
            if (side == "DEMAND" and float(row["close"]) < zone_low) or (side == "SUPPLY" and float(row["close"]) > zone_high):
                break
            was_inside = inside

    fraction = round(favorable / opportunities, 6) if opportunities >= 5 else None
    limitations = ["Historical reactions are descriptive, not predictive.",
                   "The sample can be affected by survivorship, source quality, session selection, and market regime.",
                   f"Only completed {timeframe.value} candles in this request are counted."]
    if opportunities < 5:
        limitations.append("Fewer than five completed reaction outcomes; a favorable fraction is withheld.")
    return HistoricalReactionStats(sample_size=opportunities, favorable_reactions=favorable,
                                   favorable_fraction=fraction, methodology=REACTION_METHOD,
                                   limitations=limitations)
