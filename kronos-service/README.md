# Diablo Scanner — optional Kronos service

This optional service accepts **structured OHLCV candle data** and is designed to invoke the official Kronos-small model with its matching tokenizer. It is not yet deployed or connected to the Android APK. It is separate from the Android APK because the existing APK's llama.cpp binary is for SmolVLM GGUF, not the Kronos PyTorch architecture.

## Run locally / container

Build and run the container, then call `GET /health` and `POST /v1/forecast`. The first forecast downloads `NeoQuasar/Kronos-small` and `NeoQuasar/Kronos-Tokenizer-base` from Hugging Face and may take time / substantial RAM.

## Important current limitations

- This API is **not deployed** by a GitHub commit. It needs a reachable host and a configured URL before the Android app can call it.
- The current screenshot-only chart path does not supply exact OHLCV. A separate reliable candle feed or validated candle extraction adapter is required; the API deliberately does not fabricate candles from pixels.
- The Android app has a saved SmolVLM / Kronos / Hybrid choice. Kronos is deliberately not treated as operational: the Android endpoint setting, network client, response mapping, and verified OHLC adapter are still missing. Selecting Kronos makes scans return WAIT instead of silently falling back.
- The container installs the upstream Kronos runtime requirements. A successful HTTP health response only proves the service starts; a successful `/v1/forecast` request is required to verify model loading and inference.
- Do not treat a forecast as a guaranteed UP/DOWN signal or automatically infer entry/SL/TP from it.

## Request contract

Send at least 32 completed candles, in strictly increasing timestamp order, with OHLC values and optional volume/amount. `timeframe_seconds` must match the candle spacing; `prediction_bars` is capped at 12. The returned OHLC bars are a forecast only.
