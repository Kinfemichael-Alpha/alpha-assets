package org.alpha.hub

import android.content.Context
import java.io.File

object CrashLog {
    fun install(ctx: Context) {
        val f = File(ctx.filesDir, "crash.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { f.writeText(android.util.Log.getStackTraceString(e).take(2500)) } catch (_: Exception) { }
            prev?.uncaughtException(t, e)
        }
    }

    fun read(ctx: Context): String? = try {
        val f = File(ctx.filesDir, "crash.txt")
        if (f.exists()) f.readText().ifBlank { null } else null
    } catch (_: Exception) { null }

    fun clear(ctx: Context) { try { File(ctx.filesDir, "crash.txt").delete() } catch (_: Exception) { } }
}
