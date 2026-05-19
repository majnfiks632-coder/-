package com.aiagent.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
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
import com.aiagent.android.ui.theme.KiroColors

/**
 * Minimal Compose markdown renderer. Designed to look like Kiro's
 * `react-markdown + rehype-highlight` pipeline without pulling a heavy
 * library. Supports:
 *   - paragraphs separated by blank lines
 *   - fenced code blocks ```lang ... ``` (with copy button + horizontal scroll)
 *   - inline code `x`
 *   - bold (**x**) and italic (*x* / _x_)
 *   - headings `#`, `##`, `###`
 *   - bullet lists (`- ` or `* `) and ordered lists (`1. `)
 *   - links `[text](url)`
 * Everything else is rendered as-is, preserving line breaks.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> ParagraphBlock(block.text)
                is MdBlock.Code -> CodeBlockView(block.language, block.code)
                is MdBlock.Heading -> HeadingBlock(block.level, block.text)
                is MdBlock.BulletList -> BulletListBlock(block.items)
                is MdBlock.OrderedList -> OrderedListBlock(block.items)
                is MdBlock.BlockQuote -> BlockQuoteBlock(block.text)
            }
        }
    }
}

@Composable
private fun ParagraphBlock(text: String) {
    SelectionContainer {
        Text(
            text = renderInline(text),
            color = KiroColors.Foreground,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
    }
}

@Composable
private fun HeadingBlock(level: Int, text: String) {
    val (size, weight) = when (level) {
        1 -> 20.sp to FontWeight.SemiBold
        2 -> 18.sp to FontWeight.SemiBold
        else -> 16.sp to FontWeight.SemiBold
    }
    SelectionContainer {
        Text(
            text = renderInline(text),
            color = KiroColors.Foreground,
            fontSize = size,
            fontWeight = weight,
        )
    }
}

@Composable
private fun BulletListBlock(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "•",
                    color = KiroColors.Muted,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(end = 8.dp),
                )
                SelectionContainer {
                    Text(
                        text = renderInline(item),
                        color = KiroColors.Foreground,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun OrderedListBlock(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { idx, item ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "${idx + 1}.",
                    color = KiroColors.Muted,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(end = 8.dp),
                )
                SelectionContainer {
                    Text(
                        text = renderInline(item),
                        color = KiroColors.Foreground,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun BlockQuoteBlock(text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = 0f)
                .background(KiroColors.Border)
                .padding(end = 10.dp),
        )
        Text(
            text = renderInline(text),
            color = KiroColors.Muted,
            fontSize = 14.sp,
            fontStyle = FontStyle.Italic,
            modifier = Modifier
                .background(KiroColors.Surface2, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun CodeBlockView(language: String?, code: String) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D1117), RoundedCornerShape(10.dp))
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = (language ?: "").ifBlank { "code" },
                color = KiroColors.Muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(code)) },
                modifier = Modifier,
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy code",
                    tint = KiroColors.Muted,
                )
            }
        }
        SelectionContainer {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = code.trimEnd('\n'),
                    color = Color(0xFFE6EDF3),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------------------------
// Parser
// -----------------------------------------------------------------------------------------------

private sealed class MdBlock {
    data class Paragraph(val text: String) : MdBlock()
    data class Code(val language: String?, val code: String) : MdBlock()
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class BulletList(val items: List<String>) : MdBlock()
    data class OrderedList(val items: List<String>) : MdBlock()
    data class BlockQuote(val text: String) : MdBlock()
}

private fun parseMarkdownBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.replace("\r\n", "\n").split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]

        // Fenced code block.
        val fence = Regex("^```\\s*([\\w+.-]*)\\s*$").matchEntire(line)
        if (fence != null) {
            val lang = fence.groupValues[1].ifBlank { null }
            val buf = StringBuilder()
            i++
            while (i < lines.size && !lines[i].startsWith("```")) {
                buf.append(lines[i]).append('\n')
                i++
            }
            if (i < lines.size) i++ // consume closing fence
            out.add(MdBlock.Code(lang, buf.toString()))
            continue
        }

        // Heading.
        val heading = Regex("^(#{1,6})\\s+(.*)$").matchEntire(line)
        if (heading != null) {
            val level = heading.groupValues[1].length.coerceAtMost(6)
            out.add(MdBlock.Heading(level, heading.groupValues[2]))
            i++
            continue
        }

        // Bullet list — gather consecutive bullet lines.
        if (Regex("^\\s*[-*]\\s+.*").matches(line)) {
            val items = mutableListOf<String>()
            while (i < lines.size && Regex("^\\s*[-*]\\s+.*").matches(lines[i])) {
                items.add(lines[i].replaceFirst(Regex("^\\s*[-*]\\s+"), ""))
                i++
            }
            out.add(MdBlock.BulletList(items))
            continue
        }

        // Ordered list — gather consecutive `n. ` lines.
        if (Regex("^\\s*\\d+\\.\\s+.*").matches(line)) {
            val items = mutableListOf<String>()
            while (i < lines.size && Regex("^\\s*\\d+\\.\\s+.*").matches(lines[i])) {
                items.add(lines[i].replaceFirst(Regex("^\\s*\\d+\\.\\s+"), ""))
                i++
            }
            out.add(MdBlock.OrderedList(items))
            continue
        }

        // Block quote.
        if (line.startsWith(">")) {
            val buf = StringBuilder()
            while (i < lines.size && lines[i].startsWith(">")) {
                buf.append(lines[i].removePrefix(">").trimStart()).append('\n')
                i++
            }
            out.add(MdBlock.BlockQuote(buf.toString().trimEnd()))
            continue
        }

        // Paragraph — gather until blank line.
        if (line.isBlank()) { i++; continue }
        val buf = StringBuilder(line)
        i++
        while (i < lines.size && lines[i].isNotBlank() &&
            !lines[i].startsWith("```") &&
            !Regex("^#{1,6}\\s+.*").matches(lines[i]) &&
            !Regex("^\\s*[-*]\\s+.*").matches(lines[i]) &&
            !Regex("^\\s*\\d+\\.\\s+.*").matches(lines[i]) &&
            !lines[i].startsWith(">")
        ) {
            buf.append('\n').append(lines[i])
            i++
        }
        out.add(MdBlock.Paragraph(buf.toString()))
    }
    return out
}

// -----------------------------------------------------------------------------------------------
// Inline renderer: bold, italic, code, links.
// -----------------------------------------------------------------------------------------------

private val InlineToken = Regex(
    "(`[^`]+`)" +                           // 1: inline code
        "|(\\*\\*[^*]+\\*\\*)" +            // 2: bold **x**
        "|(__([^_]+)__)" +                  // 3: bold __x__ (group 4 = inner)
        "|(\\*([^*]+)\\*)" +                // 5: italic *x* (group 6 = inner)
        "|(_([^_]+)_)" +                    // 7: italic _x_ (group 8 = inner)
        "|(\\[([^\\]]+)\\]\\(([^)]+)\\))",  // 9: link [t](u) (10 = text, 11 = url)
)

private fun renderInline(text: String): AnnotatedString = buildAnnotatedString {
    var pos = 0
    InlineToken.findAll(text).forEach { match ->
        if (match.range.first > pos) {
            append(text.substring(pos, match.range.first))
        }
        val whole = match.value
        when {
            whole.startsWith("`") && whole.endsWith("`") -> {
                withStyle(
                    SpanStyle(
                        background = Color(0x33FFFFFF),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    ),
                ) { append(whole.removeSurrounding("`")) }
            }
            whole.startsWith("**") && whole.endsWith("**") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append(whole.removeSurrounding("**"))
                }
            }
            whole.startsWith("__") && whole.endsWith("__") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append(whole.removeSurrounding("__"))
                }
            }
            whole.startsWith("*") && whole.endsWith("*") -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(whole.removeSurrounding("*"))
                }
            }
            whole.startsWith("_") && whole.endsWith("_") -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(whole.removeSurrounding("_"))
                }
            }
            whole.startsWith("[") -> {
                val linkMatch = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)").matchEntire(whole)
                if (linkMatch != null) {
                    val display = linkMatch.groupValues[1]
                    withStyle(
                        SpanStyle(
                            color = KiroColors.Accent2,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ) { append(display) }
                } else {
                    append(whole)
                }
            }
            else -> append(whole)
        }
        pos = match.range.last + 1
    }
    if (pos < text.length) append(text.substring(pos))
}
