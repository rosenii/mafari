/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.dsh.miuixbrowser.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * 包在真正的网络 DataSource 外面：从头读（position == 0）的响应若开头是图片魔数且后面藏着 TS，就丢掉图片前缀再交给解析器。
 * 其它情况（带偏移的 Range 请求、mp4 / 正常 TS）原样透传，只多一次不超过 16KB 的预读。
 *
 * 对播放器来说这个分片的长度是「裁掉前缀之后」的长度，所以 [open] 返回值要同步减去前缀。
 */
class StripImageHeaderDataSource(private val upstream: DataSource) : DataSource {

    private var buf: ByteArray = EMPTY
    private var bufPos = 0
    private var bufEnd = 0

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        buf = EMPTY; bufPos = 0; bufEnd = 0
        val len = upstream.open(dataSpec)
        if (dataSpec.position != 0L) return len
        try {
            val head = ByteArray(FakeImageHeader.PEEK_LIMIT)
            var n = 0
            // 先读够 4 字节判断是不是图片；不是就带着已读到的字节直接返回，不为凑满 16KB 去等网络
            while (n < 4) {
                val r = upstream.read(head, n, head.size - n)
                if (r == C.RESULT_END_OF_INPUT) break
                n += r
            }
            if (FakeImageHeader.looksLikeImage(head, n)) {
                while (n < head.size) {
                    val r = upstream.read(head, n, head.size - n)
                    if (r == C.RESULT_END_OF_INPUT) break
                    n += r
                }
            }
            val off = FakeImageHeader.stripOffset(head, n)
            buf = head; bufPos = off; bufEnd = n
            return if (len == C.LENGTH_UNSET.toLong()) len else (len - off).coerceAtLeast(0)
        } catch (e: Throwable) {
            runCatching { upstream.close() }
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bufPos < bufEnd) {
            val c = minOf(length, bufEnd - bufPos)
            System.arraycopy(buf, bufPos, buffer, offset, c)
            bufPos += c
            if (bufPos >= bufEnd) buf = EMPTY
            return c
        }
        return upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        buf = EMPTY; bufPos = 0; bufEnd = 0
        upstream.close()
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = StripImageHeaderDataSource(upstream.createDataSource())
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
