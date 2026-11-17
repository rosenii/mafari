package dev.dsh.miuixbrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 一个 Markdown 块（只支持更新日志里会用到的子集） */
private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val depth: Int, val text: String) : MdBlock
    data class Numbered(val depth: Int, val number: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
    data class Para(val text: String) : MdBlock
}

private val RE_HEADING = Regex("""^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
private val RE_RULE = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
private val RE_BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val RE_NUMBERED = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
private val RE_QUOTE = Regex("""^\s{0,3}>\s?(.*)$""")

private fun depthOf(indent: String): Int = (indent.replace("\t", "    ").length / 2).coerceIn(0, 3)

private fun parseBlocks(markdown: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val para = ArrayList<String>()
    var inCode = false
    val code = ArrayList<String>()

    fun flushPara() {
        if (para.isNotEmpty()) {
            out.add(MdBlock.Para(para.joinToString("\n")))
            para.clear()
        }
    }

    for (raw in markdown.replace("\r\n", "\n").lines()) {
        val line = raw.trimEnd()
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                out.add(MdBlock.Code(code.joinToString("\n")))
                code.clear()
                inCode = false
            } else {
                flushPara()
                inCode = true
            }
            continue
        }
        if (inCode) {
            code.add(raw)
            continue
        }
        if (line.isBlank()) {
            flushPara()
            continue
        }
        val heading = RE_HEADING.matchEntire(line)
        if (heading != null) {
            flushPara()
            out.add(MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2]))
            continue
        }
        if (RE_RULE.matches(line)) {
            flushPara()
            out.add(MdBlock.Rule)
            continue
        }
        val bullet = RE_BULLET.matchEntire(line)
        if (bullet != null) {
            flushPara()
            out.add(MdBlock.Bullet(depthOf(bullet.groupValues[1]), bullet.groupValues[2]))
            continue
        }
        val numbered = RE_NUMBERED.matchEntire(line)
        if (numbered != null) {
            flushPara()
            out.add(MdBlock.Numbered(depthOf(numbered.groupValues[1]), numbered.groupValues[2], numbered.groupValues[3]))
            continue
        }
        val quote = RE_QUOTE.matchEntire(line)
        if (quote != null) {
            flushPara()
            val last = out.lastOrNull()
            if (last is MdBlock.Quote) out[out.lastIndex] = MdBlock.Quote(last.text + "\n" + quote.groupValues[1])
            else out.add(MdBlock.Quote(quote.groupValues[1]))
            continue
        }
        // 缩进的续行：接到上一个列表项后面
        val last = out.lastOrNull()
        if (para.isEmpty() && raw.startsWith(" ") && last is MdBlock.Bullet) {
            out[out.lastIndex] = last.copy(text = last.text + " " + line.trim())
            continue
        }
        if (para.isEmpty() && raw.startsWith(" ") && last is MdBlock.Numbered) {
            out[out.lastIndex] = last.copy(text = last.text + " " + line.trim())
            continue
        }
        para.add(line.trim())
    }
    if (inCode && code.isNotEmpty()) out.add(MdBlock.Code(code.joinToString("\n")))
    flushPara()
    return out
}

private class InlineColors(
    val codeBackground: Color,
    val codeForeground: Color,
    val link: Color,
)

/** 行内样式：`code`、**粗体**、*斜体*、~~删除线~~、[文字](链接)（只着色不跳转）、反斜杠转义。 */
private fun inlineOf(src: String, c: InlineColors): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = src.length
    while (i < n) {
        val ch = src[i]
        when {
            ch == '\\' && i + 1 < n && src[i + 1] in "\\`*_{}[]()#+-.!~>|" -> {
                append(src[i + 1]); i += 2
            }
            ch == '`' -> {
                val end = src.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = c.codeBackground,
                            color = c.codeForeground,
                            fontSize = 12.sp,
                        ),
                    ) { append(" " + src.substring(i + 1, end) + " ") }
                    i = end + 1
                } else { append(ch); i++ }
            }
            src.startsWith("**", i) -> {
                val end = src.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inlineOf(src.substring(i + 2, end), c)) }
                    i = end + 2
                } else { append(ch); i++ }
            }
            src.startsWith("~~", i) -> {
                val end = src.indexOf("~~", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(inlineOf(src.substring(i + 2, end), c)) }
                    i = end + 2
                } else { append(ch); i++ }
            }
            // 单个 * 才当斜体；下划线不处理（sync_lite 这类名字里有 _）
            ch == '*' && i + 1 < n && !src[i + 1].isWhitespace() && src[i + 1] != '*' -> {
                var end = src.indexOf('*', i + 1)
                while (end > 0 && (src[end - 1].isWhitespace() || (end + 1 < n && src[end + 1] == '*'))) {
                    end = src.indexOf('*', end + 1)
                }
                if (end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inlineOf(src.substring(i + 1, end), c)) }
                    i = end + 1
                } else { append(ch); i++ }
            }
            ch == '[' -> {
                val close = src.indexOf(']', i + 1)
                val ok = close > i && close + 1 < n && src[close + 1] == '('
                val paren = if (ok) src.indexOf(')', close + 2) else -1
                if (paren > close) {
                    withStyle(SpanStyle(color = c.link, textDecoration = TextDecoration.Underline)) {
                        append(src.substring(i + 1, close))
                    }
                    i = paren + 1
                } else { append(ch); i++ }
            }
            else -> { append(ch); i++ }
        }
    }
}

/** 日志条目前缀「新增：」「修复：」…… → 彩色粗体标签 */
private val TAG_RE = Regex("""^(新增|修复|调整|优化|改进|改动|移除|删除|重构|注意|实验性)\s*[：:]\s*""")

private fun tagColor(tag: String): Color = when (tag) {
    "新增" -> Color(0xFF30B36A)
    "修复" -> Color(0xFFF08A24)
    "调整", "优化", "改进", "改动", "重构" -> Color(0xFF3482FF)
    "移除", "删除" -> Color(0xFFE5484D)
    else -> Color(0xFF8E6CEF)
}

/**
 * 用 Compose 原生文本渲染 Markdown（更新日志用）：标题、列表（含嵌套 / 有序）、引用、代码块、分隔线、
 * 粗体 / 斜体 / 行内代码 / 删除线 / 链接；条目开头的「新增 / 修复 / 调整…」自动着色。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    fontSize: Int = 13,
) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
    val colors = MiuixTheme.colorScheme
    val primary = colors.primary
    val inlineColors = remember(colors.surfaceContainerHigh, colors.onSurface, primary) {
        InlineColors(
            codeBackground = colors.surfaceContainerHigh,
            codeForeground = colors.onSurface,
            link = primary,
        )
    }
    val body = colors.onSurface
    val soft = colors.onSurfaceVariantSummary
    val size = fontSize.sp
    val lineHeight = (fontSize * 1.55f).sp

    /** 列表项 / 段落文本：识别开头的类型标签 */
    fun itemText(text: String): AnnotatedString {
        val m = TAG_RE.find(text)
        if (m == null) return inlineOf(text, inlineColors)
        val tag = m.groupValues[1]
        return buildAnnotatedString {
            withStyle(SpanStyle(color = tagColor(tag), fontWeight = FontWeight.SemiBold)) { append(tag) }
            append("  ")
            append(inlineOf(text.substring(m.range.last + 1), inlineColors))
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = inlineOf(block.text, inlineColors),
                    fontSize = when (block.level) { 1 -> 17.sp; 2 -> 16.sp; 3 -> 15.sp; else -> 14.sp },
                    fontWeight = FontWeight.Bold,
                    color = body,
                    modifier = Modifier.padding(top = if (block.level <= 2) 4.dp else 2.dp),
                )
                is MdBlock.Bullet -> Row(modifier = Modifier.padding(start = (block.depth * 14).dp)) {
                    Box(
                        modifier = Modifier
                            .padding(top = (fontSize * 0.62f).dp, end = 8.dp)
                            .size(5.dp)
                            .background(if (block.depth == 0) primary else soft, CircleShape),
                    )
                    Text(text = itemText(block.text), fontSize = size, lineHeight = lineHeight, color = body, modifier = Modifier.weight(1f))
                }
                is MdBlock.Numbered -> Row(modifier = Modifier.padding(start = (block.depth * 14).dp)) {
                    Text(
                        text = block.number + ".",
                        fontSize = size,
                        lineHeight = lineHeight,
                        fontWeight = FontWeight.SemiBold,
                        color = primary,
                        modifier = Modifier.width(22.dp),
                    )
                    Text(text = itemText(block.text), fontSize = size, lineHeight = lineHeight, color = body, modifier = Modifier.weight(1f))
                }
                is MdBlock.Quote -> Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(primary.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
                    )
                    Text(text = inlineOf(block.text, inlineColors), fontSize = size, lineHeight = lineHeight, color = soft, modifier = Modifier.weight(1f))
                }
                is MdBlock.Code -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceContainerHigh, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = block.text,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = body,
                    )
                }
                MdBlock.Rule -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .height(0.5.dp)
                        .background(colors.dividerLine),
                )
                is MdBlock.Para -> Text(text = itemText(block.text), fontSize = size, lineHeight = lineHeight, color = body)
            }
        }
    }
}
