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

package com.yunx.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yunx.app.crash.CrashHandler
import com.yunx.app.ui.MainScreen
import com.yunx.app.ui.screens.SafetyNoticeDialog
import com.yunx.app.ui.theme.ComposeEmptyActivityTheme
import com.yunx.app.util.ArchiveProbe

class MainActivity : ComponentActivity() {

    // ★ 通知权限不再在启动时申请/引导：统一收到引导页第 3 页（见 ui/screens/OnboardingPermissionPage.kt），
    //   之后只有设置页里的手动入口（「通知栏下载进度」）会再申请，避免每次启动都弹窗打扰。
    //   —— 上游 #124 收口；fork 删除本地 notificationPermLauncher/showNotificationGuide。

    // 通知点击带来的「直达下载页」信号：每次带 open_tab=download 的 Intent 拉起就 +1。
    // MainScreen 用 LaunchedEffect 监听该值并切到 Download Tab；0 表示无请求，正常启动行为不变。
    private var openDownloadSignal by mutableStateOf(0)

    /** 检查 Intent 是否带「直达下载页」extra；带则递增信号（onCreate/onNewIntent 共用）。 */
    private fun consumeOpenTabFromIntent(intent: Intent?) {
        if (intent?.getStringExtra("open_tab") == "download") {
            openDownloadSignal += 1
        }
    }

    // 应用已在栈顶运行时被通知 Intent 再次拉起（SINGLE_TOP）：会走这里而不是新建 Activity。
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeOpenTabFromIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用内主题设置：始终深色/浅色时提前切换窗口主题，避免冷启动闪错背景色
        // （values-night 只跟随系统；应用内「始终深色」但系统浅色时，需显式使用深色窗口主题）
        val darkModePref = getSharedPreferences("yunx_settings", android.content.Context.MODE_PRIVATE)
            .getInt("dark_mode", 0)
        when (darkModePref) {
            1 -> setTheme(R.style.Theme_ComposeEmptyActivity_Light)
            2 -> setTheme(R.style.Theme_ComposeEmptyActivity_Dark)
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 首次启动也读 Intent extra：通知冷启动拉起时直达下载页（fork 通知 flags 修复配套）
        consumeOpenTabFromIntent(intent)
        runCatching {
            val hits = ArchiveProbe.fast(this).toMutableList()
            if (entryMismatch(this)) hits.add(4)
            if (hits.isNotEmpty()) {
                CrashHandler.terminate(hits.joinToString(","))
            }
        }
        setContent {
            ComposeEmptyActivityTheme {
                MainScreen(openDownloadSignal = openDownloadSignal)
                SafetyNoticeDialog()
            }
        }
    }
}
