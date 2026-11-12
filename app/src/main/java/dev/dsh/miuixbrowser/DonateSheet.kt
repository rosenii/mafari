package dev.dsh.miuixbrowser

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 捐赠入口的小字（设置页 / 关于页的入口行与弹窗共用） */
const val DONATE_SUMMARY = "支持开发者，给牛马来杯核动力coffee ☕!"

/** 捐赠弹窗：赞赏码 + 保存到相册（设置页与关于页共用）。 */
@Composable
fun DonateSheet(show: Boolean, backdrop: Backdrop?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    AppBottomSheet(
        show = show,
        title = "捐赠",
        backdrop = backdrop,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = DONATE_SUMMARY,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Image(
                painter = painterResource(R.drawable.donate_qr),
                contentDescription = "赞赏码",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "保存后打开微信「扫一扫」，点右上角相册选择此图即可赞赏",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            AppDialogButton(
                onClick = {
                    if (saving) return@AppDialogButton
                    saving = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { saveDonateQr(context) }
                        saving = false
                        Toast.makeText(
                            context,
                            if (ok) "已保存到相册（Pictures/Mafari）。打开微信「扫一扫」→ 相册，选择此图即可赞赏"
                            else "保存失败，请截图保存",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                role = GlassDialogButtonRole.Primary,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = if (saving) "保存中…" else "保存到相册", style = MiuixTheme.textStyles.button)
            }
        }
    }
}

/** 把赞赏码存进系统相册（Pictures/Mafari）。minSdk 33：走 MediaStore，不需要存储权限。 */
private fun saveDonateQr(context: Context): Boolean = runCatching {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "mafari_donate_qr.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Mafari")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("insert failed")
    try {
        resolver.openOutputStream(uri)!!.use { out ->
            context.resources.openRawResource(R.drawable.donate_qr).use { it.copyTo(out) }
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    } catch (t: Throwable) {
        runCatching { resolver.delete(uri, null, null) }
        throw t
    }
}.onFailure { Diag.log("donate: save qr failed: $it") }.isSuccess
