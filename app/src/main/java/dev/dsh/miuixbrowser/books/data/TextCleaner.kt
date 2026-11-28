/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.books.data

/**
 * 正文净化：HTML 实体、隐藏字符、广告网址 / 站点水印。
 *
 * 只做「读起来明显是广告」的清理，不按关键词乱删正文：
 * - 先解实体（`&nbsp;` `&ensp;` `&ldquo;` … 以及数字实体）
 * - 去掉零宽字符、控制字符、重复空格
 * - 整行是网址 / 域名，或短行里出现「请记住本站 / 最新网址 / 无弹窗」这类站点水印，丢掉整行
 * - 正文中间的广告网址和【…网址…】块删掉，保留原句
 */
object TextCleaner {
    private val NAMED = mapOf(
        "nbsp" to " ", "ensp" to " ", "emsp" to " ", "thinsp" to "",
        "zwnj" to "", "zwj" to "", "lrm" to "", "rlm" to "", "shy" to "",
        "middot" to "·", "bull" to "•", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
        "laquo" to "«", "raquo" to "»", "copy" to "©", "reg" to "®", "trade" to "™",
        "times" to "×", "divide" to "÷", "deg" to "°", "para" to "¶", "sect" to "§",
        "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢",
    )

    private val NAMED_ENTITY = Regex("&([a-zA-Z][a-zA-Z0-9]{1,8});")
    private val URL = Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>\"'，。！？；：、（）【】《》]+")
    private val DOMAIN = Regex(
        "(?i)(?<![\\w@./-])(?:[a-z0-9](?:[a-z0-9-]{0,62})\\.)+" +
            "(?:com|net|org|cn|cc|info|me|xyz|top|vip|la|io|co|tv|biz|site|club|app|link|wang|shop|mobi)" +
            "(?::\\d+)?(?:/[^\\s<>\"'，。！？；：、（）【】《》]*)?",
    )
    private val BRACKET_AD = Regex("【[^】]{0,48}(?:https?://|www\\.|\\.com|\\.net|\\.cn|网址|广告|域名)[^】]{0,48}】", RegexOption.IGNORE_CASE)

    /** 短行里出现这些词，基本是站点水印；长段落里出现不算（避免误删正文）。 */
    private val AD_HINTS = listOf(
        "请记住本站", "记住本站", "本站地址", "最新网址", "最新地址", "手机版阅读", "手机阅读网址",
        "章节未完", "本章未完", "点击下一页", "翻页继续", "加入书签", "投推荐票", "推荐票",
        "天才一秒记住", "一秒记住", "无弹窗", "更新最快", "全文阅读", "请收藏", "首发地址",
        "笔趣阁", "顶点小说", "新笔趣阁", "书趣阁", "飘天文学", "无名小说网", "88小说",
        "微信公众号", "书友群", "QQ群", "群号", "本站域名",
    )

    /** 解 HTML 实体（命名 + 十进制 / 十六进制数字）。 */
    fun decode(s: String): String {
        var out = s
        if (out.indexOf('&') >= 0) {
            out = out.replace(Regex("&#x([0-9a-fA-F]+);?")) { m ->
                runCatching { String(Character.toChars(m.groupValues[1].toInt(16))) }.getOrDefault(m.value)
            }
            out = out.replace(Regex("&#(\\d+);?")) { m ->
                runCatching { String(Character.toChars(m.groupValues[1].toInt())) }.getOrDefault(m.value)
            }
            out = NAMED_ENTITY.replace(out) { m ->
                NAMED[m.groupValues[1].lowercase()] ?: m.value
            }
            out = out.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
        }
        return out
    }

    /**
     * 净化一行 / 一段。返回 null 表示整行都是广告 / 水印，直接丢掉。
     */
    fun line(raw: String): String? {
        var s = decode(raw)
            .replace('\u00A0', ' ').replace('\u3000', ' ')
            .replace(Regex("[\\u200B-\\u200F\\u202A-\\u202E\\uFEFF]"), "")
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
            .trim()
        if (s.isEmpty()) return null

        // 整行就是网址 / 域名：丢
        val noUrl = URL.replace(s, " ").let { DOMAIN.replace(it, " ") }
        if (noUrl.isBlank()) return null

        // 【www.xxx.com】这种插在正文里的广告块
        s = BRACKET_AD.replace(s, " ")
        s = URL.replace(s, " ").let { DOMAIN.replace(it, " ") }
        s = s.replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return null

        // 站点水印：短行命中就丢
        if (s.length <= 120 && AD_HINTS.any { s.contains(it, ignoreCase = true) }) return null

        // 去掉句尾常见的「（本章未完…）」「请记住本站…」尾巴
        s = s.replace(Regex("(?i)[（(【\\[]?\\s*(?:本章未完|章节未完|请记住本站|最新网址|最新地址|加入书签|投推荐票)[^）)】\\]]*[）)】\\]]?\\s*$"), "").trim()
        if (s.isEmpty()) return null
        // 只剩标点 / 分隔符的坏行
        if (s.none { it.isLetterOrDigit() || it.code in 0x4E00..0x9FFF }) return null
        return s
    }

    /** 一批段落：逐段净化并丢掉空段。 */
    fun paragraphs(list: List<String>): List<String> = list.mapNotNull { line(it) }
}
