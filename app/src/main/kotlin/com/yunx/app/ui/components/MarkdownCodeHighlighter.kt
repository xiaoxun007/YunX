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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.buildAnnotatedString

/**
 * 简易代码块语法高亮（自研正则，非完整解析器）。
 *
 * 仅覆盖常见语言的关键词/字符串/注释/数字着色，保证可读性；
 * 未识别语言回退等宽纯文本。颜色为调色板常量，跟随 Material 浅色/深色均可用。
 */
object MarkdownCodeHighlighter {
    private val commentColor = Color(0xFF6A9955)   // 注释绿
    private val stringColor = Color(0xFFCE9178)   // 字符串橙
    private val keywordColor = Color(0xFF569CD6)   // 关键字蓝
    private val numberColor = Color(0xFFB5CEA8)    // 数字浅绿
    private val typeColor = Color(0xFF4EC9B0)      // 类型青

    /** 各语言关键字集合（小写匹配） */
    private val keywords = setOf(
        "kotlin", "java", "python", "shell", "bash", "sh", "zsh", "json", "yaml", "yml",
        "ini", "toml", "c", "cpp", "c++", "h", "hpp", "typescript", "ts", "javascript", "js",
        "go", "rust", "rs", "sql", "xml", "html", "css", "markdown", "md"
    )

    /** 通用保留字（按语言族近似合并） */
    private val commonKeywords = setOf(
        "fun", "val", "var", "class", "object", "interface", "enum", "data", "sealed",
        "return", "if", "else", "when", "for", "while", "do", "break", "continue",
        "import", "package", "as", "is", "in", "out", "by", "lazy", "override", "open",
        "private", "public", "protected", "internal", "final", "abstract", "suspend",
        "null", "true", "false", "this", "super", "void", "int", "long", "short",
        "byte", "char", "float", "double", "boolean", "string", "new", "throw",
        "try", "catch", "finally", "static", "def", "elif", "endif", "echo", "then",
        "fi", "esac", "case", "select", "export", "local", "func", "func.", "fn",
        "let", "const", "var.", "typeof", "instanceof", "nil", "None", "True", "False",
        "print", "println", "println!"
    )

    /**
     * 对代码文本生成带颜色的 AnnotatedString。未识别语言返回纯文本（等宽）。
     */
    fun highlight(code: String, language: String?): AnnotatedString = buildAnnotatedString {
        if (language == null || language.lowercase() !in keywords) {
            // 未识别：纯等宽文本
            append(code, SpanStyle(fontFamily = FontFamily.Monospace))
            return@buildAnnotatedString
        }
        // 简化：按行扫描，行注释（// 或 # 或 /* */ 单行）、字符串、数字、关键字着色
        val lines = code.lines()
        lines.forEachIndexed { idx, line ->
            highlightLine(line)
            if (idx < lines.size - 1) append("\n")
        }
    }

    private fun AnnotatedString.Builder.highlightLine(line: String) {
        // token 正则：字符串("..." / '...' / `...`)、注释(//... 或 #...)、数字、关键字
        val tokenRegex = Regex(
            """("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')""" +   // 字符串
                """|(\/\/[^\n]*|#[^\n]*)""" +                  // 行注释
                """|(\b\d+(?:\.\d+)?\b)""" +                  // 数字
                """|\b([A-Za-z_][A-Za-z0-9_]*)\b"""            // 标识符
        )
        var last = 0
        for (m in tokenRegex.findAll(line)) {
            if (m.range.first > last) append(line.substring(last, m.range.first), SpanStyle(fontFamily = FontFamily.Monospace))
            val s = m.value
            when {
                s.startsWith("\"") || s.startsWith("'") ->
                    append(s, SpanStyle(fontFamily = FontFamily.Monospace, color = stringColor))
                s.startsWith("//") || s.startsWith("#") ->
                    append(s, SpanStyle(fontFamily = FontFamily.Monospace, color = commentColor))
                s.matches(Regex("\\b\\d+(\\.\\d+)?\\b")) ->
                    append(s, SpanStyle(fontFamily = FontFamily.Monospace, color = numberColor))
                s.lowercase() in commonKeywords ->
                    append(s, SpanStyle(fontFamily = FontFamily.Monospace, color = keywordColor))
                // 首字母大写标识符近似当作类型
                s.firstOrNull()?.isUpperCase() == true ->
                    append(s, SpanStyle(fontFamily = FontFamily.Monospace, color = typeColor))
                else ->
                    append(s, SpanStyle(fontFamily = FontFamily.Monospace))
            }
            last = m.range.last + 1
        }
        if (last < line.length) append(line.substring(last), SpanStyle(fontFamily = FontFamily.Monospace))
    }
}
