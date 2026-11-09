package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.playtranslate.yomitan.YomitanDataStore
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream

/**
 * What a definitions page may load and where its links go, the same for
 * every WebView that draws definitions.
 *
 * [respond] is THE network guarantee: a page gets a dictionary's own media
 * out of the app's database and nothing else, whatever its content asks for.
 */
internal object DefinitionMedia {

    private const val TAG = "DefinitionMedia"

    /**
     * The response to any request a definitions page makes. The one allowed
     * shape is `https://pt-media.internal/media/<dictId>/<path>`, served
     * from [language]'s imported dictionaries; everything else, and a media
     * path the database does not hold, gets an empty body.
     */
    fun respond(context: Context, language: String, request: WebResourceRequest?): WebResourceResponse {
        val url = request?.url ?: return blocked()
        if ("${url.scheme}://${url.host}" == YomitanContentHtml.MEDIA_ORIGIN) {
            val segments = url.pathSegments
            if (segments.size >= 3 && segments[0] == "media") {
                val dictId = segments[1]
                val path = segments.drop(2).joinToString("/")
                val blob = runBlocking { YomitanDataStore.mediaBlob(context, language, dictId, path) }
                if (blob != null) {
                    return WebResourceResponse(mimeForPath(path), null, ByteArrayInputStream(blob))
                }
            }
        }
        return blocked()
    }

    /** True for a link the page may hand to the system browser: http(s),
     *  and not the media origin (which is no site). */
    fun isExternalLink(url: Uri?): Boolean =
        url != null && (url.scheme == "http" || url.scheme == "https") &&
            "${url.scheme}://${url.host}" != YomitanContentHtml.MEDIA_ORIGIN

    fun openInBrowser(context: Context, url: Uri) {
        try {
            // NEW_TASK: some hosts are overlay windows (the lens), whose
            // context is no Activity; harmless from the Activity-hosted
            // sheets.
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            // No browser installed / activity start denied: the tap just
            // does nothing.
            Log.w(TAG, "openInBrowser failed for $url", e)
        }
    }

    private fun mimeForPath(path: String): String =
        when (path.substringAfterLast('.', "").lowercase()) {
            "avif" -> "image/avif"
            "apng" -> "image/apng"
            "bmp" -> "image/bmp"
            "gif" -> "image/gif"
            "ico", "cur" -> "image/x-icon"
            "jpg", "jpeg", "jfif", "pjpeg", "pjp" -> "image/jpeg"
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            "tif", "tiff" -> "image/tiff"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }

    private fun blocked(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
