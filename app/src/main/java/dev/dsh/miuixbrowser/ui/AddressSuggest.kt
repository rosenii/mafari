/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import dev.dsh.miuixbrowser.i18n.tr
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 地址栏搜索建议（输入时自动补全）：输入内容匹配到书签或历史的网址时，把网址剩余部分补在光标后并选中，
 * 回车直接使用补全后的地址；继续输入则覆盖补全，退格删掉补全部分。开关在输入状态的地址栏右侧，跨启动保存。
 */
object AddressSuggest {
    private const val FILE = "mafari"
    private const val KEY = "address_suggest"

    var enabled by mutableStateOf(true)
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        enabled = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        enabled = value
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }

    private fun stripScheme(u: String): String = u.replaceFirst(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://"), "")

    /**
     * 在 [urls]（按优先级排好：书签在前、历史按时间倒序）里找第一个以 [typed] 开头的网址，返回补全后的完整文本
     * （前缀保留用户输入的大小写）；没有或无需补全返回 null。
     * 输入不含 "/" 时只补到域名（git → github.com），含 "/" 时补到完整路径。可匹配带协议 / 去协议 / 去 www 三种写法。
     */
    fun complete(typed: String, urls: List<String>): String? {
        if (typed.isEmpty() || typed.any { it.isWhitespace() }) return null
        val t = typed.lowercase()
        val hostOnly = !typed.contains('/')
        for (url in urls) {
            val noScheme = stripScheme(url)
            val variants = listOf(url, noScheme, noScheme.removePrefix("www."))
            for (v in variants) {
                if (!v.lowercase().startsWith(t)) continue
                var cand = if (hostOnly) {
                    val s0 = v.indexOf("://").let { if (it >= 0) it + 3 else 0 }
                    val e = v.indexOf('/', s0)
                    if (e < 0) v else v.substring(0, e)
                } else v
                cand = cand.trimEnd('/')
                if (cand.length > typed.length && cand.lowercase().startsWith(t)) return typed + cand.substring(typed.length)
            }
        }
        return null
    }
}

/** 输入状态下地址栏右侧的「搜索建议」开关：开 = 主题色，关 = 灰色加斜杠；点击切换。 */
@Composable
internal fun AddressSuggestButton() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    remember { AddressSuggest.load(context) }
    val on = AddressSuggest.enabled
    val tint = if (on) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    AddressSuggest.set(context, !on)
                    Toast.makeText(context, if (!on) tr("搜索建议已开启") else tr("搜索建议已关闭"), Toast.LENGTH_SHORT).show()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val iconSize = with(LocalDensity.current) { 15.sp.toDp() }
        Icon(
            imageVector = MiuixIcons.Glass.Search,
            contentDescription = tr("搜索建议"),
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
        if (!on) {
            val stroke = with(LocalDensity.current) { 1.6.dp.toPx() }
            Canvas(modifier = Modifier.size(iconSize + 4.dp)) {
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.12f, size.height * 0.88f),
                    end = Offset(size.width * 0.88f, size.height * 0.12f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
