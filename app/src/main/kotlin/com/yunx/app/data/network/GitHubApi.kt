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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * GitHub REST API 封装（匿名 / 可选 Token）。
 *
 * - 未配置 Token：限额 60 次/小时/IP；
 * - 配置 Token：限额提升至 5000 次/小时；
 * - Token 仅用于提升限额，不用于登录态；严禁打印到日志。
 *
 * 所有方法均运行在 [Dispatchers.IO]，失败（网络异常 / 非 2xx / JSON 解析失败）返回 null。
 */
class GitHubApi(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() },
    private val tokenProvider: () -> String? = { null }
) {
    /** 每次请求动态获取全局客户端（忽略 SSL 开关切换即时生效） */
    private val client get() = clientProvider()

    /** 获取单个仓库信息：GET /repos/{owner}/{repo} */
    suspend fun getRepo(owner: String, repo: String): GitHubRepo? = withContext(Dispatchers.IO) {
        runCatching {
            val json = requestJson("https://api.github.com/repos/$owner/$repo")
                ?: return@withContext null
            parseRepo(json)
        }.getOrNull()
    }

    /**
     * 获取目录树（仅当前层，**不加 recursive=1**，由浏览层按需进入子目录）。
     * GET /repos/{owner}/{repo}/git/trees/{sha}
     */
    suspend fun getTree(owner: String, repo: String, sha: String): List<GitHubTreeEntry>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = requestJson("https://api.github.com/repos/$owner/$repo/git/trees/$sha")
                    ?: return@withContext null
                val arr = json.optJSONArray("tree") ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(
                            GitHubTreeEntry(
                                path = o.optString("path"),
                                type = o.optString("type"),
                                sha = o.optString("sha"),
                                size = if (o.isNull("size")) null else o.optLong("size")
                            )
                        )
                    }
                }
            }.getOrNull()
        }

    /** Releases 列表（分页，per_page=100）：GET /repos/{owner}/{repo}/releases */
    suspend fun getReleases(owner: String, repo: String, page: Int = 1): List<GitHubRelease>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val arr = requestJsonArray(
                    "https://api.github.com/repos/$owner/$repo/releases?per_page=100&page=$page"
                ) ?: return@withContext null
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(parseRelease(o))
                    }
                }
            }.getOrNull()
        }

    /** 用户公开仓库列表（分页）：GET /users/{owner}/repos?sort=updated */
    suspend fun getUserRepos(owner: String, page: Int = 1): List<GitHubRepo>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val arr = requestJsonArray(
                    "https://api.github.com/users/$owner/repos?per_page=100&page=$page&sort=updated"
                ) ?: return@withContext null
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(parseRepo(o))
                    }
                }
            }.getOrNull()
        }

    /** 组织公开仓库列表（分页）：GET /orgs/{owner}/repos?sort=updated */
    suspend fun getOrgRepos(owner: String, page: Int = 1): List<GitHubRepo>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val arr = requestJsonArray(
                    "https://api.github.com/orgs/$owner/repos?per_page=100&page=$page&sort=updated"
                ) ?: return@withContext null
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(parseRepo(o))
                    }
                }
            }.getOrNull()
        }

    // ---------- 内部解析 ----------

    private fun parseRepo(o: JSONObject): GitHubRepo {
        val parent = o.optJSONObject("parent")
        return GitHubRepo(
            name = o.optString("name"),
            fullName = o.optString("full_name"),
            description = o.optString("description").ifBlank { null },
            fork = o.optBoolean("fork", false),
            parentFullName = parent?.optString("full_name")?.takeIf { it.isNotBlank() },
            defaultBranch = o.optString("default_branch").ifBlank { "main" }
        )
    }

    private fun parseRelease(o: JSONObject): GitHubRelease {
        val assetsArr = o.optJSONArray("assets")
        val assets = buildList {
            if (assetsArr != null) {
                for (i in 0 until assetsArr.length()) {
                    val a = assetsArr.optJSONObject(i) ?: continue
                    add(
                        GitHubAsset(
                            name = a.optString("name"),
                            downloadUrl = a.optString("browser_download_url"),
                            size = a.optLong("size"),
                            contentType = a.optString("content_type").ifBlank { null }
                        )
                    )
                }
            }
        }
        return GitHubRelease(
            tagName = o.optString("tag_name"),
            name = o.optString("name").ifBlank { null },
            publishedAt = o.optString("published_at").ifBlank { null },
            assets = assets
        )
    }

    // ---------- 请求执行 ----------

    /** 构建带鉴权头的 Request 并执行，返回 JSONObject；非 2xx / 空响应 / 解析失败返回 null */
    private fun requestJson(url: String): JSONObject? {
        val request = buildRequest(url)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return runCatching { JSONObject(body) }.getOrNull()
        }
    }

    /** 构建带鉴权头的 Request 并执行，返回 JSONArray；非 2xx / 空响应 / 解析失败返回 null */
    private fun requestJsonArray(url: String): JSONArray? {
        val request = buildRequest(url)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return runCatching { JSONArray(body) }.getOrNull()
        }
    }

    private fun buildRequest(url: String): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "YunX")
            .header("Accept", "application/vnd.github+json")
            .get()
        // Token 非空时携带 Authorization: Bearer（仅提升限额，不打印）
        val token = tokenProvider()
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder.build()
    }
}
