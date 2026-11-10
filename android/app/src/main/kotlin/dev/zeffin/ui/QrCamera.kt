// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Reads QR codes with ZXing (no Google services); also inverted ones, as a dark terminal may draw them. */
object QrDecoder {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.ALSO_INVERTED to true,
    )

    fun decode(luminance: ByteArray, width: Int, height: Int, rowStride: Int = width): String? = try {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (e: NotFoundException) {
        null
    } catch (e: Exception) {
        null
    }

    internal fun fromImage(image: ImageProxy): String? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        return decode(bytes, image.width, image.height, plane.rowStride)
    }
}

/** Camera preview that calls [onText] once with the first QR it reads. */
@Composable
fun QrCamera(onText: (String) -> Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val done = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val usePreview = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { image ->
                image.use {
                    if (!done.get()) QrDecoder.fromImage(it)?.let { text ->
                        if (done.compareAndSet(false, true)) ContextCompat.getMainExecutor(context).execute { if (!onText(text)) done.set(false) }
                    }
                }
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, usePreview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { future.get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView({ preview }, Modifier.fillMaxSize())
}
