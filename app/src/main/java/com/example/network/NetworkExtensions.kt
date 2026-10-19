package com.example.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Asynchronously executes an OkHttp Call in a coroutine with complete cancellation support.
 * Cancelling the coroutine immediately cancels the underlying HTTP network socket call.
 */
suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            cancel()
        }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWithException(e)
            }
        })
    }
}

/**
 * Redacts API keys, passwords, and sensitive query tokens for secure debug logging.
 */
fun String?.redactSecret(): String {
    if (this == null) return "<null>"
    val trimmed = this.trim()
    if (trimmed.isEmpty()) return "<empty>"
    if (trimmed.length <= 8) return "***"
    return trimmed.take(4) + "..." + trimmed.takeLast(4)
}

/**
 * Extracts the media file extension from a URL path, safely ignoring query strings and fragments.
 */
fun mediaExtensionOf(url: String?): String {
    if (url.isNullOrBlank()) return ""
    val withoutFragment = url.substringBefore('#')
    val withoutQuery = withoutFragment.substringBefore('?')
    val lastSlash = withoutQuery.lastIndexOf('/')
    val filename = if (lastSlash >= 0) withoutQuery.substring(lastSlash + 1) else withoutQuery
    val lastDot = filename.lastIndexOf('.')
    if (lastDot < 0 || lastDot == filename.length - 1) return ""
    return filename.substring(lastDot + 1).lowercase(Locale.US)
}
