/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

class PackageException(message: String) : Exception(message)

data class UnpackLimits(
    val maxEntries: Int = 20_000,
    /** 按实际读出的字节数计，不信任 zip 头里声明的大小（防 zip bomb） */
    val maxEntryBytes: Long = 256L * 1024 * 1024,
    val maxTotalBytes: Long = 512L * 1024 * 1024,
)

/**
 * 把 zip / xpi 解到目录。任何一个条目的路径不合法就整包拒绝，而不是跳过：
 * 带 `../`、绝对路径、盘符、NUL、反斜杠穿越，或规范化后落到目标目录之外（zip-slip）。
 * 不创建符号链接。调用方负责在失败时清理 [dest]。
 */
object PackageUnpacker {

    /** 返回规范化后的相对路径（用 / 分隔）；不合法返回 null */
    fun safeRelativePath(name: String): String? {
        if (name.isEmpty() || name.indexOf('\u0000') >= 0) return null
        val n = name.replace('\\', '/')
        if (n.startsWith("/")) return null
        if (n.length >= 2 && n[1] == ':') return null
        val parts = n.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    fun unpack(zip: File, dest: File, limits: UnpackLimits = UnpackLimits()) {
        dest.mkdirs()
        val root = dest.canonicalFile
        val rootPrefix = root.path + File.separator
        val seen = HashSet<String>()
        var total = 0L
        var count = 0
        try {
            ZipFile(zip).use { z ->
                val entries = z.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    count++
                    if (count > limits.maxEntries) throw PackageException("包内文件数量过多")
                    val rel = safeRelativePath(e.name) ?: throw PackageException("包内含非法路径：${e.name}")
                    val target = File(root, rel).canonicalFile
                    if (!target.path.startsWith(rootPrefix)) throw PackageException("包内路径越界：${e.name}")
                    if (e.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    // 大小写不敏感地查重：避免在大小写不敏感的文件系统上互相覆盖
                    if (!seen.add(rel.lowercase())) throw PackageException("包内含重复路径：$rel")
                    target.parentFile?.mkdirs()
                    z.getInputStream(e).use { input ->
                        target.outputStream().use { out ->
                            val buf = ByteArray(32 * 1024)
                            var entryBytes = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                entryBytes += n
                                total += n
                                if (entryBytes > limits.maxEntryBytes || total > limits.maxTotalBytes) {
                                    throw PackageException("解压后体积过大")
                                }
                                out.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
        } catch (e: ZipException) {
            throw PackageException("不是有效的压缩包：${e.message}")
        } catch (e: IOException) {
            throw PackageException("解包失败：${e.message}")
        }
    }
}
