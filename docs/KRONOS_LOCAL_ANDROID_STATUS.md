# Kronos local Android runtime — integration status

## Non-negotiable architecture

- SmolVLM continues using its existing runtime unchanged.
- Kronos must use its own Android inference backend and its own matching model/tokenizer.
- Market candles are fetched by the phone over HTTPS; inference must not call a hosted service.
- No prediction is shown when model/runtime validation fails. The safe result is WAIT.

## Current code

`KronosLocalRuntime.java` is a separate fail-closed runtime boundary. It checks for these future packaged assets:

- `app/src/main/assets/kronos/kronos_mini.onnx`
- `app/src/main/assets/kronos/kronos_tokenizer.onnx`
- `app/src/main/assets/kronos/runtime.json`

It also checks for ONNX Runtime Android on the classpath. The class intentionally returns WAIT even when those files exist until inference parity is validated. This avoids presenting an untested or fabricated forecast.

## Important compatibility constraint

The official Kronos-mini and Kronos-Tokenizer-2k releases are PyTorch model/tokenizer artifacts. They are not GGUF and cannot be loaded by SmolVLM's llama.cpp runtime. Renaming the files or adding a model path is not a port.

Official upstream: https://github.com/shiyu-coder/Kronos
Model IDs: `NeoQuasar/Kronos-mini` and `NeoQuasar/Kronos-Tokenizer-2k`.

## Required work before local forecasts can be enabled

1. Export both tokenizer and forecaster to Android-compatible graphs, or implement the equivalent native architecture. Confirm every operation is supported by the selected runtime.
2. Implement the exact upstream normalization, timestamp features, hierarchical tokenization/quantization, autoregressive token decoding, and inverse transforms.
3. Add the required model files and metadata to the Android assets during CI; verify checksums and licenses.
4. Add deterministic golden tests that compare Android outputs to the upstream Python predictor for fixed candle fixtures and timestamps.
5. Test at least 32 valid completed candles, malformed/duplicate timestamps, missing volume, flat-price windows, non-finite outputs, and invalid OHLC bounds.
6. Measure peak RAM, cold-start time, and inference latency on a real low-memory Android phone.
7. Only then wire the local runtime into the Kronos dashboard path and remove the remote service requirement from that path.

## Build/test truth

Adding this boundary is not the same as completing Kronos inference. Until the export, tokenizer parity, tests, and on-device run pass, the local Kronos forecast is not complete. The existing SmolVLM runtime should remain untouched during this work.
