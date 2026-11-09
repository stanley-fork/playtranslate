package com.playtranslate.translation

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Run this call and suspend until its response, CANCELLING the call (which
 * closes its connection) if the coroutine is cancelled first. A blocking
 * `execute()` inside a coroutine does not do that: cancelling the coroutine
 * leaves the request running to its end, and a non-streamed LLM reply can
 * be a minute of that. For a call its user may abandon (an Ask answer). The
 * translation calls stay on `execute()`: they are short, and a blocking call
 * is not counted against the dispatcher's per-host limit for async calls.
 *
 * The caller closes the response. A response that arrives as the coroutine
 * is cancelled is closed here, since nobody will read it.
 */
internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, unread, _ -> unread.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            // After a cancel this is the cancel's own failure, and the
            // continuation has already ended with the cancellation.
            if (cont.isActive) cont.resumeWithException(e)
        }
    })
}
