# Diablo Scanner — Kronos-mini forecast service

This service uses the upstream Kronos model implementation with the requested model/tokenizer pair by default:

- Model: `NeoQuasar/Kronos-mini`
- Tokenizer: `NeoQuasar/Kronos-Tokenizer-2k`

Environment variables `KRONOS_MODEL_ID` and `KRONOS_TOKENIZER_ID` can override these defaults. The API accepts structured OHLCV candles and returns forecast OHLC bars. It does not manufacture candles from screenshots.

## Run

Install `requirements.txt`, then run:

```bash
uvicorn main:app --host 0.0.0.0 --port 8000
```

Check `GET /health`, then test `POST /v1/forecast` with at least 32 valid completed candles. The first forecast downloads model weights and tokenizer data and can take significant RAM and time.

## Important integration status

- This folder is a **server-side Python service**, not an on-device Android runtime. It requires a reachable host and is not currently connected to the APK.
- The Android scanner captures screenshots. A reliable OHLCV adapter is still required before the screenshot-only Android path can supply the structured input this model requires.
- The APK intentionally returns WAIT when Kronos is selected but no verified Kronos runtime/feed is connected. It must not silently use SmolVLM or the Hybrid analyzer while claiming a Kronos forecast.
- Passing the service contract tests only validates request handling; it does not prove downloaded Kronos weights load or that inference succeeds. A real forecast request with valid candle data is required for that.
- Forecasts are experimental and are not a verified win probability, guaranteed signal, or automated trading instruction.

## Request contract

Send `symbol`, `timeframe_seconds`, `prediction_bars` (1–12), and 32–512 completed candles. Each candle has an ISO timestamp and positive OHLC values, with optional nonnegative volume/amount. Timestamps must increase and align with the requested timeframe. The response contains forecast OHLC bars only.
