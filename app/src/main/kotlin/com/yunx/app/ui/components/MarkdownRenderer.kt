/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunx.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * 轻量 Markdown 渲染器（自研，无第三方依赖）。
 *
 * 设计目标：用于 GitHub README 富文本展示。项目 APK 已 R8 瘦身至 ~4MB，
 * 引入完整 Markdown 库会显著增大体积，故只实现 README 常见的块级/行内元素。
 *
 * 降级策略：
 * - 表格：不解析对齐，逐行等宽文本展示，保证可读；
 * - 图片：![alt](url) 渲染为「[图片] alt」占位，不加载网络图（避免乱起网络请求）；
 * - 未闭合的 ** 或 ` 原样显示，不崩溃；
 * - HTML 标签不解析，原样显示为文本。
 */

/** 块级 Markdown 元素 */
sealed interface MarkdownBlock {
    /** 标题（level 1~6） */
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    /** 普通段落 */
    data class Paragraph(val text: String) : MarkdownBlock
    /** 围栏代码块（```） */
    data class CodeBlock(val language: String?, val code: String) : MarkdownBlock
    /** 引用（>） */
    data class Blockquote(val text: String) : MarkdownBlock
    /** 无序列表项（- * +） */
    data class UnorderedListItem(val text: String) : MarkdownBlock
    /** 有序列表项（1.） */
    data class OrderedListItem(val number: Int, val text: String) : MarkdownBlock
    /** 水平线（--- ***） */
    data object HorizontalRule : MarkdownBlock
    /** 表格行集合（降级：原样文本） */
    data class Table(val rows: List<String>) : MarkdownBlock
}

/**
 * 块级 Markdown 解析器。按行扫描，空行分隔块；代码块内内容原样保留。
 * 边界：空文本返回空列表；未闭合的 ``` 当普通段落处理；不抛异常。
 */
fun parseMarkdown(text: String): List<MarkdownBlock> {
    val lines = text.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var i = 0
    // 当前累积的段落行（连续普通行合并为一个 Paragraph）
    val paraBuf = mutableListOf<String>()

    fun flushParagraph() {
        if (paraBuf.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(paraBuf.joinToString("\n")))
            paraBuf.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // 空行：结束当前块
        if (trimmed.isEmpty()) {
            flushParagraph()
            i++
            continue
        }

        // 围栏代码块
        if (trimmed.startsWith("```")) {
            flushParagraph()
            val lang = trimmed.removePrefix("```").trim().ifBlank { null }
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            i++ // 跳过结束 ```
            blocks.add(MarkdownBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            continue
        }

        // 标题（# ~ ######）
        val headingMatch = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
        if (headingMatch != null) {
            flushParagraph()
            blocks.add(MarkdownBlock.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2]))
            i++
            continue
        }

        // 水平线（整行 --- 或 ***）
        if (trimmed.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$"))) {
            flushParagraph()
            blocks.add(MarkdownBlock.HorizontalRule)
            i++
            continue
        }

        // 引用
        if (trimmed.startsWith(">")) {
            flushParagraph()
            blocks.add(MarkdownBlock.Blockquote(trimmed.removePrefix(">").trim()))
            i++
            continue
        }

        // 表格行（连续以 | 开头的行）——降级收集
        if (trimmed.startsWith("|")) {
            flushParagraph()
            val tableRows = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith("|")) {
                tableRows.add(lines[i].trim())
                i++
            }
            blocks.add(MarkdownBlock.Table(tableRows))
            continue
        }

        // 无序列表
        val ulMatch = Regex("^[-*+]\\s+(.*)$").find(trimmed)
        if (ulMatch != null) {
            flushParagraph()
            blocks.add(MarkdownBlock.UnorderedListItem(ulMatch.groupValues[1]))
            i++
            continue
        }

        // 有序列表
        val olMatch = Regex("^(\\d+)\\.\\s+(.*)$").find(trimmed)
        if (olMatch != null) {
            flushParagraph()
            blocks.add(MarkdownBlock.OrderedListItem(olMatch.groupValues[1].toInt(), olMatch.groupValues[2]))
            i++
            continue
        }

        // 普通行：累积到段落
        paraBuf.add(line)
        i++
    }
    flushParagraph()
    return blocks
}

/**
 * 行内解析：把 **bold**、*italic*、`code`、[text](url)、~~del~~、![alt](url) 转成 AnnotatedString。
 * 未闭合的标记原样保留；链接回调由调用方提供（用于补全相对链接）。
 */
private fun buildInlineAnnotated(
    text: String,
    linkColor: androidx.compose.ui.graphics.Color,
    onLink: (String) -> Unit
): AnnotatedString = buildAnnotatedString {
    var remaining = text
    // 用一个统一扫描：依次尝试匹配标记，按出现顺序消费
    val tokenRegex = Regex(
        """(\*\*[^*]+\*\*)|(\*[^*\s][^*]*\*)|(`[^`]+`)|(\[[^\]]+\]\([^)]+\))|(~~[^~]+~~)|(!\[[^\]]*\]\([^)]+\))"""
    )
    var lastIndex = 0
    for (m in tokenRegex.findAll(text)) {
        // 匹配前的普通文本
        if (m.range.first > lastIndex) append(text.substring(lastIndex, m.range.first))
        val token = m.value
        when {
            token.startsWith("**") && token.endsWith("**") -> {
                append(token.removeSurrounding("**"), SpanStyle(fontWeight = FontWeight.Bold))
            }
            token.startsWith("`") && token.endsWith("`") -> {
                append(token.removeSurrounding("`"), SpanStyle(fontFamily = FontFamily.Monospace))
            }
            token.startsWith("![") -> {
                // 图片：![alt](url) → 「[图片] alt」
                val alt = Regex("""!\[([^\]]*)\]""").find(token)?.groupValues?.get(1).orEmpty()
                append(if (alt.isNotBlank()) "[图片] $alt" else "[图片]", SpanStyle(color = linkColor))
            }
            token.startsWith("[") -> {
                val lm = Regex("""\[([^\]]+)\]\(([^)]+)\)""").find(token)
                if (lm != null) {
                    val label = lm.groupValues[1]
                    val url = lm.groupValues[2]
                    pushStringAnnotation(tag = "URL", annotation = url)
                    append(label, SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                    pop()
                } else {
                    append(token)
                }
            }
            token.startsWith("~~") && token.endsWith("~~") -> {
                append(token.removeSurrounding("~~"), SpanStyle(textDecoration = TextDecoration.LineThrough))
            }
            token.startsWith("*") && token.endsWith("*") && token.length >= 2 -> {
                append(token.removeSurrounding("*"), SpanStyle(fontStyle = FontStyle.Italic))
            }
            else -> append(token)
        }
        lastIndex = m.range.last + 1
    }
    if (lastIndex < text.length) append(text.substring(lastIndex))
}

/**
 * 渲染 Markdown 文本。链接点击通过外部 Intent 打开；相对链接自动补全为
 * `https://github.com/{owner}/{repo}/blob/{branch}/{相对路径}`。
 *
 * 注意：本 Composable 不自带滚动，应放在 LazyColumn 的 item 或可滚动 Column 内。
 */
@Composable
fun MarkdownRenderer(
    text: String,
    repoOwner: String,
    repoName: String,
    defaultBranch: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val linkColor = MaterialTheme.colorScheme.primary
    val blocks = remember(text) { parseMarkdown(text) }

    fun resolveUrl(raw: String): String {
        // 外链直接用；相对路径补全为 GitHub blob 链接
        if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("mailto:")) return raw
        val path = raw.removePrefix("./").removePrefix("/")
        return "https://github.com/$repoOwner/$repoName/blob/$defaultBranch/$path"
    }

    fun openLink(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(resolveUrl(url))))
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.bodyLarge
                    }
                    Text(
                        text = block.text,
                        style = style,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                is MarkdownBlock.Paragraph -> {
                    val annotated = buildInlineAnnotated(block.text, linkColor, ::openLink)
                    ClickableText(
                        text = annotated,
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                        onClick = { offset ->
                            annotated.getStringAnnotations("URL", offset, offset)
                                .firstOrNull()?.let { openLink(it.item) }
                        },
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                is MarkdownBlock.CodeBlock -> {
                    Column(
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(
                            text = block.code,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                is MarkdownBlock.Blockquote -> {
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Spacer(
                            Modifier
                                .width(3.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                                .height(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        val annotated = buildInlineAnnotated(block.text, linkColor, ::openLink)
                        ClickableText(
                            text = annotated,
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                            onClick = { offset ->
                                annotated.getStringAnnotations("URL", offset, offset)
                                    .firstOrNull()?.let { openLink(it.item) }
                            },
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                is MarkdownBlock.UnorderedListItem -> {
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        Text(text = "•", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(6.dp))
                        val annotated = buildInlineAnnotated(block.text, linkColor, ::openLink)
                        ClickableText(
                            text = annotated,
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                            onClick = { offset ->
                                annotated.getStringAnnotations("URL", offset, offset)
                                    .firstOrNull()?.let { openLink(it.item) }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is MarkdownBlock.OrderedListItem -> {
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        Text(text = "${block.number}.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(6.dp))
                        val annotated = buildInlineAnnotated(block.text, linkColor, ::openLink)
                        ClickableText(
                            text = annotated,
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                            onClick = { offset ->
                                annotated.getStringAnnotations("URL", offset, offset)
                                    .firstOrNull()?.let { openLink(it.item) }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                MarkdownBlock.HorizontalRule -> {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                is MarkdownBlock.Table -> {
                    Column(
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp)
                    ) {
                        block.rows.forEach { row ->
                            Text(
                                text = row,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}
