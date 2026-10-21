# Diablo Scanner — Kronos-mini market forecast service

This service runs the upstream Kronos model/tokenizer pair:
- Model: `NeoQuasar/Kronos-mini`
- Tokenizer: `NeoQuasar/Kronos-Tokenizer-2k`

Kronos is a numerical OHLC forecasting engine. It does not read chart screenshots. The Android visual engines remain independent and are still used for broker/OTC charts.

## Free public market-data adapter

The service now integrates the public **biquote.io** read API. The provider documentation states that read endpoints require no API key or signup. Actual symbol coverage, freshness and upstream availability are provider-dependent; do not assume every broker OTC instrument exists in this feed.

- `GET /v1/market/symbols` — dynamic provider instrument catalogue.
- `GET /v1/market/symbols?q=gold` — search instruments by name/description.
- `GET /v1/market/candles?symbol=EURUSD&interval=1m&limit=200` — validated completed OHLCV candles.
- `POST /v1/forecast/market` — fetch provider candles and call the actual Kronos model.
- `POST /v1/forecast` — forecast from explicitly supplied structured OHLCV candles.
- `GET /health` — service health (not proof that model inference has succeeded).

Supported intervals: `1m`, `5m`, `15m`, `30m`, `1h`, `4h`, `1d`. The market adapter excludes the currently open candle and requires at least 32 valid completed candles before inference.

Example market forecast request:

```json
{
  "symbol": "EURUSD",
  "interval": "1m",
  "prediction_bars": 3
}
```

## Run / deploy

Install `requirements.txt`, then run:

```bash
uvicorn main:app --host 0.0.0.0 --port 8080
```

The Android app must be configured with the HTTPS base URL of a deployed instance. Mobile data gives the phone internet access; it does not host the Kronos model. The repository contains a Render blueprint, but the service is not deployed by a Git commit alone. Hosting availability, sleep limits, RAM and model startup times depend on the provider/plan; do not treat a free plan as guaranteed permanent uptime.

The first real forecast downloads model weights and tokenizer data and can take significant RAM and time. Service-contract tests do not prove that weights load or inference succeeds. A successful `POST /v1/forecast/market` with live provider candles is the real end-to-end test.

## Safety and interpretation

The returned output is a model OHLC forecast, not a validated entry, stop-loss, take-profit, or win probability. The app derives only an experimental UP/DOWN/WAIT direction from the first forecast close versus the last observed close. No broker orders are placed. If the provider, host, or model fails, the app must show WAIT and must not silently switch to SmolVLM or Hybrid.
