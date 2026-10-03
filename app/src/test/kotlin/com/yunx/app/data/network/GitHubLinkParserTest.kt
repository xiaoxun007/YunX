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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHubLinkParser 连字符规范化（\p{Pd} → ASCII '-'）单测。
 * 覆盖 owner / repo / release tag / raw path 四个位置的 Unicode 破折号变体，
 * 以及纯 ASCII 链接的回归（规范化前后解析结果一致）。
 */
class GitHubLinkParserTest {

    // 9 个 Unicode 破折号变体（\p{Pd} 家族；手写 charArray 旧表漏了 \uFE58 之外的覆盖已由 \p{Pd} 统一）
    private val dashVariants = listOf(
        '‑', '‐', '‒', '–', '—', '―', '−', '﹣', '－'
    )

    @Test
    fun `owner 位置 unicode 破折号规范化为 ASCII 连字符`() {
        dashVariants.forEach { d ->
            val parsed = GitHubLinkParser.parse("https://github.com/my${d}user/repo")
            assertTrue("owner 位置变体 U+${Integer.toHexString(d.code)} 应解析为 Repository", parsed is GitHubLinkType.Repository)
            assertEquals("my-user", (parsed as GitHubLinkType.Repository).owner)
            assertEquals("repo", parsed.repo)
        }
    }

    @Test
    fun `repo 位置 unicode 破折号规范化为 ASCII 连字符`() {
        dashVariants.forEach { d ->
            val parsed = GitHubLinkParser.parse("https://github.com/owner/my${d}repo")
            assertTrue("repo 位置变体 U+${Integer.toHexString(d.code)} 应解析为 Repository", parsed is GitHubLinkType.Repository)
            assertEquals("owner", (parsed as GitHubLinkType.Repository).owner)
            assertEquals("my-repo", parsed.repo)
        }
    }

    @Test
    fun `release tag 位置 unicode 破折号规范化并识别为直链文件`() {
        dashVariants.forEach { d ->
            val parsed = GitHubLinkParser.parse("https://github.com/owner/repo/releases/download/v1${d}0/app.apk")
            assertTrue("tag 位置变体 U+${Integer.toHexString(d.code)} 应解析为 DirectFile", parsed is GitHubLinkType.DirectFile)
            assertEquals("app.apk", (parsed as GitHubLinkType.DirectFile).fileName)
        }
    }

    @Test
    fun `raw path 位置 unicode 破折号规范化为 ASCII 连字符文件名`() {
        dashVariants.forEach { d ->
            val parsed = GitHubLinkParser.parse("https://raw.githubusercontent.com/owner/repo/main/foo${d}bar.md")
            assertTrue("raw path 位置变体 U+${Integer.toHexString(d.code)} 应解析为 DirectFile", parsed is GitHubLinkType.DirectFile)
            assertEquals("foo-bar.md", (parsed as GitHubLinkType.DirectFile).fileName)
        }
    }

    @Test
    fun `纯 ASCII 链接解析结果与未规范化前一致（回归）`() {
        val repo = GitHubLinkParser.parse("https://github.com/owner/my-repo") as GitHubLinkType.Repository
        assertEquals("owner", repo.owner)
        assertEquals("my-repo", repo.repo)

        val direct = GitHubLinkParser.parse("https://raw.githubusercontent.com/owner/my-repo/main/read-me.md") as GitHubLinkType.DirectFile
        assertEquals("read-me.md", direct.fileName)
    }
}
