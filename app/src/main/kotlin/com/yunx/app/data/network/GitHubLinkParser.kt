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

package com.yunx.app.data.network

/**
 * GitHub 链接解析结果。
 * - [Repository]：仓库链接（含 tree / blob / releases 等子路径），进入仓库浏览；
 * - [Account]：用户 / 组织主页链接，进入其仓库列表；
 * - [DirectFile]：文件直链，直接触发下载。
 */
sealed class GitHubLinkType {
    /** 仓库链接；subPath 为 tree/blob/releases 后的路径部分（可空） */
    data class Repository(val owner: String, val repo: String, val subPath: String?) : GitHubLinkType()

    /** 用户 / 组织主页链接 */
    data class Account(val owner: String) : GitHubLinkType()

    /** 文件直链（raw / releases download / github raw），直接下载 */
    data class DirectFile(val url: String, val fileName: String) : GitHubLinkType()
}

/**
 * 从一段文本中识别 GitHub 链接。
 * 匹配优先级：DirectFile > Repository > Account，都不匹配返回 null。
 */
object GitHubLinkParser {

    private val urlRegex = Regex("""https?://[^\s]+""")

    // owner/repo 字符集：\p{Pd} 是 Unicode Dash 通用属性，覆盖 ASCII '-' 及 U+2010~U+2015、
    // U+2212、U+FE58、U+FE63、U+FF0D 等全部连字符家族——保护真用特殊连字符命名的仓库原样识别。
    private val NAME = """[A-Za-z0-9_.\p{Pd}]"""

    // raw.githubusercontent.com/{owner}/{repo}/{branch}/{path...}
    private val rawRegex = Regex(
        """raw\.githubusercontent\.com/($NAME+)/($NAME+)/[^/]+/(.+)""",
        RegexOption.IGNORE_CASE
    )

    // github.com/{owner}/{repo}/releases/download/{tag}/{file...}
    private val releaseDownloadRegex = Regex(
        """github\.com/($NAME+)/($NAME+)/releases/download/[^/]+/(.+)""",
        RegexOption.IGNORE_CASE
    )

    // github.com/{owner}/{repo}/raw/{branch}/{path...}（会 302 到 raw.githubusercontent.com）
    private val githubRawRegex = Regex(
        """github\.com/($NAME+)/($NAME+)/raw/[^/]+/(.+)""",
        RegexOption.IGNORE_CASE
    )

    // github.com/{owner}/{repo}（及其后续子路径 tree/blob/releases 等）
    private val repoRegex = Regex(
        """github\.com/($NAME+)/($NAME+)(?:/(.*))?""",
        RegexOption.IGNORE_CASE
    )

    // github.com/{owner}（仅一段路径）
    private val accountRegex = Regex(
        """github\.com/($NAME+)/?(?:[?#]|$)""",
        RegexOption.IGNORE_CASE
    )

    // github.com 本身的功能/营销页路径，不应识别为账号
    private val specialPaths = setOf(
        "features", "topics", "trending", "collections", "events", "sponsors",
        "about", "pricing", "login", "signup", "join", "marketplace", "explore",
        "notifications", "settings", "new", "orgs", "sites", "customer-stories",
        "team", "enterprise", "readme", "contact", "security", "resources"
    )

    fun parse(text: String): GitHubLinkType? {
        val rawUrl = urlRegex.find(text.trim())?.value
            ?.trimEnd('。', '，', ',', '；', ';', ')', ']', '}', '"', '\'')
            ?: return null

        // 实测结论：GitHub 仓库名/用户名**不允许** Unicode dash（带 U+2011 的请求 → HTTP 404，
        // 转 ASCII '-' 后 → 200），带污染字符的链接全是微信/豆包复制污染，转 ASCII 永远正确、
        // 不存在"作者真用特殊连字符命名"的场景。因此统一先把连字符家族规范化为 ASCII '-' 再匹配，
        // 输出给调用方的 owner/repo/url 一律是 ASCII。
        // 正则字符集保留 \p{Pd} 扩展，仅用于容错识别（即使未规范化的链接也能被认出是 GitHub 链接）。
        val url = normalizeDashesToAscii(rawUrl)
        return matchRules(url)
    }

    /** 按 DirectFile > Repository > Account 优先级跑全部规则 */
    private fun matchRules(url: String): GitHubLinkType? {
        // 1) 文件直链（优先级最高）
        rawRegex.find(url)?.let { m ->
            fileNameOf(m.groupValues[3])?.let { name ->
                return GitHubLinkType.DirectFile(url, name)
            }
        }
        releaseDownloadRegex.find(url)?.let { m ->
            fileNameOf(m.groupValues[3])?.let { name ->
                return GitHubLinkType.DirectFile(url, name)
            }
        }
        githubRawRegex.find(url)?.let { m ->
            fileNameOf(m.groupValues[3])?.let { name ->
                return GitHubLinkType.DirectFile(url, name)
            }
        }

        // 2) 仓库链接
        repoRegex.find(url)?.let { m ->
            val owner = m.groupValues[1]
            // owner 是 GitHub 功能页（features/topics/trending 等）时，github.com/{owner}/{repo}
            // 实际是功能页带子路径，既非仓库也非用户/组织，直接丢弃，避免后续 getRepo 无谓报错。
            if (owner.lowercase() in specialPaths) return null
            val repo = m.groupValues[2]
            val subPath = m.groupValues[3]
                .substringBefore('?').substringBefore('#')
                .takeIf { it.isNotBlank() }
            return GitHubLinkType.Repository(owner, repo, subPath)
        }

        // 3) 账号 / 组织链接
        accountRegex.find(url)?.let { m ->
            val owner = m.groupValues[1]
            if (owner.lowercase() in specialPaths) return null
            return GitHubLinkType.Account(owner)
        }

        return null
    }

    /** 把 Unicode 连字符家族统一替换为 ASCII '-'；无变化时返回原串 */
    private fun normalizeDashesToAscii(url: String): String {
        val chars = charArrayOf(
            '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015',
            '\u2212', '\ufe58', '\ufe63', '\uff0d'
        )
        var out = url
        for (c in chars) out = out.replace(c, '-')
        return out
    }

    /** 从路径段取最后一段作为文件名，去掉 query/fragment；空则返回 null */
    private fun fileNameOf(path: String): String? {
        val clean = path.substringBefore('?').substringBefore('#')
        val name = clean.substringAfterLast('/')
        return name.takeIf { it.isNotBlank() }
    }
}
