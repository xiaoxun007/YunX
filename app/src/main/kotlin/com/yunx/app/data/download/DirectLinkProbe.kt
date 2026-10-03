/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa / xiaoxun007 (fork)
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

package com.yunx.app.data.download

import com.yunx.app.data.network.HttpClients
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 通用直链探测结果。
 *
 * - [filename]：服务器 `Content-Disposition` 声明的文件名；未声明为 null（由调用方回退 URL 段名）。
 * - [size]：真实总大小（`Content-Range`/`Content-Length`）；未知为 null（弹窗显示"未知"）。
 * - [isHtml]：响应为 text/html 且无 attachment 头——基本可判定是网页而非文件（调用方决定是否放行）。
 */
data class DirectLinkProbeResult(
    val filename: String?,
    val size: Long?,
    val isHtml: Boolean
)

/**
 * 通用直链探测：解析页把「非内置网盘的 http(s) 链接」当文件直链下载前，先发一次
 * Range 探测拿真实文件名（Content-Disposition）与大小。
 *
 * - 走全局统一 [HttpClients.apiClient]（自动继承「网络代理」设置），仅把超时收短，
 *   探测失败不阻塞：直接返回兜底结果由调用方回退。
 * - 服务器不支持 Range（非 206）时退化为普通 GET 读 Content-Length（响应体不消费即释放）。
 */
object DirectLinkProbe {

    /** 探测超时：比下载宽松些但要短，避免解析页转圈过久 */
    private const val CONNECT_TIMEOUT_SEC = 5L
    private const val READ_TIMEOUT_SEC = 8L

    suspend fun probe(url: String): DirectLinkProbeResult = withContext(Dispatchers.IO) {
        val client = HttpClients.apiClient().newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
        // Range 优先：能拿到 Content-Range 总大小 + Content-Disposition；不支持再退普通 GET
        val ranged = probeOnce(client, url, withRange = true)
        if (ranged != null) return@withContext ranged
        probeOnce(client, url, withRange = false) ?: DirectLinkProbeResult(null, null, false)
    }

    private suspend fun probeOnce(
        client: okhttp3.OkHttpClient,
        url: String,
        withRange: Boolean
    ): DirectLinkProbeResult? = runCatching {
        val request = Request.Builder()
            .url(url)
            .apply { if (withRange) header("Range", "bytes=0-0") }
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val disposition = resp.header("Content-Disposition")
            val contentType = resp.header("Content-Type").orEmpty()
            val isHtml = contentType.contains("text/html", ignoreCase = true) &&
                disposition?.contains("attachment", ignoreCase = true) != true
            val size = if (withRange) {
                // 206 + Content-Range 的 total 是真实总大小
                if (resp.code != 206) return@use null
                parseContentRangeTotal(resp.header("Content-Range"))
            } else {
                if (!resp.isSuccessful) return@use null
                resp.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
            }
            DirectLinkProbeResult(
                filename = parseContentDisposition(disposition),
                size = size,
                isHtml = isHtml
            )
        }
    }.getOrNull()

    /** `Content-Range: bytes 0-0/12345` → 12345；解析失败返回 null */
    private fun parseContentRangeTotal(header: String?): Long? {
        if (header == null) return null
        return header.substringAfter('/').toLongOrNull()?.takeIf { it > 0 }
    }

    /**
     * 从 `Content-Disposition` 提取文件名，支持两种形式：
     * 1. RFC 5987：`filename*=UTF-8''%E4%B8%AD%E6%96%87.zip`（中文等非 ASCII 名）
     * 2. 传统：`filename="file.zip"` / `filename=file.zip`
     */
    private fun parseContentDisposition(cd: String?): String? {
        if (cd == null) return null
        val ext = Regex("filename\\*\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
            .find(cd)?.groupValues?.get(1)?.trim()
        if (ext != null && ext.isNotBlank()) {
            val charset = ext.substringBefore("''", "UTF-8").takeIf { it.isNotBlank() } ?: "UTF-8"
            val name = ext.substringAfter("''", "")
            return runCatching { URLDecoder.decode(name, charset) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
        }
        val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(cd)?.groupValues?.get(1)?.trim()
        return plain?.takeIf { it.isNotBlank() }
    }
}
