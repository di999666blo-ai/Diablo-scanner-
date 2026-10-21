# Kronos Android integration audit

## Audit result

Kronos is **not integrated into the Diablo Scanner APK** as of this commit. The app must not report a Kronos prediction or treat the Kronos engine selector preference as an operational model.

The official Kronos implementation is a PyTorch model family with a dedicated financial-candle tokenizer. Its documented predictor consumes structured historical K-line data (at least open, high, low, close; timestamps are required, while volume/amount may be optional). It is not a GGUF model and cannot be run by the current `llama-mtmd-cli` runtime used for SmolVLM.

Official upstream: https://github.com/shiyu-coder/Kronos

## Components required before enabling Kronos

1. **Model weights** — choose a model suitable for the target phone, e.g. the small/mini family, and pin a specific Hugging Face revision.
2. **Matching tokenizer weights/config** — must match the selected Kronos model. A model file without its tokenizer is insufficient.
3. **Android-compatible inference runtime** — the current SmolVLM llama.cpp executable is not a Kronos runtime. The model architecture and tokenizer need a supported mobile runtime or a separately hosted inference service.
4. **Structured candle input** — derive OHLC timestamps and optional volume from a verified chart-data source. A screenshot alone does not reliably expose exact OHLC values. If candles are estimated from pixels, label them as estimates and reject unclear charts.
5. **Prediction adapter** — convert the model forecast into a documented decision rule for the selected trade duration. Forecasts are not automatically entry/SL/TP levels; levels must be calculated separately from observed price structure and clearly marked unavailable if price scale cannot be read.
6. **Safety and fallback** — keep SmolVLM available, make engine selection explicit, and return WAIT/no setup when model files, input candles, or inference are unavailable. Never silently fall back while claiming Kronos ran.
7. **Tests** — deterministic tests for tokenizer/model pairing, candle validation, timestamp/frequency alignment, forecast parsing, timeout/error handling, and APK packaging; then test latency and memory on the target Android 8 / 4 GB phone.

## Current app state

- SmolVLM assets and llama.cpp packaging are configured by `.github/workflows/android.yml`.
- The dashboard and scalping settings expose three saved choices: **SmolVLM**, **Kronos**, and **Hybrid**. SmolVLM uses the bundled llama.cpp visual route. Hybrid calls only `ChartAnalyzer` and does not depend on SmolVLM or Kronos. Choosing Kronos is retained as the selected preference but the APK deliberately returns WAIT until a genuine Android runtime and verified structured OHLC candles are integrated. A selector alone is not a working Kronos integration.
- A saved preference such as `scalping_engine=KRONOS` is not proof of integration.
- No Kronos weights, tokenizer, Android runtime, broker OHLC adapter, deployed service URL, or verified end-to-end Kronos prediction path are currently present in the app tree. The optional Python service defaults to `NeoQuasar/Kronos-mini` with `NeoQuasar/Kronos-Tokenizer-2k`, includes candle validation and a forecast endpoint, but it is not automatically deployed or connected to the APK.
- Do not treat Kronos as operational until a compatible runtime is integrated, the app can submit verified candles, a real forecast succeeds, and APK/device acceptance checks pass. Hybrid currently means standalone fast chart-image heuristic analysis; it does not include SmolVLM review or a Kronos forecast.

## Why this is intentionally not a fake integration

Bundling arbitrary Kronos files or downloading the Python repository in CI would not make it runnable inside this Android app. The existing native llama.cpp path is for GGUF-compatible models such as SmolVLM. A real Kronos port requires a model-specific runtime and correct structured candle input; claiming otherwise would produce a misleading engine switch.
