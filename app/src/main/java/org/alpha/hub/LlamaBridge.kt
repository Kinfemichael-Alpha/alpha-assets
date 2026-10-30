package org.alpha.hub

fun interface TokenSink { fun onToken(piece: String): Boolean }

class LlamaBridge {
    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int, useMmap: Boolean): Long
    external fun nativeFree(handle: Long)
    external fun nativeGenerate(handle: Long, roles: Array<String>, contents: Array<String>, maxTokens: Int, temp: Float, sink: TokenSink): Int

    companion object {
        val available: Boolean = try { System.loadLibrary("alphallm"); true } catch (_: Throwable) { false }
    }
}
