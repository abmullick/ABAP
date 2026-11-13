package com.abap.privatep2p

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun CameraQrScanner(
    onQrCode: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val latestOnQrCode by rememberUpdatedState(onQrCode)
    var scannerError by remember { mutableStateOf<String?>(null) }
    val delivered = remember { AtomicBoolean(false) }

    DisposableEffect(lifecycleOwner, previewView) {
        val executor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        val listener = Runnable {
            try {
                provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(executor) { image ->
                    try {
                        val decoded = decodeQrFromLuma(image)
                        if (decoded != null && delivered.compareAndSet(false, true)) {
                            mainExecutor.execute { latestOnQrCode(decoded) }
                        }
                    } catch (_: Exception) {
                        // Most camera frames do not contain a complete QR code.
                    } finally {
                        image.close()
                    }
                }

                provider?.unbindAll()
                provider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (error: Exception) {
                scannerError = error.message ?: "Camera could not be started"
            }
        }
        providerFuture.addListener(listener, mainExecutor)

        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Scan the other device's ABAP Private QR code", style = MaterialTheme.typography.titleMedium)
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxWidth().height(420.dp)
        )
        scannerError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Text(
            "Keep the entire QR code inside the camera frame. Scanning only imports signed pairing data; it does not connect to a server.",
            style = MaterialTheme.typography.bodySmall
        )
        Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel scan")
        }
    }
}

private fun decodeQrFromLuma(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val width = image.width
    val height = image.height
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val buffer = plane.buffer.duplicate()
    val start = buffer.position()
    val luminance = ByteArray(width * height)

    for (row in 0 until height) {
        val rowStart = start + row * rowStride
        val outputStart = row * width
        for (column in 0 until width) {
            luminance[outputStart + column] = buffer.get(rowStart + column * pixelStride)
        }
    }

    val source = PlanarYUVLuminanceSource(
        luminance, width, height, 0, 0, width, height, false
    )
    val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)))
    }
    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } finally {
        reader.reset()
    }
}
