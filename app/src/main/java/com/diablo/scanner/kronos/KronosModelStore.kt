package com.diablo.scanner.kronos

import android.content.Context
import java.io.File

object KronosModelStore {
    const val ASSET_DIR = "kronos"
    const val MANIFEST_NAME = "kronos_manifest.json"
    fun ensureInstalled(context: Context, onProgress: ((String, Int) -> Unit)? = null): File {
        val target = File(context.filesDir, ASSET_DIR)
        check((target.exists() || target.mkdirs())) { "Cannot create local Kronos model directory" }
        val names = context.assets.list(ASSET_DIR)?.filter { it.endsWith(".onnx") || it == MANIFEST_NAME }?.sorted().orEmpty()
        check(names.size >= 5) { "APK does not contain the four Kronos ONNX graphs and manifest." }
        val marker = File(target, "model.version")
        val fingerprint = names.joinToString("|") { name ->
            val length = context.assets.open("$ASSET_DIR/$name").use { it.available() }
            "$name:$length"
        }
        if (marker.isFile && marker.readText() == fingerprint && names.all { File(target, it).isFile && File(target, it).length() > 0 }) return target
        names.forEachIndexed { index, name ->
            onProgress?.invoke(name, index)
            val out = File(target, name)
            val tmp = File(target, "$name.tmp")
            context.assets.open("$ASSET_DIR/$name").use { input -> tmp.outputStream().use { input.copyTo(it, 65536) } }
            if (out.exists()) out.delete()
            check(tmp.renameTo(out) || run { tmp.copyTo(out, overwrite = true); tmp.delete(); out.isFile }) { "Could not install $name" }
        }
        marker.writeText(fingerprint)
        return target
    }
    fun loadManifest(modelDir: File): KronosManifest {
        val file = File(modelDir, MANIFEST_NAME)
        check(file.isFile) { "Missing local Kronos manifest" }
        return KronosManifest.parse(file.readText())
    }
}