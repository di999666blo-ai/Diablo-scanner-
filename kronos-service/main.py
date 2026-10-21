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
MODEL_ID = os.getenv("KRONOS_MODEL_ID", "NeoQuasar/Kronos-mini")
TOKENIZER_ID = os.getenv("KRONOS_TOKENIZER_ID", "NeoQuasar/Kronos-Tokenizer-2k")
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


# BigQuote/biquote.io market-data adapter. The public read API requires no API key.
# Keep the provider boundary explicit so the app never treats screenshot pixels as OHLC.
import json
import re
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

BIQUOTE_BASE_URL = os.getenv("BIQUOTE_BASE_URL", "https://biquote.io").rstrip("/")
INTERVAL_SECONDS = {"1m": 60, "5m": 300, "15m": 900, "30m": 1800, "1h": 3600, "4h": 14400, "1d": 86400}


class MarketForecastRequest(BaseModel):
    symbol: str = Field(default="EURUSD", min_length=1, max_length=40)
    interval: str = Field(default="1m")
    prediction_bars: int = Field(default=3, ge=1, le=12)


def _biquote_get(path: str, params: dict[str, str] | None = None) -> Any:
    query = "?" + urlencode(params) if params else ""
    request = Request(
        f"{BIQUOTE_BASE_URL}{path}{query}",
        headers={"Accept": "application/json", "User-Agent": "DiabloScanner/1.0"},
        method="GET",
    )
    try:
        with urlopen(request, timeout=15) as response:
            if response.status != 200:
                raise RuntimeError(f"biquote returned HTTP {response.status}")
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as exc:
        raise RuntimeError(f"biquote returned HTTP {exc.code}") from exc
    except (URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"biquote market data unavailable: {exc}") from exc


def _validate_symbol(symbol: str) -> str:
    cleaned = symbol.strip().upper()
    if not re.fullmatch(r"[A-Z0-9._-]{1,40}", cleaned):
        raise HTTPException(status_code=422, detail="Invalid instrument symbol.")
    return cleaned


def _fetch_market_candles(symbol: str, interval: str, limit: int = 200) -> list[Candle]:
    if interval not in INTERVAL_SECONDS:
        raise HTTPException(status_code=422, detail="Unsupported interval. Use 1m, 5m, 15m, 30m, 1h, 4h or 1d.")
    data = _biquote_get(f"/api/{symbol}/ohlc", {"interval": interval, "limit": str(min(max(limit, 32), 1000))})
    if not isinstance(data, dict) or not isinstance(data.get("bars"), list):
        raise RuntimeError("biquote returned an unexpected OHLC response.")
    candles: list[Candle] = []
    for bar in data["bars"]:
        # The current forming candle is excluded from inference to avoid training
        # the forecast on an incomplete bar. API documents newest bars first.
        if not isinstance(bar, dict) or bar.get("isOpen") is True:
            continue
        try:
            candles.append(Candle(
                timestamp=bar["openTime"],
                open=bar["open"], high=bar["high"], low=bar["low"], close=bar["close"],
                volume=max(0, float(bar.get("tickVolume", bar.get("volume", 0)) or 0)),
                amount=0,
            ))
        except (KeyError, TypeError, ValueError):
            continue
    candles.sort(key=lambda candle: candle.timestamp)
    # Validate the exact provider result before passing it to the model.
    validate_candles(candles, INTERVAL_SECONDS[interval])
    return candles[-512:]


@app.get("/v1/market/symbols")
def market_symbols(q: str | None = None, live_only: bool = False) -> dict[str, Any]:
    """Return the provider's dynamic instrument catalogue; no hardcoded pair list."""
    params: dict[str, str] = {}
    if q and q.strip():
        params["q"] = q.strip()
        params["limit"] = "200"
        if live_only:
            params["liveOnly"] = "true"
        data = _biquote_get("/api/symbols/search", params)
    else:
        params["activeOnly"] = "true"
        if live_only:
            params["liveOnly"] = "true"
        data = _biquote_get("/api/symbols", params)
    if not isinstance(data, list):
        raise HTTPException(status_code=502, detail="biquote returned an unexpected symbol catalogue.")
    return {"provider": "biquote.io", "count": len(data), "symbols": data}


@app.get("/v1/market/candles")
def market_candles(symbol: str = "EURUSD", interval: str = "1m", limit: int = 200) -> dict[str, Any]:
    symbol = _validate_symbol(symbol)
    candles = _fetch_market_candles(symbol, interval, limit)
    return {
        "provider": "biquote.io",
        "symbol": symbol,
        "interval": interval,
        "timeframe_seconds": INTERVAL_SECONDS[interval],
        "count": len(candles),
        "candles": [c.model_dump(mode="json") for c in candles],
        "note": "Only completed candles are returned; the current open candle is excluded.",
    }


@app.post("/v1/forecast/market")
def forecast_from_market(request: MarketForecastRequest) -> dict[str, Any]:
    """Fetch verified structured candles from biquote, then run the actual Kronos model."""
    symbol = _validate_symbol(request.symbol)
    if request.interval not in INTERVAL_SECONDS:
        raise HTTPException(status_code=422, detail="Unsupported interval. Use 1m, 5m, 15m, 30m, 1h, 4h or 1d.")
    try:
        candles = _fetch_market_candles(symbol, request.interval, 200)
    except HTTPException:
        raise
    except RuntimeError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    forecast_request = ForecastRequest(
        symbol=symbol,
        timeframe_seconds=INTERVAL_SECONDS[request.interval],
        prediction_bars=request.prediction_bars,
        candles=candles,
    )
    try:
        result = build_forecast(forecast_request)
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    result["data_provider"] = "biquote.io"
    result["data_interval"] = request.interval
    result["completed_candles_used"] = len(candles)
    result["market_data_note"] = "Public feed, no API key; quotes and instrument availability depend on the provider's upstream sources and market hours."
    return result
