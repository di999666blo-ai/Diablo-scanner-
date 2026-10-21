"""Optional Kronos inference API for Diablo Scanner.

This is a separate service because upstream Kronos uses its own PyTorch model and
financial-candle tokenizer; it cannot run through the app's llama.cpp/SmolVLM binary.
The Android app must not claim Kronos is active until this service is deployed and
configured in the app.
"""
from __future__ import annotations

import os
from datetime import datetime
from functools import lru_cache
from math import isfinite
from typing import Any

import pandas as pd
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field, field_validator

app = FastAPI(title="Diablo Kronos Forecast API", version="0.1.0")
MODEL_ID = os.getenv("KRONOS_MODEL_ID", "NeoQuasar/Kronos-small")
TOKENIZER_ID = os.getenv("KRONOS_TOKENIZER_ID", "NeoQuasar/Kronos-Tokenizer-base")
MAX_BARS = 512


class Candle(BaseModel):
    timestamp: datetime
    open: float = Field(gt=0, allow_inf_nan=False)
    high: float = Field(gt=0, allow_inf_nan=False)
    low: float = Field(gt=0, allow_inf_nan=False)
    close: float = Field(gt=0, allow_inf_nan=False)
    volume: float = Field(default=0, ge=0, allow_inf_nan=False)
    amount: float = Field(default=0, ge=0, allow_inf_nan=False)

    @field_validator("high")
    @classmethod
    def high_not_below_low_or_open_close(cls, value: float, info: Any) -> float:
        # Cross-field checks are performed after parsing the full candle below.
        return value


class ForecastRequest(BaseModel):
    symbol: str = Field(min_length=1, max_length=40)
    timeframe_seconds: int = Field(gt=0, le=86400)
    prediction_bars: int = Field(default=3, ge=1, le=12)
    candles: list[Candle] = Field(min_length=32, max_length=MAX_BARS)


def validate_candles(candles: list[Candle], timeframe_seconds: int | None = None) -> None:
    times = [c.timestamp for c in candles]
    if any(b <= a for a, b in zip(times, times[1:])):
        raise ValueError("Candle timestamps must be strictly increasing.")
    if timeframe_seconds is not None:
        for i, (a, b) in enumerate(zip(times, times[1:]), start=1):
            delta = (b - a).total_seconds()
            # Allow market-session gaps, but require each timestamp gap to align
            # to the requested candle interval (and reject irregular/mislabeled data).
            if delta < timeframe_seconds or delta % timeframe_seconds != 0:
                raise ValueError(
                    f"Candle {i}: timestamp spacing must align to timeframe_seconds={timeframe_seconds}."
                )
    for i, c in enumerate(candles):
        if c.high < max(c.open, c.close, c.low):
            raise ValueError(f"Candle {i}: high must be >= open, close, and low.")
        if c.low > min(c.open, c.close, c.high):
            raise ValueError(f"Candle {i}: low must be <= open, close, and high.")
    if len(candles) < 32:
        raise ValueError("At least 32 completed candles are required.")


@lru_cache(maxsize=1)
def _load_predictor(model_id: str, tokenizer_id: str):
    """Load one pinned model/tokenizer pair once per service process."""
    from model import Kronos, KronosPredictor, KronosTokenizer
    tokenizer = KronosTokenizer.from_pretrained(tokenizer_id)
    model = Kronos.from_pretrained(model_id)
    return KronosPredictor(model, tokenizer, max_context=min(MAX_BARS, 512), device="cpu")


def build_forecast(request: ForecastRequest) -> dict[str, Any]:
    """Load the upstream model lazily so /health works without model downloads."""
    try:
        import torch
        predictor = _load_predictor(MODEL_ID, TOKENIZER_ID)
    except Exception as exc:
        raise RuntimeError(f"Kronos dependencies/model are unavailable: {exc}") from exc

    try:
        rows = [{
            "open": c.open, "high": c.high, "low": c.low, "close": c.close,
            "volume": c.volume, "amount": c.amount,
        } for c in request.candles]
        frame = pd.DataFrame(rows)
        x_ts = pd.Series(pd.to_datetime([c.timestamp for c in request.candles]))
        last = request.candles[-1].timestamp
        y_ts = pd.Series([
            last + pd.Timedelta(seconds=request.timeframe_seconds * i)
            for i in range(1, request.prediction_bars + 1)
        ])
        with torch.inference_mode():
            pred = predictor.predict(
                df=frame, x_timestamp=x_ts, y_timestamp=y_ts,
                pred_len=request.prediction_bars, T=1.0, top_p=0.9,
                sample_count=1, verbose=False,
            )
        rows = pred[["open", "high", "low", "close"]].to_dict(orient="records")
        if not rows or any(
            not all(pd.notna(v) and isfinite(float(v)) and float(v) > 0 for v in row.values())
            or float(row["high"]) < max(float(row["open"]), float(row["close"]), float(row["low"]))
            or float(row["low"]) > min(float(row["open"]), float(row["close"]), float(row["high"]))
            for row in rows
        ):
            raise ValueError("Kronos returned empty, non-finite, non-positive, or invalid OHLC forecast values.")
        return {
            "engine": "KRONOS",
            "model": MODEL_ID,
            "tokenizer": TOKENIZER_ID,
            "symbol": request.symbol,
            "timeframe_seconds": request.timeframe_seconds,
            "prediction_bars": rows,
            "last_observed_close": request.candles[-1].close,
            "status": "FORECAST_ONLY",
            "warning": "Forecast is experimental, not a verified win probability or trade instruction.",
        }
    except Exception as exc:
        raise RuntimeError(f"Kronos inference failed: {exc}") from exc


@app.get("/health")
def health() -> dict[str, str]:
    return {
        "status": "service_online",
        "engine": "KRONOS",
        "model": MODEL_ID,
        "tokenizer": TOKENIZER_ID,
        "inference": "model_not_loaded_yet",
        "forecast_endpoint": "/v1/forecast",
    }


@app.post("/v1/forecast")
def forecast(request: ForecastRequest) -> dict[str, Any]:
    try:
        validate_candles(request.candles, request.timeframe_seconds)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    try:
        return build_forecast(request)
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
