package com.playtranslate.translation

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Runs [read], a blocking step of [call] (its `execute()` and the reading of
 * the response, or a later read of a response left open), so that cancelling
 * the calling coroutine cancels [call] at once, wherever the step stands:
 * waiting for the response's headers, or reading its body. OkHttp then fails
 * the blocked read, and the continuation, already cancelled, throws
 * [kotlinx.coroutines.CancellationException], whatever [read] returned or
 * threw. A plain blocking `execute()` inside a coroutine does not do that:
 * cancelling the coroutine leaves the request running to its end, and a
 * non-streamed LLM reply can be a minute of that. A `Job.invokeOnCompletion`
 * hook could not either: it fires only after the blocked read returns by
 * itself.
 *
 * For a call that may be abandoned while it runs: PlayTranslate Cloud's
 * ([com.playtranslate.cloud.PtCloudClient]) and an online service's Ask
 * answer ([OpenAiBackend], [GeminiBackend], over [executeToEnd]). The step
 * takes the reading of the body with the `execute()` on purpose. A service
 * may send its headers before the reply is written, which puts the wait in
 * the body's read, so a cancel that watched the call only up to its headers
 * would leave that wait running.
 *
 * [read] runs on the calling thread, which it blocks: call this where
 * blocking is allowed ([kotlinx.coroutines.Dispatchers.IO]). A cancel does
 * not undo what [read] had done before it arrived, and what [read] returns
 * to a cancelled coroutine is dropped, so a step that returns something to
 * close keeps its own hold on it ([com.playtranslate.cloud.PtCloudClient.postFrame]).
 */
internal suspend fun <T> blockingOn(call: Call, read: () -> T): T =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        cont.resumeWith(runCatching(read))
    }

/**
 * Executes this call and reads its response's body to the end, blocking:
 * the response, with that body held in memory and the connection given
 * back. The network's whole part of an exchange as one step for
 * [blockingOn], for a caller whose handling of the reply cannot run inside
 * a blocking step (it suspends): reading the returned response touches no
 * socket, so nothing is left for a cancel to miss. The caller closes the
 * returned response as it would any other.
 */
internal fun Call.executeToEnd(): Response = execute().use { response ->
    val body = response.body
    response.newBuilder().body(body.bytes().toResponseBody(body.contentType())).build()
}
