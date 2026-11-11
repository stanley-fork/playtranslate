package com.playtranslate.translation

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [executeToEnd], the blocking step an Ask answer's exchange is
 * ([blockingOn]): the reply it hands back is the reply that came, and the
 * live one is never left open. The cancellation it exists for is tested
 * where it is used, against each service that answers
 * (OpenAiBackendCompleteTest, GeminiBackendCompleteTest, PtCloudAskTest).
 */
class BlockingCallTest {

    /** A call answered [code] with [bytes] as its body, declared
     *  [contentType] and [contentLength] long; [closed] turns true when the
     *  live body is closed. */
    private fun call(
        code: Int,
        bytes: ByteArray,
        contentType: String,
        contentLength: Long,
        closed: AtomicBoolean,
    ): Call = OkHttpClient.Builder().addInterceptor { chain ->
        val live = object : ForwardingSource(Buffer().write(bytes)) {
            override fun close() {
                closed.set(true)
                super.close()
            }
        }
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .header("Retry-After", "7")
            .body(live.buffer().asResponseBody(contentType.toMediaType(), contentLength))
            .build()
    }.build().newCall(Request.Builder().url("https://example.invalid/").build())

    @Test fun `executeToEnd hands back the reply as it came, its body in memory, with the live one read and closed`() {
        // A charset other than the default, so a body handed back without
        // its content type would decode to other text.
        val text = "{\"text\":\"café\"}"
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        val closed = AtomicBoolean()

        val reply = call(429, bytes, "application/json; charset=ISO-8859-1", bytes.size.toLong(), closed).executeToEnd()

        assertTrue("the live body was closed inside the step", closed.get())
        assertEquals(429, reply.code)
        assertEquals("7", reply.header("Retry-After"))
        assertEquals(text, reply.body.string())
    }

    @Test fun `executeToEnd closes a reply it cannot hold`() {
        // A body declared longer than an array can be is refused before a
        // byte of it is read, so nothing but the step itself closes it.
        val closed = AtomicBoolean()

        val thrown = runCatching {
            call(200, ByteArray(0), "application/json", Int.MAX_VALUE + 1L, closed).executeToEnd()
        }.exceptionOrNull()

        assertTrue("the refusal reaches the caller: $thrown", thrown is IOException)
        assertTrue("the live reply was closed all the same", closed.get())
    }
}
