/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.browser.BrowserState
import dev.dsh.miuixbrowser.browser.ScanHelper
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.util.Diag
import android.app.Activity
import android.os.Build
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.webkit.WebChromeClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString

import kotlinx.coroutines.launch

/** 文件选择 / 拍照 / 扫码 / 权限申请的启动器集合（由 [rememberBrowserLaunchers] 创建）。 */
internal class BrowserLaunchers(
    val androidPermLauncher: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>,
    private val scan: () -> Unit,
    private val filePicker: (FileChooserChoice) -> Unit,
    private val camera: (FileChooserChoice, Boolean) -> Unit,
) {
    /** 打开应用内扫码页 */
    fun launchScan() = scan()
    /** 拉起系统文件选择器（按 acceptTypes / 多选 / 保存） */
    fun launchFilePicker(choice: FileChooserChoice) = filePicker(choice)
    /** 拍照 / 录像 */
    fun launchCamera(choice: FileChooserChoice, video: Boolean) = camera(choice, video)
}

@Composable
internal fun BrowserUiState.rememberBrowserLaunchers(
    settings: AppSettings,
    context: android.content.Context,
    state: BrowserState,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
): BrowserLaunchers {
    val tab = state.activeTab
    // Android 运行时权限申请结果
    val androidPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val done = androidPermDone
        androidPermDone = null
        done?.invoke()
    }

    // 系统文件选择器（含多选）
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val choice = fileChooserChoice
        fileChooserChoice = null
        val cb = choice?.callback ?: return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val clip = data?.clipData
            val uris = when {
                clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
            cb.onReceiveValue(uris)
        } else {
            cb.onReceiveValue(null)
        }
    }

    // 拍照 / 录像
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val choice = fileChooserChoice
        fileChooserChoice = null
        val cb = choice?.callback
        val out = cameraOutputUri
        cameraOutputUri = null
        if (result.resultCode == Activity.RESULT_OK && out != null) {
            cb?.onReceiveValue(arrayOf(out))
        } else {
            out?.let { uri ->
                runCatching {
                    context.contentResolver.update(
                        uri,
                        android.content.ContentValues().apply {
                            put(MediaStore.Images.Media.IS_PENDING, 0)
                            put(MediaStore.Video.Media.IS_PENDING, 0)
                        },
                        null,
                        null,
                    )
                }
            }
            cb?.onReceiveValue(null)
        }
    }

    // 扫一扫：应用内扫码页回传结果
    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val text = ScanHelper.extractText(result.data) ?: return@rememberLauncherForActivityResult
        when (val target = ScanHelper.resolve(text, settings.searchTemplate)) {
            is ScanHelper.Target.Open -> state.newTab(target.url)
            is ScanHelper.Target.CopyOnly -> {
                clipboard.setText(AnnotatedString(target.text))
                android.widget.Toast.makeText(context, tr("已复制扫描内容"), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 打开应用内扫码页 */
    fun launchScan() {
        val started = runCatching { scanLauncher.launch(ScanHelper.intent(context)) }.isSuccess
        if (!started) {
            android.widget.Toast.makeText(context, tr("无法打开扫一扫"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** 拉起系统文件选择器（按 acceptTypes / 多选） */
    fun launchFilePicker(choice: FileChooserChoice) {
        val params = choice.params
        // showSaveFilePicker()（File System Access API，WebView 132+）：新建文件并把 Uri 交还给网页，
        // 网页用 createWritable() 边下边写，不经 blob、不占内存；文件名用页面给的原名，不改名
        if (params.mode == WebChromeClient.FileChooserParams.MODE_SAVE) {
            val hint = params.filenameHint?.takeIf { it.isNotBlank() }
            val ext = hint?.substringAfterLast('.', "")?.lowercase().orEmpty()
            // MIME 按原文件名的扩展名取，避免系统因类型不符在文件名后再追加扩展名
            val mimeByName = ext.takeIf { it.isNotEmpty() }
                ?.let { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            val saveIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeByName ?: "application/octet-stream"
                if (hint != null) putExtra(Intent.EXTRA_TITLE, hint)
            }
            runCatching { filePickerLauncher.launch(saveIntent) }.onFailure {
                Diag.log("save picker launch failed: ${it}")
                choice.callback.onReceiveValue(null)
                fileChooserChoice = null
            }
            return
        }
        val types = params.acceptTypes
            ?.mapNotNull { it?.trim()?.takeIf { t -> t.isNotEmpty() } }
            .orEmpty()
        // 只在能确定全部类型都合法时才给系统选择器加类型过滤；任何一项解析不了就不过滤，
        // 宁可多显示文件，也不能让用户想选的文件被灰掉、点不动
        val mimes = resolvePickerMimeTypes(types)
        fun buildIntent(action: String) = Intent(action).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            if (mimes.size == 1) {
                type = mimes[0]
            } else {
                type = "*/*"
                if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
            }
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
        // 部分机型（定制文档应用）的 GET_CONTENT 不认 EXTRA_MIME_TYPES 或根本没有对应界面：
        // 依次尝试 GET_CONTENT → OPEN_DOCUMENT → 系统 chooser。不再依赖 resolveActivity
        // （Android 11+ 没声明 <queries> 时它会误返回 null）
        val attempts = listOf(
            buildIntent(Intent.ACTION_GET_CONTENT),
            buildIntent(Intent.ACTION_OPEN_DOCUMENT),
            Intent.createChooser(buildIntent(Intent.ACTION_GET_CONTENT), tr("选择文件")),
        )
        for (intent in attempts) {
            val launched = runCatching { filePickerLauncher.launch(intent) }
                .onFailure { Diag.log("file picker launch failed: ${intent.action} $it") }
                .isSuccess
            if (launched) return
        }
        choice.callback.onReceiveValue(null)
        fileChooserChoice = null
        android.widget.Toast.makeText(context, tr("未找到可用的文件选择器"), android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 拍照或录像：MediaStore pending 输出 + 相机 Intent */
    fun launchCamera(choice: FileChooserChoice, video: Boolean) {
        val resolver = context.contentResolver
        val uri = runCatching {
            if (video) {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "rec_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Mafari")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
                resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            } else {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "photo_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Mafari")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            }
        }.getOrNull()
        if (uri == null) {
            choice.callback.onReceiveValue(null)
            fileChooserChoice = null
            return
        }
        cameraOutputUri = uri
        val intent = if (video) {
            Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply { putExtra(MediaStore.EXTRA_OUTPUT, uri) }
        } else {
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply { putExtra(MediaStore.EXTRA_OUTPUT, uri) }
        }.apply {
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            val resInfo = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            for (info in resInfo) {
                context.grantUriPermission(
                    info.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        val canResolve = intent.resolveActivity(context.packageManager) != null
        if (canResolve) {
            cameraLauncher.launch(intent)
        } else {
            cameraOutputUri = null
            runCatching { resolver.delete(uri, null, null) }
            choice.callback.onReceiveValue(null)
            fileChooserChoice = null
            android.widget.Toast.makeText(context, tr("未找到相机应用"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    return BrowserLaunchers(androidPermLauncher, ::launchScan, ::launchFilePicker, ::launchCamera)
}
