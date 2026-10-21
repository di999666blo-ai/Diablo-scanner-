from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient

from main import app, validate_candles, Candle

client = TestClient(app)


def sample_candles(count=32):
    start = datetime(2026, 1, 1, tzinfo=timezone.utc)
    return [
        Candle(
            timestamp=start + timedelta(minutes=i),
            open=100 + i, high=102 + i, low=99 + i,
            close=101 + i, volume=10, amount=1000,
        )
        for i in range(count)
    ]


def test_health_marks_inference_not_checked():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["engine"] == "KRONOS"
    assert response.json()["inference"] == "model_not_loaded_yet"
    assert response.json()["model"] == "NeoQuasar/Kronos-mini"
    assert response.json()["tokenizer"] == "NeoQuasar/Kronos-Tokenizer-2k"


def test_valid_candles_pass():
    validate_candles(sample_candles())


def test_rejects_non_increasing_timestamps():
    candles = sample_candles()
    candles[4] = candles[3]
    with pytest.raises(ValueError, match="strictly increasing"):
        validate_candles(candles)


def test_rejects_invalid_high_low():
    candles = sample_candles()
    candles[0] = Candle(
        timestamp=candles[0].timestamp,
        open=100, high=98, low=99, close=101, volume=0, amount=0,
    )
    with pytest.raises(ValueError, match="high must be"):
        validate_candles(candles)


def test_forecast_rejects_too_few_bars():
    payload = {
        "symbol": "TEST",
        "timeframe_seconds": 60,
        "prediction_bars": 3,
        "candles": [c.model_dump(mode="json") for c in sample_candles(10)],
    }
    response = client.post("/v1/forecast", json=payload)
    assert response.status_code == 422


def test_rejects_timestamp_spacing_not_aligned_to_timeframe():
    candles = sample_candles()
    candles[5] = Candle(
        timestamp=candles[4].timestamp + timedelta(seconds=30),
        open=105, high=107, low=104, close=106, volume=10, amount=1000,
    )
    with pytest.raises(ValueError, match="timestamp spacing must align"):
        validate_candles(candles, timeframe_seconds=60)


def test_forecast_rejects_bad_timeframe_spacing():
    candles = sample_candles()
    candles[8] = Candle(
        timestamp=candles[7].timestamp + timedelta(seconds=30),
        open=108, high=110, low=107, close=109, volume=10, amount=1000,
    )
    payload = {
        "symbol": "TEST",
        "timeframe_seconds": 60,
        "prediction_bars": 3,
        "candles": [c.model_dump(mode="json") for c in candles],
    }
    response = client.post("/v1/forecast", json=payload)
    assert response.status_code == 422
    assert "timestamp spacing must align" in response.json()["detail"]



def test_market_symbols_uses_dynamic_provider_catalogue(monkeypatch):
    import main

    monkeypatch.setattr(main, "_biquote_get", lambda path, params=None: [
        {"name": "EURUSD", "type": "Forex"}, {"name": "XAUUSD", "type": "Commodity"}
    ])
    response = client.get("/v1/market/symbols")
    assert response.status_code == 200
    assert response.json()["provider"] == "biquote.io"
    assert response.json()["count"] == 2
    assert response.json()["symbols"][1]["name"] == "XAUUSD"


def test_market_candles_excludes_open_bar_and_sorts_completed_bars(monkeypatch):
    import main
    start = datetime(2026, 1, 1, tzinfo=timezone.utc)
    bars = [{
        "openTime": (start + timedelta(minutes=i)).isoformat(),
        "open": 100 + i, "high": 102 + i, "low": 99 + i,
        "close": 101 + i, "volume": 0, "tickVolume": 10, "isOpen": False,
    } for i in range(40)]
    bars.reverse()
    bars.insert(0, {
        "openTime": (start + timedelta(minutes=40)).isoformat(),
        "open": 140, "high": 142, "low": 139, "close": 141,
        "volume": 0, "tickVolume": 10, "isOpen": True,
    })
    monkeypatch.setattr(main, "_biquote_get", lambda path, params=None: {"symbol": "EURUSD", "bars": bars})
    response = client.get("/v1/market/candles?symbol=EURUSD&interval=1m&limit=100")
    assert response.status_code == 200
    body = response.json()
    assert body["provider"] == "biquote.io"
    assert body["count"] == 40
    assert body["candles"][0]["open"] == 100
    assert body["candles"][-1]["close"] == 140


def test_market_forecast_fetches_real_provider_candles_then_calls_kronos(monkeypatch):
    import main
    start = datetime(2026, 1, 1, tzinfo=timezone.utc)
    bars = [{
        "openTime": (start + timedelta(minutes=i)).isoformat(),
        "open": 100 + i, "high": 102 + i, "low": 99 + i,
        "close": 101 + i, "volume": 0, "tickVolume": 10, "isOpen": False,
    } for i in range(40)]
    bars.reverse()
    monkeypatch.setattr(main, "_biquote_get", lambda path, params=None: {"symbol": "EURUSD", "bars": bars})
    captured = {}
    def fake_forecast(request):
        captured["request"] = request
        return {
            "engine": "KRONOS", "model": "test", "tokenizer": "test",
            "symbol": request.symbol, "timeframe_seconds": request.timeframe_seconds,
            "prediction_bars": [{"open": 140, "high": 142, "low": 139, "close": 141}],
            "last_observed_close": request.candles[-1].close, "status": "FORECAST_ONLY",
        }
    monkeypatch.setattr(main, "build_forecast", fake_forecast)
    response = client.post("/v1/forecast/market", json={
        "symbol": "EURUSD", "interval": "1m", "prediction_bars": 1
    })
    assert response.status_code == 200
    body = response.json()
    assert body["data_provider"] == "biquote.io"
    assert body["completed_candles_used"] == 40
    assert captured["request"].timeframe_seconds == 60
    assert len(captured["request"].candles) == 40


def test_market_forecast_rejects_invalid_symbol():
    response = client.post("/v1/forecast/market", json={
        "symbol": "../EURUSD", "interval": "1m", "prediction_bars": 1
    })
    assert response.status_code == 422
