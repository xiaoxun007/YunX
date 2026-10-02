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

import android.util.Log
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
 * - Token 仅用于提升限额，不用于登录态；严禁打印到日志（日志只带 withToken 布尔）。
 *
 * 所有方法均运行在 [Dispatchers.IO]。
 */
class GitHubApi(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() },
    private val tokenProvider: () -> String? = { null }
) {
    /** 每次请求动态获取全局客户端（忽略 SSL 开关切换即时生效） */
    private val client get() = clientProvider()

    private companion object {
        const val TAG = "GitHubApi"
    }

    /** 成功响应；失败时携带原因（HTTP 状态码或网络异常），便于 UI 给出可诊断提示 */
    sealed interface RepoResult {
        data class Success(val repo: GitHubRepo) : RepoResult
        data class Failure(
            /** HTTP 状态码；网络异常时为 null */
            val code: Int?,
            /** true = 网络层异常（IO/超时/断网），false = HTTP 非 2xx */
            val networkError: Boolean
        ) : RepoResult
    }

    /** 校验未保存 Token 的结果（保存前先校验，拦住乱填的无效 Token） */
    sealed interface TokenCheck {
        data class Valid(val login: String) : TokenCheck
        data object Invalid : TokenCheck
        data object Unknown : TokenCheck
    }

    /**
     * 携带的 Token 被 GitHub 拒绝（HTTP 401）时回调，只回调一次。
     *
     * 为什么必须处理：GitHub 对带**无效** Authorization 头的一切请求都返回 401，
     * 连公开仓库/Release 解析也会全量失败（不只是限额问题），所以上层要清除这个 Token 才能恢复匿名访问。
     */
    var onUnauthorized: (() -> Unit)? = null

    private var unauthorizedNotified = false

    /** 统一检查响应码：401 → 清空缓存（失败条目会短缓存，不清会让「修好之后」仍失败）并通知上层 */
    private fun noteResponseCode(code: Int) {
        if (code != 401 || unauthorizedNotified) return
        unauthorizedNotified = true
        GitHubResponseCache.clear()
        onUnauthorized?.invoke()
    }

    /**
     * 获取单个仓库信息：GET /repos/{owner}/{repo}（结果经统一缓存）。
     * 失败原因透传给调用方，用于在 UI 上区分 404/403/网络异常。
     */
    suspend fun getRepoResult(owner: String, repo: String): RepoResult = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$owner/$repo"
        when (val out = cachedBody("repo:$owner/$repo", url)) {
            is GitHubResponseCache.FetchResult.Ok -> runCatching {
                RepoResult.Success(parseRepo(JSONObject(out.body)))
            }.getOrDefault(RepoResult.Failure(code = null, networkError = false))
            is GitHubResponseCache.FetchResult.HttpError -> RepoResult.Failure(out.code, networkError = false)
            GitHubResponseCache.FetchResult.NetworkError -> RepoResult.Failure(code = null, networkError = true)
        }
    }

    /** 兼容旧调用点：只取仓库对象，失败返回 null（不区分原因） */
    suspend fun getRepo(owner: String, repo: String): GitHubRepo? =
        (getRepoResult(owner, repo) as? RepoResult.Success)?.repo

    /**
     * 获取目录树（仅当前层，**不加 recursive=1**，由浏览层按需进入子目录）。
     * GET /repos/{owner}/{repo}/git/trees/{sha}（结果经统一缓存）。
     */
    suspend fun getTree(owner: String, repo: String, sha: String): List<GitHubTreeEntry>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = (cachedBody(
                    "tree:$owner/$repo/$sha",
                    "https://api.github.com/repos/$owner/$repo/git/trees/$sha"
                ) as? GitHubResponseCache.FetchResult.Ok)?.body ?: return@withContext null
                val arr = JSONObject(raw).optJSONArray("tree") ?: return@runCatching emptyList()
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

    /** Releases 列表（分页，per_page=100）：GET /repos/{owner}/{repo}/releases（结果经统一缓存） */
    suspend fun getReleases(owner: String, repo: String, page: Int = 1): List<GitHubRelease>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = (cachedBody(
                    "releases:$owner/$repo:$page",
                    "https://api.github.com/repos/$owner/$repo/releases?per_page=100&page=$page"
                ) as? GitHubResponseCache.FetchResult.Ok)?.body ?: return@withContext null
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(parseRelease(o))
                    }
                }
            }.getOrNull()
        }

    /** 用户公开仓库列表（分页）：GET /users/{owner}/repos?sort=updated（带 Token 指纹 key，结果经缓存） */
    suspend fun getUserRepos(owner: String, page: Int = 1): List<GitHubRepo>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = (cachedBody(
                    "userrepos:$owner:$page:${tokenFingerprint()}",
                    "https://api.github.com/users/$owner/repos?per_page=100&page=$page&sort=updated"
                ) as? GitHubResponseCache.FetchResult.Ok)?.body ?: return@withContext null
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(parseRepo(o))
                    }
                }
            }.getOrNull()
        }

    /** 组织公开仓库列表（分页）：GET /orgs/{owner}/repos?sort=updated（带 Token 指纹 key，结果经缓存） */
    suspend fun getOrgRepos(owner: String, page: Int = 1): List<GitHubRepo>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = (cachedBody(
                    "orgrepos:$owner:$page:${tokenFingerprint()}",
                    "https://api.github.com/orgs/$owner/repos?per_page=100&page=$page&sort=updated"
                ) as? GitHubResponseCache.FetchResult.Ok)?.body ?: return@withContext null
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(parseRepo(o))
                    }
                }
            }.getOrNull()
        }

    /**
     * 判断 owner 是用户还是组织：GET /users/{owner}，取返回对象的 type 字段（结果经缓存）。
     * @return "User" / "Organization"；owner 不存在或网络失败返回 null（调用方按失败兜底）。
     */
    suspend fun getUserType(owner: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = (cachedBody("usertype:$owner", "https://api.github.com/users/$owner")
                    as? GitHubResponseCache.FetchResult.Ok)?.body ?: return@withContext null
                JSONObject(raw).optString("type").takeIf { it.isNotBlank() }
            }.getOrNull()
        }

    /**
     * 获取当前 Token 对应用户的登录名：GET /user（需 Bearer token），取 login 字段（带 Token 指纹 key）。
     * 未配置 Token / 无效 Token / 网络失败返回 null。
     */
    suspend fun getUserLogin(): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = (cachedBody("userlogin:${tokenFingerprint()}", "https://api.github.com/user")
                    as? GitHubResponseCache.FetchResult.Ok)?.body ?: return@withContext null
                JSONObject(raw).optString("login").takeIf { it.isNotBlank() }
            }.getOrNull()
        }

    /**
     * 获取仓库 README 原文（Markdown）。
     * - 优先 GET /repos/{owner}/{repo}/readme，Accept: application/vnd.github.raw（返回纯文本，自动识别 README.md/readme.rst 等）；
     * - 404 视为无 README，返回 null；
     * - API 网络失败时兜底请求 raw.githubusercontent.com/{owner}/{repo}/{defaultBranch}/README.md；
     * - 全部失败返回 null，不抛异常。
     */
    suspend fun getReadme(owner: String, repo: String, defaultBranch: String): String? =
        withContext(Dispatchers.IO) {
            // README 原文缓存；>128KB 不写缓存（防超大 README 占内存，实际极少超）。
            // 返回 FetchResult：HTTP 404 等短缓存（确定无 README），网络异常不缓存（下次重试）。
            GitHubResponseCache.getOrFetch("readme:$owner/$repo/$defaultBranch", maxBytes = 128 * 1024) {
                val started = System.currentTimeMillis()
                val withToken = !tokenProvider().isNullOrBlank()
                // 1) API readme 接口（原始 Markdown）
                val apiRequest = Request.Builder()
                    .url("https://api.github.com/repos/$owner/$repo/readme")
                    .header("User-Agent", "YunX")
                    .header("Accept", "application/vnd.github.raw")
                    .get()
                    .also { b ->
                        tokenProvider()?.takeIf { it.isNotBlank() }?.let { b.header("Authorization", "Bearer $it") }
                    }
                    .build()
                var lastHttp: Int? = null
                try {
                    client.newCall(apiRequest).execute().use { resp ->
                        lastHttp = resp.code
                        if (resp.isSuccessful) {
                            val body = resp.body?.string()
                            if (!body.isNullOrBlank()) {
                                logReq("readme(api)", resp.code, started, withToken)
                                return@getOrFetch GitHubResponseCache.FetchResult.Ok(body)
                            }
                        }
                    }
                    // 2) 兜底：raw README.md
                    client.newCall(buildRequest(
                        "https://raw.githubusercontent.com/$owner/$repo/$defaultBranch/README.md"
                    )).execute().use { resp ->
                        lastHttp = resp.code
                        logReq("readme(raw)", resp.code, started, withToken)
                        if (resp.isSuccessful) {
                            val body = resp.body?.string()
                            if (!body.isNullOrBlank()) {
                                return@getOrFetch GitHubResponseCache.FetchResult.Ok(body)
                            }
                        }
                    }
                    // 两个源都非 2xx：确定无 README，短缓存（10s 后仍可重试）
                    GitHubResponseCache.FetchResult.HttpError(lastHttp ?: -1)
                } catch (e: Exception) {
                    logNetErr("readme", e)
                    GitHubResponseCache.FetchResult.NetworkError
                }
            }
        }

    /**
     * 获取某路径最后一次提交的时间（ISO8601，如 "2026-09-20T10:30:00Z"）。
     * GET /repos/{owner}/{repo}/commits?path={path}&per_page=1，取 [0].commit.committer.date。
     * 任何失败（404/401/403/超时/网络错误/空数组）一律返回 null，不抛异常。
     */
    suspend fun getLastCommitDate(owner: String, repo: String, path: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://api.github.com/repos/$owner/$repo/commits?path=${java.net.URLEncoder.encode(path, "UTF-8")}&per_page=1"
                val arr = requestJsonArray(url) ?: return@runCatching null
                if (arr.length() == 0) return@runCatching null
                arr.optJSONObject(0)
                    ?.optJSONObject("commit")
                    ?.optJSONObject("committer")
                    ?.optString("date")
                    ?.takeIf { it.isNotBlank() }
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
            defaultBranch = o.optString("default_branch").ifBlank { "main" },
            language = o.optString("language").ifBlank { null },
            updatedAt = o.optString("updated_at").ifBlank { null },
            pushedAt = o.optString("pushed_at").ifBlank { null }
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
                            contentType = a.optString("content_type").ifBlank { null },
                            updatedAt = a.optString("updated_at").ifBlank { null }
                        )
                    )
                }
            }
        }
        return GitHubRelease(
            tagName = o.optString("tag_name"),
            name = o.optString("name").ifBlank { null },
            publishedAt = o.optString("published_at").ifBlank { null },
            assets = assets,
            prerelease = o.optBoolean("prerelease", false),
            draft = o.optBoolean("draft", false)
        )
    }

    // ---------- 请求执行 ----------

    /** Token 短指纹：带 Token 的请求 key 拼上，换/清 Token 后旧缓存不命中、不串号 */
    private fun tokenFingerprint(): String =
        tokenProvider()?.takeIf { it.isNotBlank() }?.hashCode()?.toString() ?: "anon"

    /** 成功请求日志：只打 withToken 布尔，绝不打 Token 本身 */
    private fun logReq(tag: String, code: Int, startedAt: Long, withToken: Boolean, url: String = "") {
        Log.d(TAG, "$tag -> HTTP $code (${System.currentTimeMillis() - startedAt}ms, token=$withToken) $url")
    }

    /** 网络异常日志：打异常类名+消息，便于区分超时/断网/SSL 等 */
    private fun logNetErr(tag: String, e: Throwable) {
        Log.w(TAG, "$tag network error: ${e.javaClass.simpleName}: ${e.message}")
    }

    /**
     * 走统一缓存取 JSON 原始响应文本，返回 [GitHubResponseCache.FetchResult]：
     * - 2xx 非空 body → Ok（成功缓存）；
     * - 非 2xx → HttpError（短缓存 10s，防 403 限流重试风暴）；
     * - 网络异常（IOException 等）→ NetworkError（**不写缓存**，下次立即重试，避免偶发失败被放大）。
     * 调用方再自行用 JSONObject/JSONArray 解析。
     */
    private suspend fun cachedBody(key: String, url: String): GitHubResponseCache.FetchResult =
        GitHubResponseCache.getOrFetchResult(key) {
            val started = System.currentTimeMillis()
            val withToken = !tokenProvider().isNullOrBlank()
            try {
                client.newCall(buildRequest(url)).execute().use { resp ->
                    val body = if (resp.isSuccessful) resp.body?.string() else null
                    logReq("GET", resp.code, started, withToken, url)
                    noteResponseCode(resp.code)
                    when {
                        body != null -> GitHubResponseCache.FetchResult.Ok(body)
                        else -> GitHubResponseCache.FetchResult.HttpError(resp.code)
                    }
                }
            } catch (e: Exception) {
                logNetErr("GET $url", e)
                GitHubResponseCache.FetchResult.NetworkError
            }
        }

    /** 构建带鉴权头的 Request 并执行，返回 JSONObject；非 2xx / 空响应 / 解析失败返回 null */
    private fun requestJson(url: String): JSONObject? {
        val request = buildRequest(url)
        val started = System.currentTimeMillis()
        val withToken = !tokenProvider().isNullOrBlank()
        return try {
            client.newCall(request).execute().use { response ->
                logReq("requestJson", response.code, started, withToken, url)
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                runCatching { JSONObject(body) }.getOrNull()
            }
        } catch (e: Exception) {
            logNetErr("requestJson $url", e); null
        }
    }

    /** 构建带鉴权头的 Request 并执行，返回 JSONArray；非 2xx / 空响应 / 解析失败返回 null */
    private fun requestJsonArray(url: String): JSONArray? {
        val request = buildRequest(url)
        val started = System.currentTimeMillis()
        val withToken = !tokenProvider().isNullOrBlank()
        return try {
            client.newCall(request).execute().use { response ->
                logReq("requestJsonArray", response.code, started, withToken, url)
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                runCatching { JSONArray(body) }.getOrNull()
            }
        } catch (e: Exception) {
            logNetErr("requestJsonArray $url", e); null
        }
    }

    /**
     * 校验一个**尚未保存**的 Token：GET /user。
     *
     * 不走 [cachedBody]（缓存 key 带 Token 指纹，校验的 Token 还没保存，会与匿名 key 串号）。
     * 保存前先校验可拦住乱填的 Token —— 无效 Token 会让之后所有 GitHub 请求返回 401。
     */
    suspend fun validateToken(token: String): TokenCheck = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/user")
                .header("User-Agent", "YunX")
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // 401 = Token 无效/已过期/被撤销；其余（403 限流、5xx 等）无法判定有效性
                    if (resp.code == 401) TokenCheck.Invalid else TokenCheck.Unknown
                } else {
                    val login = resp.body?.string()
                        ?.let { body -> runCatching { JSONObject(body).optString("login") }.getOrNull() }
                    if (login.isNullOrBlank()) TokenCheck.Unknown else TokenCheck.Valid(login)
                }
            }
        }.getOrElse { TokenCheck.Unknown }
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
