package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.browser.ScanHelper
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内扫一扫：CameraX 取景 + ML Kit 内置版条码识别（识别模型打包在 APK 里）。
 * 不依赖 Google Play 服务，也不需要联网，国内设备可直接使用；两种内核（WebView / Gecko）共用。
 *
 * 结果按 ZXing 的约定回传：RESULT_OK + extra [ScanHelper.EXTRA_RESULT]（SCAN_RESULT），
 * 由 BrowserScreen 的 scanLauncher 接收并打开。
 */
class ScanActivity : ComponentActivity() {

    private val delivered = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val barStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            // 相机页固定深色，不随应用主题变化
            val controller = remember { ThemeController(ColorSchemeMode.Dark) }
            MiuixTheme(controller = controller) {
                ScanScreen(onResult = ::deliver, onClose = { finish() })
            }
        }
    }

    /** 只回传一次：相机连续出帧时，识别回调可能重复触发 */
    private fun deliver(text: String) {
        if (!delivered.compareAndSet(false, true)) return
        setResult(RESULT_OK, Intent().putExtra(ScanHelper.EXTRA_RESULT, text))
        finish()
    }
}

@Composable
private fun ScanScreen(onResult: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var askedOnce by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        askedOnce = true
    }
    LaunchedEffect(Unit) { if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA) }

    // 相册里选图识别：相机权限被拒时也能用；识别器用到时才创建，打开页面本身不初始化 ML Kit
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val scanner = createScanner()
        if (scanner == null) {
            toast(context, "扫码组件初始化失败")
            return@rememberLauncherForActivityResult
        }
        runCatching {
            val image = InputImage.fromFilePath(context, uri)
            scanner.process(image)
                .addOnSuccessListener { codes ->
                    val text = codes.firstNotNullOfOrNull { it.rawValue?.takeIf { v -> v.isNotBlank() } }
                    if (text != null) onResult(text) else toast(context, "未识别到二维码")
                }
                .addOnFailureListener { toast(context, "识别失败") }
                .addOnCompleteListener { runCatching { scanner.close() } }
        }.onFailure {
            runCatching { scanner.close() }
            toast(context, "无法读取这张图片")
        }
    }

    var torchOn by remember { mutableStateOf(false) }
    var hasTorch by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraLayer(
                torchOn = torchOn,
                onCameraBound = { hasTorch = it },
                onCode = onResult,
            )
            ScanOverlay()
        } else {
            PermissionHint(
                permanentlyDenied = askedOnce,
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    }
                },
            )
        }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        ) {
            Pill("关闭", onClick = onClose)
            Text(
                "扫一扫",
                color = Color.White,
                fontSize = 17.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            // 占位保持标题居中
            Box(Modifier.padding(horizontal = 24.dp))
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (granted) {
                Text("将二维码 / 条码放入框内", color = Color(0xCCFFFFFF), fontSize = 14.sp, textAlign = TextAlign.Center)
            }
            Row(
                Modifier.padding(top = 20.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp),
            ) {
                Pill("相册", onClick = { pickImage.launch("image/*") })
                if (granted && hasTorch) {
                    Pill(if (torchOn) "关闭手电筒" else "手电筒", onClick = { torchOn = !torchOn })
                }
            }
        }
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x66000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(label, color = Color.White, fontSize = 15.sp)
    }
}

@Composable
private fun PermissionHint(permanentlyDenied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("扫码需要使用相机", color = Color.White, fontSize = 17.sp)
        Text(
            "你也可以点下方「相册」，识别相册里的二维码图片。",
            color = Color(0xB3FFFFFF),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        Pill(if (permanentlyDenied) "去系统设置开启相机权限" else "授予相机权限", onClick = if (permanentlyDenied) onOpenSettings else onRequest)
    }
}

/** 相机预览 + 逐帧识别；离开页面时释放相机与识别器 */
@Composable
private fun CameraLayer(torchOn: Boolean, onCameraBound: (hasTorch: Boolean) -> Unit, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = context as androidx.lifecycle.LifecycleOwner
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val main = remember { Handler(Looper.getMainLooper()) }
    val latestOnCode by androidx.compose.runtime.rememberUpdatedState(onCode)

    DisposableEffect(lifecycleOwner) {
        val scanner = createScanner()
        var provider: ProcessCameraProvider? = null
        if (scanner == null) {
            toast(context, "扫码组件初始化失败")
        } else {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching {
                    val p = future.get()
                    provider = p
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(executor) { proxy -> analyze(scanner, proxy) { text -> main.post { latestOnCode(text) } } } }
                    p.unbindAll()
                    val bound = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    camera = bound
                    onCameraBound(bound.cameraInfo.hasFlashUnit())
                }.onFailure { toast(context, "相机启动失败") }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose {
            runCatching { provider?.unbindAll() }
            executor.shutdown()
            runCatching { scanner?.close() }
        }
    }
    LaunchedEffect(camera, torchOn) { runCatching { camera?.cameraControl?.enableTorch(torchOn) } }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** 创建 ML Kit 识别器；初始化失败返回 null，由调用方提示（不要让扫码页因此崩溃） */
private fun createScanner(): BarcodeScanner? = runCatching { BarcodeScanning.getClient() }.getOrNull()

@OptIn(ExperimentalGetImage::class)
private fun analyze(scanner: BarcodeScanner, proxy: ImageProxy, onText: (String) -> Unit) {
    val media = proxy.image
    if (media == null) {
        proxy.close()
        return
    }
    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { codes ->
            codes.firstNotNullOfOrNull { it.rawValue?.takeIf { v -> v.isNotBlank() } }?.let(onText)
        }
        .addOnCompleteListener { proxy.close() }
}

/** 取景框：四周压暗、中间留出方框，四角括号 + 上下往复的扫描线 */
@Composable
private fun ScanOverlay() {
    val transition = rememberInfiniteTransition(label = "scan")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Reverse),
        label = "line",
    )
    Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        val side = size.minDimension * 0.68f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f - size.height * 0.04f
        val radius = CornerRadius(20.dp.toPx())
        drawRect(Color(0x99000000))
        drawRoundRect(Color.Transparent, Offset(left, top), Size(side, side), radius, blendMode = BlendMode.Clear)

        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        val len = 28.dp.toPx()
        val white = Color.White
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(white, Offset(x, y), Offset(x + dx * len, y), stroke.width, StrokeCap.Round)
            drawLine(white, Offset(x, y), Offset(x, y + dy * len), stroke.width, StrokeCap.Round)
        }
        corner(left, top, 1f, 1f)
        corner(left + side, top, -1f, 1f)
        corner(left, top + side, 1f, -1f)
        corner(left + side, top + side, -1f, -1f)

        val y = top + 12.dp.toPx() + (side - 24.dp.toPx()) * progress
        drawLine(
            Color(0xFF3B7DFF),
            Offset(left + 14.dp.toPx(), y),
            Offset(left + side - 14.dp.toPx(), y),
            2.dp.toPx(),
            StrokeCap.Round,
        )
    }
}

private fun toast(context: android.content.Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
