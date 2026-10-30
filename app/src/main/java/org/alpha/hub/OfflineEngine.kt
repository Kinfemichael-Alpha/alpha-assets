package org.alpha.hub

import android.app.ActivityManager
import android.content.Context
import java.io.File

class OfflineError(val key: String, val detail: String = "") : Exception(key)

fun offlineMessage(e: OfflineError, msg: (String) -> String): String {
    val base = msg(e.key)
    if (e.key == "model_ram_low") {
        val p = e.detail.split("|")
        val need = (p.getOrNull(0)?.toIntOrNull() ?: 0) / 1024.0
        val free = (p.getOrNull(1)?.toIntOrNull() ?: 0) / 1024.0
        return base.replace("{need}", String.format(java.util.Locale.US, "%.1f", need)).replace("{free}", String.format(java.util.Locale.US, "%.1f", free))
    }
    return base
}

class ThinkFilter {
    private val raw = StringBuilder()
    private var emitted = 0
    private val tag = "<think>"
    fun feed(p: String): String {
        raw.append(p)
        var v = raw.toString().replace(Regex("(?s)<think>.*?</think>\\s*"), "")
        val i = v.indexOf(tag)
        if (i >= 0) v = v.substring(0, i)
        for (k in 6 downTo 1) if (v.endsWith(tag.substring(0, k))) { v = v.dropLast(k); break }
        if (v.length <= emitted) return ""
        val r = v.substring(emitted)
        emitted = v.length
        return r
    }
}

class OfflineEngine(private val ctx: Context) {
    private val bridge by lazy { LlamaBridge() }
    private var handle = 0L
    private var loadedPath = ""
    @Volatile var nCtx = 0
        private set

    var turbo: () -> Boolean = { false }

    val supported: Boolean get() = LlamaBridge.available

    private fun memInfo(): ActivityManager.MemoryInfo {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi
    }

    fun totalRamMb(): Int = (memInfo().totalMem / 1048576L).toInt()
    fun freeRamMb(): Int = (memInfo().availMem / 1048576L).toInt()

    fun contextFor(totalMb: Int): Int = when {
        totalMb < 3000 -> 1024
        totalMb < 5000 -> 2048
        totalMb < 8000 -> 3072
        else -> 4096
    }

    fun fits(sizeMb: Int, minRamGb: Int): Boolean = totalRamMb() >= minRamGb * 900

    @Synchronized
    fun load(file: File) {
        if (!supported) throw OfflineError("offline_unavailable")
        if (handle != 0L && loadedPath == file.path) return
        unload()
        val needMb = (file.length() / 1048576L).toInt() + 350
        val free = freeRamMb()
        if (needMb > free + 200) throw OfflineError("model_ram_low", "$needMb|$free")
        val c = contextFor(totalRamMb())
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, if (turbo()) 8 else 4)
        val h = try { bridge.nativeLoad(file.path, c, threads, true) } catch (e: Throwable) { throw OfflineError("model_load_failed", e.toString()) }
        if (h == 0L) throw OfflineError("model_load_failed")
        handle = h; loadedPath = file.path; nCtx = c
    }

    @Synchronized
    fun unload() {
        if (handle != 0L) { try { bridge.nativeFree(handle) } catch (_: Throwable) { } }
        handle = 0L; loadedPath = ""; nCtx = 0
    }

    fun isLoaded(path: String) = handle != 0L && loadedPath == path

    @Synchronized
    fun generate(roles: List<String>, contents: List<String>, maxTokens: Int, temp: Float, sink: TokenSink): Int {
        if (handle == 0L) throw OfflineError("model_load_failed")
        return bridge.nativeGenerate(handle, roles.toTypedArray(), contents.toTypedArray(), maxTokens, temp, sink)
    }
}
