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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * GitHub API 统一内存缓存（原始响应文本）。
 *
 * - 成功条目 TTL 10 分钟（GitHub 数据不会秒级变化，浏览/返回时命中省请求）；
 * - HTTP 非 2xx（404/403 等）是**确定状态**，短缓存 10 秒（防 403 限流时重试风暴，10 秒后仍可重试）；
 * - **网络异常（IOException/超时/断网）不写失败哨兵缓存**：偶发网络失败若被缓存 1 分钟，
 *   会被放大为持续 1 分钟的"必现失败"假象（实测同一仓库点进去 3 秒报错），下次必须立即重发请求；
 * - 容量上限 256 条，超限淘汰最旧条目；
 * - in-flight 去重：同一 key 并发只发一次网络请求，其余协程等待同一份结果；
 * - [invalidatePrefix] 供下拉刷新使用，按前缀清空（用户主动刷新要看最新数据）。
 *
 * 仅进程内缓存，不持久化；换 Token 后带 token 指纹的 key 自然不命中旧缓存。
 */
object GitHubResponseCache {
    /** 一次请求的结果分类，供缓存层决定"写不写缓存、写多久"。 */
    sealed interface FetchResult {
        /** 2xx 且 body 非空：成功缓存 */
        data class Ok(val body: String) : FetchResult

        /** HTTP 非 2xx（404/403/5xx 等）：确定失败状态，短缓存防重试风暴 */
        data class HttpError(val code: Int) : FetchResult

        /** 网络异常（IOException/SocketTimeout/解析前断网）：**不缓存**，下次立即重试 */
        data object NetworkError : FetchResult
    }

    private data class Entry(
        /** 响应原文；失败哨兵为 null */
        val value: String?,
        val fetchedAt: Long,
        val success: Boolean,
        /** 该条目自身的 TTL（成功 10min，HTTP 失败 10s） */
        val ttlMs: Long
    )

    private const val SUCCESS_TTL_MS = 10 * 60 * 1000L   // 成功 10 分钟
    private const val HTTP_FAIL_TTL_MS = 10 * 1000L      // HTTP 非 2xx 短缓存 10 秒
    private const val MAX_ENTRIES = 256

    private val cache = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<FetchResult>>()
    private val evictMutex = Mutex()

    /**
     * 命中有效缓存直接返回；否则执行 [fetch] 取网络结果并按结果类型决定写缓存策略。
     * 同 key 并发只发一次请求，其余等待同一 Deferred（结果类型透传）。
     * @param maxBytes 成功结果超过此大小则不写缓存（防大 README 占内存）；<=0 不限制。
     */
    suspend fun getOrFetchResult(key: String, maxBytes: Int = 0, fetch: suspend () -> FetchResult): FetchResult {
        // 1) 命中（含 HTTP 失败哨兵在短 TTL 内）直接返回对应结果
        cache[key]?.let { entry ->
            if (System.currentTimeMillis() - entry.fetchedAt <= entry.ttlMs) {
                return if (entry.success && entry.value != null) {
                    FetchResult.Ok(entry.value)
                } else {
                    FetchResult.HttpError(-1)
                }
            }
            // 过期淘汰
            cache.remove(key)
        }
        // 2) in-flight 去重：已有同 key 请求在飞，等待其结果
        inFlight[key]?.let { return it.await() }
        // 3) 自己发起请求
        val deferred = CompletableDeferred<FetchResult>()
        inFlight[key] = deferred
        val outcome = try {
            fetch()
        } catch (ce: CancellationException) {
            inFlight.remove(key, deferred)
            deferred.complete(FetchResult.NetworkError)
            throw ce
        } catch (_: Exception) {
            // fetch 自身未捕获的异常一律按网络异常处理，不写缓存
            FetchResult.NetworkError
        }
        when (outcome) {
            // 成功：按大小限制决定是否写缓存
            is FetchResult.Ok -> {
                if (!(maxBytes > 0 && outcome.body.length > maxBytes)) {
                    cache[key] = Entry(outcome.body, System.currentTimeMillis(), success = true, ttlMs = SUCCESS_TTL_MS)
                    evictIfNeeded()
                }
            }
            // HTTP 非 2xx：写短 TTL 失败哨兵（null），10 秒后允许重试
            is FetchResult.HttpError -> {
                cache[key] = Entry(null, System.currentTimeMillis(), success = false, ttlMs = HTTP_FAIL_TTL_MS)
                evictIfNeeded()
            }
            // 网络异常：不写任何缓存，下次请求立即重发
            FetchResult.NetworkError -> Unit
        }
        deferred.complete(outcome)
        inFlight.remove(key, deferred)
        return outcome
    }

    /** 便捷包装：只取响应原文，失败/网络异常返回 null（用于无需区分失败原因的调用点） */
    suspend fun getOrFetch(key: String, maxBytes: Int = 0, fetch: suspend () -> FetchResult): String? =
        (getOrFetchResult(key, maxBytes, fetch) as? FetchResult.Ok)?.body

    /** 删除所有 key 以 [prefix] 开头的条目（下拉刷新绕缓存用） */
    fun invalidatePrefix(prefix: String) {
        cache.keys.removeAll { it.startsWith(prefix) }
    }

    /** 容量超限按 fetchedAt 淘汰最旧条目 */
    private suspend fun evictIfNeeded() = evictMutex.withLock {
        if (cache.size <= MAX_ENTRIES) return@withLock
        val toRemove = cache.entries
            .sortedBy { it.value.fetchedAt }
            .take(cache.size - MAX_ENTRIES)
            .map { it.key }
        toRemove.forEach { cache.remove(it) }
    }
}
