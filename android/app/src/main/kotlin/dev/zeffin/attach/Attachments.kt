// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.attach

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import dev.zeffin.link.Attachment
import dev.zeffin.link.AttachmentKind
import dev.zeffin.link.ChatIn
import dev.zeffin.relay.NetError
import dev.zeffin.relay.describeNetworkError
import dev.zeffin.link.FlipperFrame
import dev.zeffin.core.ServerHttp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A file ready to upload: its bytes, after a photo was reduced; [chars]: the length of a long paste made a text file. */
class Prepared(val name: String, val mediaType: String, val bytes: ByteArray, val width: Int?, val height: Int?, val chars: Int? = null) {
    val isImage get() = mediaType.startsWith("image/")
}

sealed class AttachError {
    data object TooBig : AttachError()
    data object Unreadable : AttachError()
    data class Server(val code: String) : AttachError()
    data class Net(val error: NetError) : AttachError()
}

/** Where attachments are read (IO) and decoded, scaled and compressed (Default): never the caller's thread. Tests swap them to see it. */
internal object AttachDispatchers {
    @Volatile var io: CoroutineDispatcher = Dispatchers.IO
    @Volatile var work: CoroutineDispatcher = Dispatchers.Default
}

/**
 * Photos are reduced to [MAX_EDGE] px on the long edge and sent as JPEG; small PNG, GIF and WebP images (Flipper
 * screens, screenshots) go as they are. Files over 10 MB are refused here, before any upload.
 */
object ImageScaler {
    const val MAX_EDGE = 2000
    const val MAX_BYTES = 10L * 1024 * 1024
    private const val KEEP_BYTES = 3L * 1024 * 1024
    private val mutex = Mutex()  // one photo in memory at a time

    fun nameAndSize(resolver: ContentResolver, uri: Uri): Pair<String, Long?> {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: "file"
                val size = if (c.isNull(1)) null else c.getLong(1)
                return name to size
            }
        }
        return (uri.lastPathSegment?.substringAfterLast('/') ?: "file") to null
    }

    /** The picked file's name, read off the main thread. */
    suspend fun displayName(resolver: ContentResolver, uri: Uri): String =
        withContext(AttachDispatchers.io) { nameAndSize(resolver, uri).first }

    /** Main-safe: the file is read on IO, and decoded, scaled and compressed on Default. */
    suspend fun prepare(resolver: ContentResolver, uri: Uri): Result<Prepared> = mutex.withLock {
        runCatching {
            val (name, type, bytes) = withContext(AttachDispatchers.io) {
                val (name, size) = nameAndSize(resolver, uri)
                val type = resolver.getType(uri) ?: "application/octet-stream"
                if (size != null && size > MAX_BYTES && !type.startsWith("image/")) throw TooBig()
                Triple(name, type, resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Unreadable())
            }
            withContext(AttachDispatchers.work) { prepare(name, type, bytes) }
        }
    }

    /** The bytes of a picked file or photo, made ready for Claude. */
    fun prepare(name: String, type: String, bytes: ByteArray): Prepared {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) {  // not an image Android can read: sent as a file
            if (bytes.size > MAX_BYTES) throw TooBig()
            return Prepared(name, if (type.startsWith("image/")) "application/octet-stream" else type, bytes, null, null)
        }
        val mime = bounds.outMimeType ?: type
        val rotation = runCatching {
            when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
        val keep = mime in setOf("image/png", "image/gif", "image/webp") && maxOf(w, h) <= MAX_EDGE && bytes.size <= KEEP_BYTES
        if (keep && rotation == 0) return Prepared(name, mime, bytes, w, h)
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw Unreadable()
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply { if (scale < 1f) postScale(scale, scale); if (rotation != 0) postRotate(rotation.toFloat()) }
        val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (out !== decoded) decoded.recycle()
        val buffer = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 85, buffer)
        val result = Prepared(name.substringBeforeLast('.', name) + ".jpg", "image/jpeg", buffer.toByteArray(), out.width, out.height)
        out.recycle()
        return result
    }

    /** The Flipper screen shown in the app, as a PNG at 4x (512x256 or turned), in the screen's colors. */
    fun flipperScreen(data: ByteArray, orientation: Int): Prepared? {
        val pixels = FlipperFrame.decode(data, orientation) ?: return null
        val small = Bitmap.createBitmap(pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        small.setPixels(IntArray(pixels.dark.size) { if (pixels.dark[it]) FlipperFrame.INK else FlipperFrame.PAPER }, 0, pixels.width, 0, 0, pixels.width, pixels.height)
        val big = Bitmap.createScaledBitmap(small, pixels.width * 4, pixels.height * 4, false)
        val buffer = ByteArrayOutputStream()
        big.compress(Bitmap.CompressFormat.PNG, 100, buffer)
        small.recycle(); big.recycle()
        return Prepared("flipper-screen.png", "image/png", buffer.toByteArray(), pixels.width * 4, pixels.height * 4)
    }

    class TooBig : Exception()
    class Unreadable : Exception()
}

/** POST /app/upload with the app token, reporting progress (docs/protocol/app/). */
class Uploader(private val http: ServerHttp) {
    suspend fun upload(base: HttpUrl, token: String, conversation: String, item: Prepared, onProgress: (Float) -> Unit): Result<Attachment> {
        val url = base.newBuilder().encodedPath("/app/upload").addQueryParameter("conversation", conversation)
            .addQueryParameter("name", item.name).build()
        val body = object : RequestBody() {
            override fun contentType(): MediaType = item.mediaType.toMediaType()
            override fun contentLength() = item.bytes.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                var sent = 0
                while (sent < item.bytes.size) {
                    val n = minOf(CHUNK, item.bytes.size - sent)
                    sink.write(item.bytes, sent, n)
                    sent += n
                    onProgress(sent.toFloat() / item.bytes.size)
                }
            }
        }
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").post(body).build()
        return runCatching {
            val response = http.newCall(ServerHttp.Use.TRANSFER, request).await()
            response.use { r ->
                val text = r.body.string()
                if (r.code != 201) {
                    val code = runCatching { Json.parseToJsonElement(text).jsonObject["error"]?.toString()?.trim('"') }.getOrNull()
                    throw ServerError(code ?: "http_${r.code}")
                }
                ChatIn.attachment(Json.parseToJsonElement(text).jsonObject) ?: throw ServerError("bad_answer")
            }
        }
    }

    class ServerError(val code: String) : Exception(code)

    companion object {
        private const val CHUNK = 64 * 1024
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
    cont.invokeOnCancellation { cancel() }
}

/** One file of the tray above the composer. */
data class Upload(
    val localId: Int,
    val name: String,
    val kind: AttachmentKind,
    val bytes: Long,
    val preview: Prepared?,
    val progress: Float? = 0f,
    val result: Attachment? = null,
    val error: AttachError? = null,
    /** A long paste made a text file: its length, which the chip shows instead of the size. */
    val chars: Int? = null,
) {
    val done get() = result != null
    val busy get() = result == null && error == null
}

/**
 * The files attached to the next message: each one is prepared and uploaded as soon as it is chosen, so the
 * message goes at once when sent. Limits as on the server: 10 per message, 7 MB of images together.
 */
class AttachTray(
    private val scope: CoroutineScope,
    private val upload: suspend (conversation: String, item: Prepared, progress: (Float) -> Unit) -> Result<Attachment>,
) {
    private val _items = MutableStateFlow<List<Upload>>(emptyList())
    val items: StateFlow<List<Upload>> = _items.asStateFlow()
    private val jobs = mutableMapOf<Int, Job>()
    private val prepared = mutableMapOf<Int, Pair<String, Prepared>>()
    private var next = 1

    val ids: List<String> get() = _items.value.mapNotNull { it.result?.id }
    val uploading: Boolean get() = _items.value.any { it.busy }

    /** Adds a prepared file for [conversation]; false when the limits are reached. */
    fun add(conversation: String, item: Prepared): Boolean {
        val now = _items.value
        val images = now.filter { it.kind == AttachmentKind.IMAGE }.sumOf { it.bytes } + if (item.isImage) item.bytes.size else 0
        if (now.size >= MAX_PER_MESSAGE || images > MAX_IMAGE_BYTES) return false
        val id = next++
        val kind = if (item.isImage) AttachmentKind.IMAGE else AttachmentKind.FILE
        _items.update { it + Upload(id, item.name, kind, item.bytes.size.toLong(), item.takeIf { p -> p.isImage }, chars = item.chars) }
        prepared[id] = conversation to item
        start(id)
        return true
    }

    fun addError(name: String, error: AttachError) {
        val id = next++
        _items.update { it + Upload(id, name, AttachmentKind.FILE, 0, null, progress = null, error = error) }
    }

    fun retry(localId: Int) {
        if (prepared[localId] == null) return remove(localId)
        _items.update { list -> list.map { if (it.localId == localId) it.copy(error = null, progress = 0f) else it } }
        start(localId)
    }

    fun remove(localId: Int) {
        jobs.remove(localId)?.cancel()
        prepared.remove(localId)
        _items.update { list -> list.filter { it.localId != localId } }
    }

    /** Another conversation was opened: files uploaded for an earlier one cannot go with its messages. */
    fun keepOnly(conversation: String?) {
        prepared.filterValues { it.first != conversation }.keys.toList().forEach(::remove)
    }

    /** After the message went: the tray empties. */
    fun clear() {
        jobs.values.forEach { it.cancel() }
        jobs.clear(); prepared.clear()
        _items.value = emptyList()
    }

    private fun start(id: Int) {
        val (conversation, item) = prepared[id] ?: return
        jobs[id] = scope.launch {
            val result = upload(conversation, item) { p -> _items.update { list -> list.map { if (it.localId == id) it.copy(progress = p) else it } } }
            _items.update { list ->
                list.map {
                    if (it.localId != id) it else result.fold(
                        onSuccess = { a -> it.copy(result = a, progress = null) },
                        onFailure = { e -> it.copy(progress = null, error = when (e) {
                            is Uploader.ServerError -> AttachError.Server(e.code)
                            is ImageScaler.TooBig -> AttachError.TooBig
                            is IOException -> AttachError.Net(describeNetworkError(e))
                            else -> AttachError.Unreadable
                        }) })
                }
            }
        }
    }

    companion object {
        const val MAX_PER_MESSAGE = 10
        const val MAX_IMAGE_BYTES = 7L * 1024 * 1024
    }
}
