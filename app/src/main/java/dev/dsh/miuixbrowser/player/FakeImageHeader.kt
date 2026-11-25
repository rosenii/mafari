/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.player

/**
 * 部分站点把 TS 分片伪装成图片：前面垫一段 PNG / JPEG / GIF / BMP 头，播放器认不出。
 * 判定规则与 download/HlsDownload.stripFakeImageHeader 一致（纯函数，便于单测）：
 * 只有开头是这几种图片魔数、且头部能找到连续三个相隔 188 字节的 0x47 同步字节时才返回裁剪偏移，不碰正常分片。
 */
object FakeImageHeader {
    /** 最多往后找多少字节（与下载器一致：16KB） */
    const val PEEK_LIMIT = 16 * 1024

    /** 至少要读到这么多字节才能做三连同步判定 */
    private const val MIN_LEN = 400

    /** 开头是否是图片魔数（只要 4 字节就能判断；不足时按不是处理） */
    fun looksLikeImage(b: ByteArray, n: Int): Boolean {
        if (n < 4) return false
        fun at(i: Int) = b[i].toInt() and 0xFF
        return (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) ||
            (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) ||
            (at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x38) ||
            (at(0) == 0x42 && at(1) == 0x4D)
    }

    /**
     * @param b 分片开头的字节，[n] 为有效长度
     * @return 需要丢弃的前缀字节数；0 表示不处理
     */
    fun stripOffset(b: ByteArray, n: Int): Int {
        if (n < MIN_LEN || !looksLikeImage(b, n)) return 0
        fun at(i: Int) = b[i].toInt() and 0xFF
        for (i in 1 until n - 376) {
            if (at(i) == 0x47 && at(i + 188) == 0x47 && at(i + 376) == 0x47) return i
        }
        return 0
    }
}
