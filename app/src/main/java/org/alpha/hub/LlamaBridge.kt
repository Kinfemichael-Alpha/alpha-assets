package org.alpha.hub

fun interface TokenSink { fun onToken(piece: String): Boolean }

fun interface ByteSink { fun onBytes(piece: ByteArray): Boolean }

class LlamaBridge {
    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int, useMmap: Boolean): Long
    external fun nativeFree(handle: Long)
    external fun nativeGenerate(handle: Long, roles: Array<String>, contents: Array<ByteArray>, maxTokens: Int, temp: Float, sink: ByteSink): Int

    companion object {
        val available: Boolean = try { System.loadLibrary("alphallm"); true } catch (_: Throwable) { false }
    }
}
