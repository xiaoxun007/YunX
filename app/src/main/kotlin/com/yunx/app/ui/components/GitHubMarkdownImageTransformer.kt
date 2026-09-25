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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * mikepenz MarkdownText 的自定义 ImageTransformer：用项目已有 OkHttp 加载 README 图片，
 * 不引入 Coil。
 *
 * - 内存 LRU 缓存（128 张）防重复请求；
 * - Semaphore(4) 限制并发；
 * - 降采样：先读尺寸按目标宽 inSampleSize，避免大图撑爆内存；
 * - 镜像：若 mirrorPrefix 非空，先试镜像 URL，失败回退直连；
 * - 任何异常返回 null，库显示占位，不崩。
 *
 * 相对链接已在 ResolveScreen.preprocessReadme 补全为 raw.githubusercontent.com 绝对 URL。
 */
object GitHubMarkdownImageTransformer : ImageTransformer {

    private const val MAX_ENTRIES = 128
    private val lock = Mutex()
    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, android.graphics.Bitmap>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>?) =
                size > MAX_ENTRIES
        }
    )
    private val semaphore = Semaphore(4)

    /** 镜像前缀（由调用方在 ResolveScreen 读取 SettingsRepository 后设置；null 表示直连） */
    @Volatile
    var mirrorPrefix: String? = null

    @Composable
    override fun transform(link: String): ImageData? {
        // produceState：协程加载位图，加载完成前返回 null（库显示占位）
        val bitmap = produceState<android.graphics.Bitmap?>(initialValue = null, link) {
            value = loadBitmap(link)
        }.value ?: return null
        return ImageData(painter = BitmapPainter(bitmap.asImageBitmap()))
    }

    private suspend fun loadBitmap(url: String): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) {
            cache[url]?.let { return@withContext it }
            val candidates = buildList {
                if (!mirrorPrefix.isNullOrBlank()) add(UpdateChecker.mirrorUrl(url, mirrorPrefix!!))
                add(url)
            }
            val client = HttpClients.downloadClient()
            for (candidate in candidates) {
                val bytes = runCatching {
                    semaphore.withPermit {
                        val req = okhttp3.Request.Builder().url(candidate)
                            .header("User-Agent", "YunX").build()
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@withPermit null
                            resp.body?.bytes()
                        }
                    }
                }.getOrNull()
                if (!bytes.isNullOrEmpty()) {
                    val bmp = decodeSampled(bytes)
                    if (bmp != null) {
                        cache[url] = bmp
                        return@withContext bmp
                    }
                }
            }
            null
        }

    /** 降采样解码：先读边界尺寸，按目标宽（1080px）算 inSampleSize（2 的幂）。 */
    private fun decodeSampled(bytes: ByteArray): android.graphics.Bitmap? = runCatching {
        val targetWidth = 1080
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        var w = bounds.outWidth
        while (w / 2 >= targetWidth) { w /= 2; sample *= 2 }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()
}
