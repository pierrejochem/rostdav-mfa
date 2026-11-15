package app.rustmfa.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.rustmfa.android.R
import app.rustmfa.android.ui.design.BannerKind
import app.rustmfa.android.ui.design.BrandButton
import app.rustmfa.android.ui.design.ButtonKind
import app.rustmfa.android.ui.design.StatusBanner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Asks for the camera at the moment of scanning; a denial leaves the manual entry usable. */
@Composable
fun ScanScreen(onText: (String) -> Unit, onManual: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
            denied = !it
        }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (granted) {
        Box(Modifier.fillMaxSize()) {
            CameraPreview(onText)
            Text(
                stringResource(R.string.scan_hint),
                modifier = Modifier.align(Alignment.TopCenter).padding(24.dp),
                color = androidx.compose.ui.graphics.Color.White,
            )
            BrandButton(stringResource(R.string.back), onClick = onBack, kind = ButtonKind.Tonal, modifier = Modifier.align(Alignment.BottomStart).padding(16.dp))
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            if (denied) StatusBanner(BannerKind.Error, stringResource(R.string.scan_permission_denied))
            BrandButton(stringResource(R.string.scan_button), onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth())
            BrandButton(stringResource(R.string.manual_button), onClick = onManual, kind = ButtonKind.Outlined, modifier = Modifier.fillMaxWidth())
            BrandButton(stringResource(R.string.back), onClick = onBack, kind = ButtonKind.Outlined, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CameraPreview(onText: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val done = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener(
                {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    val analysis =
                        ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { a ->
                                a.setAnalyzer(executor) { image ->
                                    val text = QrDecoder.decode(image)
                                    if (text != null && done.compareAndSet(false, true)) {
                                        ContextCompat.getMainExecutor(ctx).execute { onText(text) }
                                    }
                                }
                            }
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                },
                ContextCompat.getMainExecutor(context),
            )
            view
        },
    )
}

/** QR only, from the luminance (Y) plane; no Google ML Kit. */
internal object QrDecoder {
    private val reader =
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }

    fun decode(image: ImageProxy): String? =
        image.use {
            if (it.format != ImageFormat.YUV_420_888) return null
            val plane = it.planes[0]
            val w = it.width
            val h = it.height
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val y = ByteArray(w * h)
            for (row in 0 until h) {
                buf.position(row * rowStride)
                buf.get(y, row * w, w)
            }
            try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false)))).text
            } catch (_: Exception) {
                null
            } finally {
                reader.reset()
            }
        }
}
