from __future__ import annotations

import math
from decimal import Decimal
from typing import Any

import numpy as np
import pandas as pd

from .models import Candle, IndicatorValues


def _number(value: Any) -> float | None:
    if value is None or pd.isna(value):
        return None
    number = float(value)
    return round(number, 6) if math.isfinite(number) else None


def frame(candles: list[Candle]) -> pd.DataFrame:
    return pd.DataFrame({
        "timestamp": [c.timestamp for c in candles],
        "open": np.asarray([float(c.open) for c in candles], dtype=np.float64),
        "high": np.asarray([float(c.high) for c in candles], dtype=np.float64),
        "low": np.asarray([float(c.low) for c in candles], dtype=np.float64),
        "close": np.asarray([float(c.close) for c in candles], dtype=np.float64),
        "volume": np.asarray([c.volume for c in candles], dtype=np.float64),
    })


def calculate(candles: list[Candle]) -> tuple[IndicatorValues, pd.DataFrame, pd.Series]:
    data = frame(candles)
    close = data["close"]
    high = data["high"]
    low = data["low"]
    previous_close = close.shift(1)
    true_range = pd.concat(
        [high - low, (high - previous_close).abs(), (low - previous_close).abs()], axis=1
    ).max(axis=1, skipna=True)
    atr = true_range.ewm(alpha=1 / 14, adjust=False, min_periods=14).mean()

    ema20 = close.ewm(span=20, adjust=False, min_periods=20).mean()
    ema50 = close.ewm(span=50, adjust=False, min_periods=50).mean()
    ema200 = close.ewm(span=200, adjust=False, min_periods=200).mean()

    change = close.diff()
    gains = change.clip(lower=0)
    losses = (-change.clip(upper=0))
    average_gain = gains.ewm(alpha=1 / 14, adjust=False, min_periods=14).mean()
    average_loss = losses.ewm(alpha=1 / 14, adjust=False, min_periods=14).mean()
    relative_strength = average_gain / average_loss.replace(0, np.nan)
    rsi = 100 - (100 / (1 + relative_strength))
    no_losses = (average_loss == 0) & (average_gain > 0)
    no_movement = (average_loss == 0) & (average_gain == 0)
    rsi = rsi.mask(no_losses, 100).mask(no_movement, 50)

    ema12 = close.ewm(span=12, adjust=False, min_periods=12).mean()
    ema26 = close.ewm(span=26, adjust=False, min_periods=26).mean()
    macd = ema12 - ema26
    macd_signal = macd.ewm(span=9, adjust=False, min_periods=9).mean()

    up_move = high.diff()
    down_move = -low.diff()
    plus_dm = pd.Series(np.where((up_move > down_move) & (up_move > 0), up_move, 0.0), index=data.index)
    minus_dm = pd.Series(np.where((down_move > up_move) & (down_move > 0), down_move, 0.0), index=data.index)
    plus_dm.iloc[0] = np.nan
    minus_dm.iloc[0] = np.nan
    smoothed_tr = true_range.ewm(alpha=1 / 14, adjust=False, min_periods=14).mean()
    plus_di = 100 * plus_dm.ewm(alpha=1 / 14, adjust=False, min_periods=14).mean() / smoothed_tr.replace(0, np.nan)
    minus_di = 100 * minus_dm.ewm(alpha=1 / 14, adjust=False, min_periods=14).mean() / smoothed_tr.replace(0, np.nan)
    di_sum = plus_di + minus_di
    dx = 100 * (plus_di - minus_di).abs() / di_sum.replace(0, np.nan)
    dx = dx.mask((di_sum == 0) & di_sum.notna(), 0)
    adx = dx.ewm(alpha=1 / 14, adjust=False, min_periods=14).mean()

    volume_average = data["volume"].rolling(20, min_periods=20).mean()
    volume_ratio = data["volume"] / volume_average.replace(0, np.nan)
    latest_volume_ratio = volume_ratio.iloc[-1]
    if data["volume"].iloc[-1] == 0 and volume_average.iloc[-1] == 0:
        latest_volume_ratio = 0.0

    values = IndicatorValues(
        ema20=_number(ema20.iloc[-1]), ema50=_number(ema50.iloc[-1]), ema200=_number(ema200.iloc[-1]),
        adx14=_number(adx.iloc[-1]), rsi14=_number(rsi.iloc[-1]), macd=_number(macd.iloc[-1]),
        macd_signal=_number(macd_signal.iloc[-1]),
        macd_histogram=_number((macd - macd_signal).iloc[-1]), atr14=_number(atr.iloc[-1]),
        volume_average20=_number(volume_average.iloc[-1]), latest_volume_ratio=_number(latest_volume_ratio),
    )
    return values, data, atr


def trend(ema20: float | None, ema50: float | None, ema200: float | None) -> str:
    if ema20 is None or ema50 is None or ema200 is None:
        return "INSUFFICIENT_HISTORY"
    if ema20 > ema50 and ema50 >= ema200:
        return "BULLISH"
    if ema20 < ema50 and ema50 <= ema200:
        return "BEARISH"
    return "NEUTRAL"
