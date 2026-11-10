// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import dev.zeffin.attach.AttachDispatchers
import dev.zeffin.attach.Thumbnails
import dev.zeffin.core.NoTunnel
import dev.zeffin.ui.screens.previewOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import dev.zeffin.attach.AttachError
import dev.zeffin.attach.AttachTray
import dev.zeffin.attach.ImageScaler
import dev.zeffin.attach.Prepared
import dev.zeffin.attach.Uploader
import dev.zeffin.link.Attachment
import dev.zeffin.link.AttachmentKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

private fun encode(w: Int, h: Int, format: Bitmap.CompressFormat, quality: Int = 90): ByteArray {
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.rgb(88, 80, 72))
    return ByteArrayOutputStream().also { bitmap.compress(format, quality, it) }.toByteArray()
}

private fun size(bytes: ByteArray) = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    .also { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }.let { it.outWidth to it.outHeight }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ImageScalerTest {
    @Test fun aBigPhotoIsReducedTo2000OnTheLongEdge() {
        val out = ImageScaler.prepare("IMG_2041.jpg", "image/jpeg", encode(4000, 3000, Bitmap.CompressFormat.JPEG))
        assertEquals("image/jpeg", out.mediaType)
        assertEquals(2000 to 1500, out.width to out.height)
        assertEquals(2000 to 1500, size(out.bytes))
    }

    @Test fun aSmallPngGoesAsItIs() {
        val png = encode(512, 256, Bitmap.CompressFormat.PNG)
        val out = ImageScaler.prepare("screen.png", "image/png", png)
        assertArrayEquals(png, out.bytes)
        assertEquals("image/png", out.mediaType)
        assertEquals("screen.png", out.name)
    }

    @Test fun aPhotoTakenSidewaysIsTurned() {
        val file = File.createTempFile("exif", ".jpg")
        file.writeBytes(encode(400, 300, Bitmap.CompressFormat.JPEG))
        ExifInterface(file.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()) }
            .saveAttributes()
        val out = ImageScaler.prepare("IMG_1.jpg", "image/jpeg", file.readBytes())
        file.delete()
        assertEquals(300 to 400, out.width to out.height)
        assertEquals(300 to 400, size(out.bytes))
    }

    @Test fun aPngIsRenamedWhenItBecomesJpeg() {
        val out = ImageScaler.prepare("big.png", "image/png", encode(3000, 1000, Bitmap.CompressFormat.PNG))
        assertEquals("big.jpg", out.name)
        assertEquals(2000 to 667, out.width to out.height)
    }

    @Test fun otherFilesGoAsTheyAre() {
        val text = "Filetype: IR signals file\n".toByteArray()
        val out = ImageScaler.prepare("tv.ir", "application/octet-stream", text)
        assertArrayEquals(text, out.bytes)
        assertFalse(out.isImage)
        assertNull(out.width)
    }

    @Test fun aFileOver10MbIsRefused() {
        val result = runCatching { ImageScaler.prepare("big.bin", "application/zip", ByteArray(10 * 1024 * 1024 + 1)) }
        assertTrue(result.exceptionOrNull() is ImageScaler.TooBig)
    }

    @Test fun aPickedPhotoIsReadAndScaledOffTheCallersThread() = withRecordingDispatchers { io, work ->
        val file = File.createTempFile("picked", ".jpg").apply { writeBytes(encode(4000, 3000, Bitmap.CompressFormat.JPEG)) }
        PhotoProvider.file = file
        Robolectric.setupContentProvider(PhotoProvider::class.java, PhotoProvider.AUTHORITY)
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val uri = Uri.parse("content://${PhotoProvider.AUTHORITY}/IMG_2041.jpg")
        assertTrue(Looper.getMainLooper().isCurrentThread)  // called from the main thread, like the conversation screen
        val out = runBlocking { ImageScaler.prepare(resolver, uri) }.getOrThrow()
        assertEquals("IMG_2041.jpg" to "image/jpeg", out.name to out.mediaType)
        assertEquals(2000 to 1500, out.width to out.height)
        assertEquals("IMG_2041.jpg", runBlocking { ImageScaler.displayName(resolver, uri) })
        file.delete()
        // Every read of the provider ran on the IO dispatcher, and the decoding, scaling and compressing went to the work one.
        assertEquals(setOf(io.name), PhotoProvider.threads.toSet())
        assertTrue(work.dispatched.get() > 0)
    }

    @Test fun thumbnailsAndTrayPreviewsDecodeOffTheCallersThread() = withRecordingDispatchers { _, work ->
        val photo = encode(2000, 1500, Bitmap.CompressFormat.JPEG)
        val viewer = runBlocking { Thumbnails.decodeOffMain(photo, 2000) }!!
        assertEquals(2000 to 1500, viewer.width to viewer.height)
        val afterViewer = work.dispatched.get()
        assertTrue(afterViewer > 0)
        val chip = runBlocking { previewOf(photo) }!!
        assertTrue(chip.width < 2000 / 4)  // decoded small, for the chip
        assertTrue(work.dispatched.get() > afterViewer)
    }

    @Test fun withoutTheTunnelNoThumbnailIsAskedFor() {
        val server = MockWebServer().tls().apply { start() }
        try {
            val token = "a".repeat(43)
            val load = { http: dev.zeffin.core.ServerHttp ->
                runBlocking { Thumbnails(http) { server.base() to token }.load("7f1d3c3e", "0123456789abcdef0123456789abcdef", 64) }
            }
            assertNull(load(testHttp(FakeNetwork(NoTunnel.Why.NO_VPN))))
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse.Builder().body(okio.Buffer().write(encode(64, 64, Bitmap.CompressFormat.PNG))).build())
            assertEquals(64, load(testHttp())?.width)
            assertEquals("Bearer $token", server.takeRequest().headers["Authorization"])
        } finally {
            server.close()
        }
    }

    @Test fun theFlipperScreenBecomesA512x256Png() {
        val out = ImageScaler.flipperScreen(Fixtures.frameData, 0)!!
        assertEquals("flipper-screen.png", out.name)
        assertEquals(512 to 256, size(out.bytes))
        val turned = ImageScaler.flipperScreen(Fixtures.frameData, 2)!!
        assertEquals(256 to 512, turned.width to turned.height)
    }
}

class UploaderTest {
    private lateinit var server: MockWebServer
    private val token = "a".repeat(43)
    private val item = Prepared("living room.ir", "application/octet-stream", ByteArray(200_000) { (it % 251).toByte() }, null, null)

    @Before fun setUp() { server = MockWebServer().tls(); server.start() }
    @After fun tearDown() = server.close()

    @Test fun uploadsWithTheTokenAndReportsProgress() = runBlocking {
        server.enqueue(MockResponse.Builder().code(201).body("""{"id":"0123456789abcdef0123456789abcdef","name":"living room.ir",
            "kind":"file","media_type":"application/octet-stream","bytes":200000,"width":null,"height":null}""").build())
        val progress = mutableListOf<Float>()
        val result = Uploader(testHttp()).upload(server.base(), token, "7f1d3c3e", item) { progress += it }
        assertEquals(Attachment("0123456789abcdef0123456789abcdef", "living room.ir", AttachmentKind.FILE, "application/octet-stream",
            200_000, null, null), result.getOrThrow())
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/app/upload?conversation=7f1d3c3e&name=living%20room.ir", request.target)
        assertEquals("Bearer $token", request.headers["Authorization"])
        assertEquals("application/octet-stream", request.headers["Content-Type"])
        assertArrayEquals(item.bytes, request.body!!.toByteArray())
        assertEquals(1f, progress.last())
        assertTrue(progress.size >= 3)
    }

    @Test fun aRefusalCarriesTheServersCode() = runBlocking {
        server.enqueue(MockResponse.Builder().code(413).body("""{"error":"too_large","message":"over 10 MB"}""").build())
        val error = Uploader(testHttp()).upload(server.base(), token, "7f1d3c3e", item) {}.exceptionOrNull()
        assertEquals("too_large", (error as Uploader.ServerError).code)
    }

    @Test fun withoutTheTunnelNothingIsUploaded() = runBlocking {
        val error = Uploader(testHttp(FakeNetwork(NoTunnel.Why.NO_VPN))).upload(server.base(), token, "7f1d3c3e", item) {}.exceptionOrNull()
        assertTrue(error is NoTunnel)
        assertEquals(0, server.requestCount)
    }

    @Test fun anAnswerWithoutJsonStillFails() = runBlocking {
        server.enqueue(MockResponse.Builder().code(502).body("Bad gateway").build())
        val error = Uploader(testHttp()).upload(server.base(), token, "7f1d3c3e", item) {}.exceptionOrNull()
        assertEquals("http_502", (error as Uploader.ServerError).code)
    }
}

class AttachTrayTest {
    private val photo = Prepared("IMG_2041.jpg", "image/jpeg", ByteArray(1000), 2000, 1500)
    private val file = Prepared("notes.pdf", "application/pdf", ByteArray(500), null, null)
    private fun attachment(p: Prepared, id: String) =
        Attachment(id, p.name, if (p.isImage) AttachmentKind.IMAGE else AttachmentKind.FILE, p.mediaType, p.bytes.size.toLong(), p.width, p.height)

    @Test fun uploadsAtOnceAndGivesTheIdsInOrder() {
        val scope = TestScope(StandardTestDispatcher())
        val tray = AttachTray(scope) { conv, p, progress -> assertEquals("c1", conv); progress(1f); Result.success(attachment(p, p.name + "-id")) }
        tray.add("c1", photo)
        tray.add("c1", file)
        assertTrue(tray.uploading)
        scope.advanceUntilIdle()
        assertFalse(tray.uploading)
        assertEquals(listOf("IMG_2041.jpg-id", "notes.pdf-id"), tray.ids)
        tray.clear()
        assertTrue(tray.items.value.isEmpty())
    }

    @Test fun aFailedUploadCanBeRetried() {
        val scope = TestScope(StandardTestDispatcher())
        var fail = true
        val tray = AttachTray(scope) { _, p, _ -> if (fail) Result.failure(IOException("reset")) else Result.success(attachment(p, "x")) }
        tray.add("c1", file)
        scope.advanceUntilIdle()
        val failed = tray.items.value.single()
        assertTrue(failed.error is AttachError.Net)
        assertTrue(tray.ids.isEmpty())
        fail = false
        tray.retry(failed.localId)
        scope.advanceUntilIdle()
        assertEquals(listOf("x"), tray.ids)
    }

    @Test fun aServerRefusalKeepsItsCode() {
        val scope = TestScope(StandardTestDispatcher())
        val tray = AttachTray(scope) { _, _, _ -> Result.failure(Uploader.ServerError("bad_type")) }
        tray.add("c1", file)
        scope.advanceUntilIdle()
        assertEquals(AttachError.Server("bad_type"), tray.items.value.single().error)
    }

    @Test fun limitsAsOnTheServer() {
        val scope = TestScope(StandardTestDispatcher())
        val waiting = CompletableDeferred<Unit>()
        val tray = AttachTray(scope) { _, p, _ -> waiting.await(); Result.success(attachment(p, "x")) }
        repeat(AttachTray.MAX_PER_MESSAGE) { assertTrue(tray.add("c1", file)) }
        assertFalse(tray.add("c1", file))
        val other = AttachTray(scope) { _, p, _ -> Result.success(attachment(p, "x")) }
        val big = Prepared("big.jpg", "image/jpeg", ByteArray(4 * 1024 * 1024), 2000, 1500)
        assertTrue(other.add("c1", big))
        assertFalse(other.add("c1", big))  // 8 MB of images: over 7 MB
        assertTrue(other.add("c1", file))  // other files do not count
    }

    @Test fun openingAnotherConversationDropsTheFiles() {
        val scope = TestScope(StandardTestDispatcher())
        val tray = AttachTray(scope) { _, p, _ -> Result.success(attachment(p, p.name)) }
        tray.add("c1", file)
        tray.add("c2", photo)
        scope.advanceUntilIdle()
        tray.keepOnly("c2")
        assertEquals(listOf("IMG_2041.jpg"), tray.ids)
    }

    @Test fun removingCancelsTheUpload() {
        val scope = TestScope(StandardTestDispatcher())
        val tray = AttachTray(scope) { _, p, _ -> CompletableDeferred<Unit>().await(); Result.success(attachment(p, "x")) }
        tray.add("c1", file)
        scope.advanceUntilIdle()
        tray.remove(tray.items.value.single().localId)
        scope.advanceUntilIdle()
        assertTrue(tray.items.value.isEmpty())
        assertFalse(tray.uploading)
    }
}

/** A dispatcher on a thread of its own that counts what it is given: shows where attachment work runs. */
private class Recording(val name: String) : CoroutineDispatcher() {
    val dispatched = AtomicInteger()
    val pool: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, name) }
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatched.incrementAndGet()
        pool.execute(block)
    }
}

private fun withRecordingDispatchers(test: (io: Recording, work: Recording) -> Unit) {
    val io = Recording("attach-io")
    val work = Recording("attach-work")
    AttachDispatchers.io = io
    AttachDispatchers.work = work
    try {
        test(io, work)
    } finally {
        AttachDispatchers.io = Dispatchers.IO
        AttachDispatchers.work = Dispatchers.Default
        io.pool.shutdown()
        work.pool.shutdown()
    }
}

/** A picked photo behind a content provider, like the photo picker's; it records the thread of each call. */
class PhotoProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "dev.zeffin.test.photos"
        val threads = ConcurrentLinkedQueue<String>()
        @Volatile var file: File? = null
    }

    private fun record() { threads += Thread.currentThread().name.substringBefore(" @") }  // without the coroutine debug suffix
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        record()
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any?>(uri.lastPathSegment, file!!.length()))
        }
    }
    override fun getType(uri: Uri): String { record(); return "image/jpeg" }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        record()
        return ParcelFileDescriptor.open(file!!, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
