package dev.dsh.miuixbrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 模糊壁纸磁盘缓存：开启「壁纸模糊」时只生成一次模糊图并存进应用缓存目录，
 * 之后直接读取这张已经模糊好的小图，不再每帧对整张壁纸做实时模糊渲染。
 *
 * 做法：把壁纸缩到长边 [MAX_SIDE]px，做三次盒式模糊（近似高斯），存成 JPEG。
 * 显示时按 Crop 放大铺满，放大本身就是平滑的，模糊图不会有可见的锯齿。
 */
object WallpaperBlurCache {
    private const val DIR = "wallpaper_blur"
    // v2：压暗层（SCRIM_ALPHA）已烘进缓存图，显示时不再另盖一层全屏黑色
    private const val VERSION = 2
    /** 首页壁纸压暗强度；缓存图里已烘进，未走缓存（模糊关闭 / 缓存未就绪）时由显示层自己盖同样的一层。 */
    const val SCRIM_ALPHA = 0.28f
    private const val MAX_SIDE = 360
    /** 模糊半径占短边的比例，约等于原先 28dp 在手机屏宽上的视觉强度。 */
    private const val RADIUS_RATIO = 0.075f

    private fun cacheFile(context: Context, uri: String): File {
        val md = MessageDigest.getInstance("MD5").digest("$VERSION|$uri".toByteArray())
        val name = md.joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, DIR).apply { mkdirs() }, "$name.jpg")
    }

    // 进程内一级缓存（单项、软引用）：BrowserScreen 重新进入组合时不必再读盘解码；壁纸更换 / clear 时一并作废
    private var memKey: String? = null
    private var memRef: java.lang.ref.SoftReference<Bitmap>? = null

    private val genLock = Any()

    private fun memGet(name: String): Bitmap? = synchronized(this) {
        if (memKey == name) memRef?.get()?.takeIf { !it.isRecycled } else null
    }

    private fun memPut(name: String, bmp: Bitmap) = synchronized(this) {
        memKey = name; memRef = java.lang.ref.SoftReference(bmp)
    }

    /**
     * 只读缓存，不生成：进程内命中直接返回，否则同步解码磁盘上那张 360px 小图（几毫秒）；没有缓存返回 null。
     * 用于首帧直接拿到模糊图，避免「先显示未模糊原图再切换」的闪动。
     * 不拿生成锁：主线程调用时不会被后台正在进行的模糊生成卡住。
     */
    fun peek(context: Context, uri: String): Bitmap? {
        if (uri.isBlank()) return null
        val file = cacheFile(context, uri)
        memGet(file.name)?.let { return it }
        if (!file.exists() || file.length() <= 0) return null
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        memPut(file.name, bmp)
        return bmp
    }

    /** 读取缓存；没有则生成并写入。必须在 IO 线程调用；生成过程串行，但不阻塞 [peek]。 */
    fun getOrCreate(context: Context, uri: String): Bitmap? {
        if (uri.isBlank()) return null
        val file = cacheFile(context, uri)
        memGet(file.name)?.let { return it }
        return synchronized(genLock) {
            memGet(file.name) ?: loadOrGenerate(context, uri, file)?.also { memPut(file.name, it) }
        }
    }

    private fun loadOrGenerate(context: Context, uri: String, file: File): Bitmap? {
        if (file.exists() && file.length() > 0) {
            BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
            file.delete()
        }
        val blurred = runCatching { generate(context, uri) }.getOrNull() ?: return null
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { blurred.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            if (!tmp.renameTo(file)) tmp.delete()
        }
        return blurred
    }

    /** 壁纸更换 / 取消时清掉旧缓存。 */
    @Synchronized
    fun clear(context: Context) {
        memKey = null; memRef = null
        runCatching { File(context.cacheDir, DIR).listFiles()?.forEach { it.delete() } }
    }

    private fun generate(context: Context, uri: String): Bitmap? {
        val resolver = context.contentResolver
        val parsed = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val scale = MAX_SIDE.toFloat() / max(decoded.width, decoded.height)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                max(1, (decoded.width * scale).roundToInt()),
                max(1, (decoded.height * scale).roundToInt()),
                true,
            ).also { if (it !== decoded) decoded.recycle() }
        } else decoded
        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        small.recycle()
        val radius = max(2, (min(w, h) * RADIUS_RATIO).roundToInt())
        repeat(3) {
            boxBlurH(px, w, h, radius)
            boxBlurV(px, w, h, radius)
        }
        // 把壁纸压暗烘进缓存图：等价于显示时再盖一层 Black@SCRIM_ALPHA，却省掉一层全屏绘制
        val keep = 1f - SCRIM_ALPHA
        for (i in px.indices) {
            val c = px[i]
            val r = (((c shr 16) and 255) * keep).toInt()
            val g = (((c shr 8) and 255) * keep).toInt()
            val b = ((c and 255) * keep).toInt()
            px[i] = (c and (0xFF shl 24)) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlurH(px: IntArray, w: Int, h: Int, r: Int) {
        val line = IntArray(w)
        val div = 2 * r + 1
        for (y in 0 until h) {
            val o = y * w
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val c = px[o + i.coerceIn(0, w - 1)]
                a += c ushr 24; rr += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
            }
            for (x in 0 until w) {
                line[x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                val add = px[o + (x + r + 1).coerceAtMost(w - 1)]
                val sub = px[o + (x - r).coerceAtLeast(0)]
                a += (add ushr 24) - (sub ushr 24)
                rr += ((add shr 16) and 255) - ((sub shr 16) and 255)
                g += ((add shr 8) and 255) - ((sub shr 8) and 255)
                b += (add and 255) - (sub and 255)
            }
            System.arraycopy(line, 0, px, o, w)
        }
    }

    private fun boxBlurV(px: IntArray, w: Int, h: Int, r: Int) {
        val line = IntArray(h)
        val div = 2 * r + 1
        for (x in 0 until w) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val c = px[i.coerceIn(0, h - 1) * w + x]
                a += c ushr 24; rr += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
            }
            for (y in 0 until h) {
                line[y] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                val add = px[(y + r + 1).coerceAtMost(h - 1) * w + x]
                val sub = px[(y - r).coerceAtLeast(0) * w + x]
                a += (add ushr 24) - (sub ushr 24)
                rr += ((add shr 16) and 255) - ((sub shr 16) and 255)
                g += ((add shr 8) and 255) - ((sub shr 8) and 255)
                b += (add and 255) - (sub and 255)
            }
            for (y in 0 until h) px[y * w + x] = line[y]
        }
    }
}

/**
 * 壁纸原图加载：按「Crop 铺满屏幕所需的最小尺寸」解码，而不是整张原图。
 * 4000×3000 的照片整图解码约 48MB（还要整张上传 GPU），屏幕只需约 10MB；
 * 先用 inSampleSize 粗降，再精确缩到所需尺寸。单项软引用缓存，同一壁纸不重复解码。
 */
object WallpaperImage {
    private var memKey: String? = null
    private var memRef: java.lang.ref.SoftReference<Bitmap>? = null

    @Synchronized
    fun load(context: Context, uri: String, version: Int, targetW: Int, targetH: Int): Bitmap? {
        if (uri.isBlank() || targetW <= 0 || targetH <= 0) return null
        val key = "$version|$uri|$targetW|$targetH"
        if (memKey == key) memRef?.get()?.takeIf { !it.isRecycled }?.let { return it }
        val bmp = runCatching { decode(context, uri, targetW, targetH) }.getOrNull() ?: return null
        memKey = key
        memRef = java.lang.ref.SoftReference(bmp)
        return bmp
    }

    private fun decode(context: Context, uri: String, targetW: Int, targetH: Int): Bitmap? {
        val resolver = context.contentResolver
        val parsed = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val bw = bounds.outWidth
        val bh = bounds.outHeight
        if (bw <= 0 || bh <= 0) return null
        // Crop 铺满所需的最小缩放比：原图再小就会被放大，大了才有降采样的空间
        val need = max(targetW.toFloat() / bw, targetH.toFloat() / bh)
        var sample = 1
        while (1f / (sample * 2) >= need) sample *= 2
        val decoded = resolver.openInputStream(parsed)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        // 粗降后仍明显大于所需（比所需大 15% 以上）才精确缩一次
        val ratio = need * sample
        if (ratio >= 0.85f) return decoded
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            max(1, (decoded.width * ratio).roundToInt()),
            max(1, (decoded.height * ratio).roundToInt()),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }
}
