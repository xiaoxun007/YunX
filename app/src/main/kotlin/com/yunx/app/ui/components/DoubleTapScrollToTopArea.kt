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

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 顶部透明热区：双击回顶（适配小米/MIUI「双击状态栏回顶」习惯）。
 *
 * 放置方式：调用方在页面 Box 顶层用 `Modifier.align(Alignment.TopStart)` 叠加，
 * 热区高度默认 56.dp，与标题栏区域对齐。**不要放进 LazyColumn item 里**。
 *
 * 手势语义（手动 awaitEachGesture，不用 detectTapGestures 的 onDoubleTap——它会吞单击、延迟上报）：
 * - 第一击 down/up **不消费**：单击、滑动完全透传给下层（点标题栏返回/搜索、列表滚动不受影响）；
 * - 300ms 内出现第二次 down 且两次位移 < 16.dp 判为双击 → 平滑滚动到第 0 项；
 * - 判为双击后**消费第二击的 down/up**，防第二击穿透点击到列表行；
 * - 已在顶部（firstVisibleItemIndex==0 且 offset 很小）时双击：不执行动画（no-op 不抖动），
 *   但仍按双击消费第二击，避免第二击穿透。
 */
@Composable
fun DoubleTapScrollToTopArea(
    state: LazyListState,
    height: Dp = 56.dp,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(state) {
                val slopPx = with(density) { 16.dp.toPx() }
                awaitEachGesture {
                    // 第一击：不消费，单击/滑动透传下层
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    val firstTime = System.currentTimeMillis()
                    val firstPos = firstDown.position
                    // 300ms 内等第二击；超时则本次手势结束（单击不触发回顶）
                    val secondDown = withTimeoutOrNull(300) {
                        awaitFirstDown(requireUnconsumed = false)
                    } ?: return@awaitEachGesture

                    val dt = System.currentTimeMillis() - firstTime
                    val dist = (secondDown.position - firstPos).getDistance()
                    if (dt <= 300 && dist < slopPx) {
                        // 双击命中：消费第二击 down/up，防穿透点击列表行
                        secondDown.consume()
                        // 已在顶部则 no-op（不抖动）
                        val atTop = state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0
                        if (!atTop) {
                            scope.launch { state.animateScrollToItem(0) }
                        }
                        waitForUpOrCancellation()?.consume()
                    }
                }
            }
    )
}
