package com.diablo.scanner.kronos

import org.json.JSONObject

data class KronosManifest(
    val formatVersion: Int, val precision: String, val modelId: String, val tokenizerId: String,
    val modelRevision: String, val tokenizerRevision: String, val maxContext: Int,
    val cacheCapacity: Int, val opset: Int, val s1Bits: Int, val s2Bits: Int,
    val s1VocabSize: Int, val s2VocabSize: Int, val codebookDim: Int, val dModel: Int,
    val nLayers: Int, val nHeads: Int, val headDim: Int, val depNHeads: Int,
    val depHeadDim: Int, val clip: Float, val files: ModelFiles
) {
    val transformerCacheElements: Int get() = nLayers * nHeads * cacheCapacity * headDim
    val depCacheElements: Int get() = depNHeads * cacheCapacity * depHeadDim
    fun effectiveLookback(requested: Int, predLen: Int): Int =
        requested.coerceIn(MIN_LOOKBACK, maxContext - predLen)
    companion object {
        const val MIN_LOOKBACK = 32
        fun parse(text: String): KronosManifest {
            val j = JSONObject(text)
            val f = j.getJSONObject("files")
            return KronosManifest(
                j.getInt("formatVersion"), j.getString("precision"), j.getString("modelId"),
                j.getString("tokenizerId"), j.getString("modelRevision"), j.getString("tokenizerRevision"),
                j.getInt("maxContext"), j.getInt("cacheCapacity"), j.getInt("opset"),
                j.getInt("s1Bits"), j.getInt("s2Bits"), j.getInt("s1VocabSize"), j.getInt("s2VocabSize"),
                j.getInt("codebookDim"), j.getInt("dModel"), j.getInt("nLayers"), j.getInt("nHeads"),
                j.getInt("headDim"), j.getInt("depNHeads"), j.getInt("depHeadDim"), j.getDouble("clip").toFloat(),
                ModelFiles(f.getString("tokenizerEncode"), f.getString("tokenizerDecode"), f.getString("decode"), f.getString("stepS2"))
            )
        }
    }
}
data class ModelFiles(val tokenizerEncode: String, val tokenizerDecode: String, val decode: String, val stepS2: String)
data class Candle(val timestamp: Long, val open: Float, val high: Float, val low: Float, val close: Float, val volume: Float, val amount: Float)
data class ForecastParams(val lookback: Int, val predLen: Int, val sampleCount: Int, val temperature: Float, val topK: Int, val topP: Float, val seed: Long)
