package com.diablo.scanner;

import android.content.Context;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;

/**
 * Separate on-device Kronos runtime boundary.
 *
 * This deliberately does not reuse SmolVLM/llama.cpp and never calls a hosted
 * inference service. It only reports READY when the dedicated Kronos model,
 * tokenizer, and a compatible native inference backend are all present.
 *
 * IMPORTANT: the upstream Kronos weights are PyTorch state dictionaries, not
 * GGUF. They cannot be loaded by the SmolVLM llama.cpp runtime. A supported
 * Android backend and a validated export are required before inference can be
 * enabled. Until then this class fails closed with WAIT; it never fabricates a
 * forecast.
 */
public final class KronosLocalRuntime {
    private static final String ASSET_DIR = "kronos";
    private static final String MODEL_FILE = "kronos_mini.onnx";
    private static final String TOKENIZER_FILE = "kronos_tokenizer.onnx";
    private static final String METADATA_FILE = "runtime.json";

    public enum State {
        READY,
        MODEL_MISSING,
        TOKENIZER_MISSING,
        BACKEND_UNAVAILABLE,
        INVALID_METADATA,
        INFERENCE_UNAVAILABLE
    }

    public static final class Status {
        public final State state;
        public final String message;

        private Status(State state, String message) {
            this.state = state;
            this.message = message;
        }

        public boolean isReady() {
            return false; // No production-ready Kronos inference implementation is present yet.
        }
    }

    private KronosLocalRuntime() {}

    public static Status inspect(Context context) {
        if (!assetExists(context, ASSET_DIR + "/" + MODEL_FILE)) {
            return new Status(State.MODEL_MISSING,
                    "WAIT — local Kronos model is not bundled. Add a validated Android-compatible Kronos-mini export; SmolVLM weights are not compatible.");
        }
        if (!assetExists(context, ASSET_DIR + "/" + TOKENIZER_FILE)) {
            return new Status(State.TOKENIZER_MISSING,
                    "WAIT — matching Kronos tokenizer asset is missing.");
        }
        if (!assetExists(context, ASSET_DIR + "/" + METADATA_FILE)) {
            return new Status(State.INVALID_METADATA,
                    "WAIT — Kronos runtime metadata is missing.");
        }
        if (!hasOnnxRuntime(context)) {
            return new Status(State.BACKEND_UNAVAILABLE,
                    "WAIT — the dedicated ONNX Runtime Android backend is not installed. No remote service or substitute forecaster will be used.");
        }
        return new Status(State.INFERENCE_UNAVAILABLE,
                "WAIT — Kronos model files and Android backend were detected, but the real Kronos tokenizer, autoregressive decoding, and OHLC inverse-transform path are not implemented/validated. Forecasts remain disabled.");
    }

    /**
     * Intentional fail-closed entry point until the exported Kronos model's
     * tensor contract, tokenizer parity, and forecast outputs pass validation.
     */
    public static LocalVisionAI.Result forecast(Context context, String candleJson) {
        if (candleJson == null || candleJson.trim().isEmpty()) {
            return new LocalVisionAI.Result("WAIT",
                    "KRONOS LOCAL: valid completed-candle input is required.");
        }
        Status status = inspect(context);
        if (!status.isReady()) {
            return new LocalVisionAI.Result("WAIT", status.message);
        }
        return new LocalVisionAI.Result("WAIT",
                "KRONOS LOCAL: inference is deliberately disabled until the official tokenizer and autoregressive model are ported and parity-tested. No forecast was fabricated.");
    }

    private static boolean assetExists(Context context, String path) {
        try (InputStream in = context.getAssets().open(path)) {
            return in.read() >= 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean hasOnnxRuntime(Context context) {
        try {
            Class.forName("ai.onnxruntime.OrtEnvironment");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
