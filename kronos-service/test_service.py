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
    assert response.json()["inference"] == "not_checked_until_forecast_request"


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
