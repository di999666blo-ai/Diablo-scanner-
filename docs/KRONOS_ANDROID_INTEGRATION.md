# Kronos Android integration audit

## Audit result

Kronos-mini now has an **online Android API client** in the APK and a Docker/Render deployment blueprint. It is not yet end-to-end operational until the API is deployed, its HTTPS URL is saved in the app, and valid OHLCV candle data is supplied.

The official Kronos implementation is a PyTorch model family with a dedicated financial-candle tokenizer. Its documented predictor consumes structured historical K-line data (at least open, high, low, close; timestamps are required, while volume/amount may be optional). It is not a GGUF model and cannot be run by the current `llama-mtmd-cli` runtime used for SmolVLM.

Official upstream: https://github.com/shiyu-coder/Kronos

## Components required before enabling Kronos

1. **Model weights** — choose a model suitable for the target phone, e.g. the small/mini family, and pin a specific Hugging Face revision.
2. **Matching tokenizer weights/config** — must match the selected Kronos model. A model file without its tokenizer is insufficient.
3. **Inference runtime** — the current SmolVLM llama.cpp executable is not a Kronos runtime. This implementation uses the separate Dockerized Python/PyTorch service; it requires an online host and is not offline/on-device inference.
4. **Structured candle input** — derive OHLC timestamps and optional volume from a verified chart-data source. A screenshot alone does not reliably expose exact OHLC values. If candles are estimated from pixels, label them as estimates and reject unclear charts.
5. **Prediction adapter** — convert the model forecast into a documented decision rule for the selected trade duration. Forecasts are not automatically entry/SL/TP levels; levels must be calculated separately from observed price structure and clearly marked unavailable if price scale cannot be read.
6. **Safety and fallback** — keep SmolVLM available, make engine selection explicit, and return WAIT/no setup when model files, input candles, or inference are unavailable. Never silently fall back while claiming Kronos ran.
7. **Tests** — deterministic tests for tokenizer/model pairing, candle validation, timestamp/frequency alignment, forecast parsing, timeout/error handling, and APK packaging; then test latency and memory on the target Android 8 / 4 GB phone.

## Current app state

- SmolVLM assets and llama.cpp packaging are configured by `.github/workflows/android.yml`.
- The dashboard and scalping settings expose three saved choices: **SmolVLM**, **Kronos-mini online**, and **Hybrid**. SmolVLM uses the bundled llama.cpp visual route. Hybrid calls only `ChartAnalyzer` and does not depend on SmolVLM or Kronos. The APK now sends a structured request to the configured HTTPS `/v1/forecast` endpoint and validates the returned forecast. It returns WAIT if the endpoint URL or candle JSON is missing, the service fails, or the response is invalid.
- A saved preference such as `scalping_engine=KRONOS` is not proof of integration.
- The service uses the upstream Kronos Python implementation and defaults to `NeoQuasar/Kronos-mini` with `NeoQuasar/Kronos-Tokenizer-2k`. `kronos-service/Dockerfile` packages the actual model code; `render.yaml` provides a free-tier Render blueprint. The service is not automatically deployed, and the app cannot obtain a broker's OHLC feed from a screenshot.
- To deploy, open the repository's `render.yaml` as a new Blueprint in Render, wait for the web service to build, then save its HTTPS URL in the app's Scalping Engine Settings. Paste a complete JSON request containing at least 32 timestamped completed candles. Free hosting may sleep or have memory limits; a successful container build alone does not prove model inference. Hybrid remains standalone fast chart-image heuristic analysis and does not include SmolVLM or Kronos.

## Why this is intentionally not a fake integration

Bundling arbitrary Kronos files or downloading the Python repository in CI would not make it runnable inside this Android app. The existing native llama.cpp path is for GGUF-compatible models such as SmolVLM. A real Kronos port requires a model-specific runtime and correct structured candle input; claiming otherwise would produce a misleading engine switch.


## Phone-only routing correction (2026-10-24)

The selected **KRONOS** engine now routes live scans and the dashboard's Run Kronos action only through `KronosLocalRuntime`. The app fetches structured completed BigQuote candles on the phone, then invokes the local runtime. A saved legacy `kronos_service_url` preference must not redirect this selected engine to a hosted API. The online adapter remains in the repository for legacy/service testing, but it is not the selected phone-only engine path.

**Status remains RED for actual on-device inference:** `KronosLocalRuntime` deliberately returns WAIT because the APK does not yet contain a validated Android-compatible Kronos model/tokenizer export and the matching autoregressive decoding plus price reconstruction implementation. This routing correction prevents accidental hosting dependency; it does not pretend to complete the missing model port. CI must verify that the selected engine calls the local runtime and does not call `KronosRuntimeClient.forecast`.
