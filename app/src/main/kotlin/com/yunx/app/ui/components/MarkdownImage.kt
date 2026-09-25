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

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * README 图片加载（自研，零第三方依赖，复用 OkHttp）。
 *
 * - 内存 LRU 缓存：最多 128 张，防 OOM；
 * - 降采样：先读尺寸再按目标宽度 inSampleSize，避免大图撑爆内存；
 * - 镜像回退：先试镜像 URL，失败再试直连，保证加载成功率；
 * - 全局 Semaphore(4) 限制并发图片请求；
 * - 失败降级为「[图片] alt」文本，不崩溃不阻塞列表。
 */

/** 图片内存缓存（LRU，最多 128 张） */
object MarkdownImageCache {
    private const val MAX_ENTRIES = 128
    private val lock = Mutex()
    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, ImageBitmap>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) =
                size > MAX_ENTRIES
        }
    )

    suspend fun get(url: String): ImageBitmap? = lock.withLock { cache[url] }
    suspend fun put(url: String, bitmap: ImageBitmap) {
        lock.withLock { cache[url] = bitmap }
    }
}

/** 全局图片请求并发上限 */
private val imageSemaphore = Semaphore(4)

/**
 * 加载并渲染一张 README 图片。
 *
 * @param mirrorPrefix 镜像前缀；null/空表示直连。镜像失败会自动回退直连。
 */
@Composable
fun MarkdownImage(
    imageUrl: String,
    alt: String,
    repoOwner: String,
    repoName: String,
    defaultBranch: String,
    mirrorPrefix: String?,
    modifier: Modifier = Modifier
) {
    // 1) URL 补全：相对路径 → raw.githubusercontent.com
    val finalUrl = remember(imageUrl, repoOwner, repoName, defaultBranch) {
        if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
            imageUrl
        } else {
            val path = imageUrl.removePrefix("./").removePrefix("/")
            "https://raw.githubusercontent.com/$repoOwner/$repoName/$defaultBranch/$path"
        }
    }

    // 非 http(s) 协议（data: 等）直接降级占位
    if (!finalUrl.startsWith("http")) {
        Text(
            text = "[图片] $alt",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = 4.dp)
        )
        return
    }

    var bitmap by remember(finalUrl) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(finalUrl) { mutableStateOf(false) }
    var loading by remember(finalUrl) { mutableStateOf(true) }

    // 2) 加载：先查缓存，未命中则请求（镜像优先，失败回退直连）
    LaunchedEffect(finalUrl) {
        val cached = MarkdownImageCache.get(finalUrl)
        if (cached != null) {
            bitmap = cached
            loading = false
            return@LaunchedEffect
        }
        val result = loadImage(finalUrl, mirrorPrefix)
        if (result != null) {
            bitmap = result
            MarkdownImageCache.put(finalUrl, result)
        } else {
            failed = true
        }
        loading = false
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp) }
            failed -> Text(
                text = "[图片] $alt",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> bitmap?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = alt,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                )
            }
        }
    }
}

/** 请求图片字节并解码（降采样）；镜像失败回退直连。返回 null 表示彻底失败。 */
private suspend fun loadImage(url: String, mirrorPrefix: String?): ImageBitmap? =
    withContext(Dispatchers.IO) {
        // 候选 URL：镜像优先，直连兜底
        val candidates = buildList {
            if (!mirrorPrefix.isNullOrBlank()) add(UpdateChecker.mirrorUrl(url, mirrorPrefix))
            add(url)
        }
        val client = HttpClients.downloadClient()
        for (candidate in candidates) {
            val bytes = runCatching {
                imageSemaphore.withPermit {
                    val req = okhttp3.Request.Builder().url(candidate)
                        .header("User-Agent", "YunX").build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@withPermit null
                        resp.body?.bytes()
                    }
                }
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                val bmp = decodeSampled(bytes)
                if (bmp != null) return@withContext bmp
            }
        }
        null
    }

/** 降采样解码：先读边界尺寸，按目标宽度算 inSampleSize（2 的幂），再解码。 */
private fun decodeSampled(bytes: ByteArray): ImageBitmap? {
    return runCatching {
        // 目标解码宽度约屏幕宽减 32dp（粗略按 1080px 上限）
        val targetWidth = 1080
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        var w = bounds.outWidth
        while (w / 2 >= targetWidth) {
            w /= 2
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }.getOrNull()
}
