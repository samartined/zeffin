// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.attach

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.zeffin.core.ServerHttp
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Request

/** Where the chat's images come from: the server (the app), or drawn fakes (the screenshot tests). */
interface ThumbnailSource {
    suspend fun load(conversation: String, id: String, maxPx: Int): ImageBitmap?
}

/**
 * Attachment images from GET /app/attachments/<conversation>/<id>, with the app token, decoded small and kept
 * in memory (8 MB); the HTTP cache keeps the files themselves.
 */
class Thumbnails(private val http: ServerHttp, private val server: suspend () -> Pair<HttpUrl, String>?) : ThumbnailSource {
    private val cache = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    override suspend fun load(conversation: String, id: String, maxPx: Int): ImageBitmap? {
        val key = "$conversation/$id/$maxPx"
        cache.get(key)?.let { return it }
        val (base, token) = server() ?: return null
        val url = base.newBuilder().encodedPath("/app/attachments/$conversation/$id").build()
        val bytes = withContext(AttachDispatchers.io) {
            runCatching {
                http.newCall(ServerHttp.Use.TRANSFER, Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { r ->
                    if (r.isSuccessful) r.body.bytes() else null
                }
            }.getOrNull()
        } ?: return null
        return decodeOffMain(bytes, maxPx)?.also { cache.put(key, it) }
    }

    companion object {
        /** [decode] on Default, so a caller on the main thread (a composition's produceState) never decodes there. */
        suspend fun decodeOffMain(bytes: ByteArray, maxPx: Int): ImageBitmap? = withContext(AttachDispatchers.work) { decode(bytes, maxPx) }

        /** Decodes at most about [maxPx] on the long edge, so a 2000 px photo costs little memory. */
        fun decode(bytes: ByteArray, maxPx: Int): ImageBitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.asImageBitmap()
        }
    }
}
