"""Optional Kronos inference API for Diablo Scanner.

This is a separate service because upstream Kronos uses its own PyTorch model and
financial-candle tokenizer; it cannot run through the app's llama.cpp/SmolVLM binary.
The Android app must not claim Kronos is active until this service is deployed and
configured in the app.
"""
from __future__ import annotations

import os
from datetime import datetime
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
    open: float = Field(gt=0)
    high: float = Field(gt=0)
    low: float = Field(gt=0)
    close: float = Field(gt=0)
    volume: float = Field(default=0, ge=0)
    amount: float = Field(default=0, ge=0)

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


def validate_candles(candles: list[Candle]) -> None:
    times = [c.timestamp for c in candles]
    if any(b <= a for a, b in zip(times, times[1:])):
        raise ValueError("Candle timestamps must be strictly increasing.")
    for i, c in enumerate(candles):
        if c.high < max(c.open, c.close, c.low):
            raise ValueError(f"Candle {i}: high must be >= open, close, and low.")
        if c.low > min(c.open, c.close, c.high):
            raise ValueError(f"Candle {i}: low must be <= open, close, and high.")
    if len(candles) < 32:
        raise ValueError("At least 32 completed candles are required.")


def build_forecast(request: ForecastRequest) -> dict[str, Any]:
    """Load the upstream model lazily so /health works without model downloads."""
    try:
        from model import Kronos, KronosPredictor, KronosTokenizer
        import torch
    except Exception as exc:
        raise RuntimeError(f"Kronos dependencies are unavailable: {exc}") from exc

    try:
        tokenizer = KronosTokenizer.from_pretrained(TOKENIZER_ID)
        model = Kronos.from_pretrained(MODEL_ID)
        predictor = KronosPredictor(
            model, tokenizer, max_context=min(MAX_BARS, 512),
            device="cpu",
        )
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
        if not rows or any(not all(pd.notna(v) for v in row.values()) for row in rows):
            raise ValueError("Kronos returned empty or invalid forecast values.")
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
        "inference": "not_checked_until_forecast_request",
    }


@app.post("/v1/forecast")
def forecast(request: ForecastRequest) -> dict[str, Any]:
    try:
        validate_candles(request.candles)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    try:
        return build_forecast(request)
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
