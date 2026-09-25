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

package com.yunx.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunx.app.data.network.GitHubApi
import com.yunx.app.data.network.GitHubAsset
import com.yunx.app.data.network.GitHubRelease
import com.yunx.app.data.network.GitHubRepo
import com.yunx.app.data.network.GitHubTreeEntry

/**
 * GitHub 浏览节点（导航栈中的每一层）。
 */
sealed class GitHubNode {
    /** 仓库根：展示「代码」与「Releases」两个入口 */
    data class RepoRoot(val repo: GitHubRepo) : GitHubNode()

    /** 代码子目录（path 为相对仓库根的完整路径，sha 为该目录树 ref） */
    data class CodeDir(val repo: GitHubRepo, val path: String, val sha: String) : GitHubNode()

    /** Releases 列表（分页） */
    data class ReleasesList(val repo: GitHubRepo, val page: Int) : GitHubNode()

    /** 单个 Release 的资产列表 */
    data class ReleaseAssets(val repo: GitHubRepo, val release: GitHubRelease) : GitHubNode()

    /** 账号 / 组织的仓库列表（分页） */
    data class AccountRepos(val owner: String, val page: Int) : GitHubNode()
}

/**
 * GitHub 浏览界面：把 GitHub 当网盘解析浏览下载。
 *
 * - 用 [backStack] 维护导航栈：进入子节点 add，返回 removeLast；
 * - 系统返回键：栈内回退，栈空回调 [onExit]；
 * - 点击 repo / forked from 时，内部先 getRepo 再 push RepoRoot（pendingRepo 模式）；
 * - 下载只透传原始 GitHub 直链，镜像转换由上层（MainScreen）完成。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubBrowseScreen(
    api: GitHubApi,
    initialNode: GitHubNode,
    scrollBehavior: TopAppBarScrollBehavior,
    onExit: () -> Unit,
    onDownload: (url: String, fileName: String) -> Unit,
    /** 打开上游仓库 / 账号列表中的 repo（内部已自行 getRepo 并 push 节点，上层无需处理） */
    onOpenRepository: (owner: String, repo: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val backStack = remember { mutableStateListOf<GitHubNode>() }
    LaunchedEffect(Unit) {
        if (backStack.isEmpty()) backStack.add(initialNode)
    }
    val current = backStack.lastOrNull()

    // 内部打开仓库：先 getRepo 拿到 defaultBranch / parent，成功后 push RepoRoot
    var pendingTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingLoading by remember { mutableStateOf(false) }
    var pendingError by remember { mutableStateOf(false) }
    var pendingRetry by remember { mutableStateOf(0) }

    fun openRepository(owner: String, repo: String) {
        pendingTarget = owner to repo
    }

    LaunchedEffect(pendingTarget, pendingRetry) {
        val target = pendingTarget ?: return@LaunchedEffect
        pendingLoading = true
        pendingError = false
        val r = api.getRepo(target.first, target.second)
        pendingLoading = false
        if (r != null) {
            backStack.add(GitHubNode.RepoRoot(r))
            pendingTarget = null
        } else {
            pendingError = true
        }
    }

    BackHandler(enabled = backStack.isNotEmpty() || pendingLoading) {
        if (pendingLoading) {
            pendingTarget = null
            pendingLoading = false
            return@BackHandler
        }
        if (backStack.size > 1) backStack.removeLast()
        else {
            backStack.clear()
            onExit()
        }
    }

    fun navigateBack() {
        if (backStack.size > 1) backStack.removeLast()
        else {
            backStack.clear()
            onExit()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentTitle(current), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { navigateBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        modifier = modifier.fillMaxSize()
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                pendingLoading -> CenterLoading(Modifier.align(Alignment.Center))
                pendingError -> ErrorRetry(
                    message = "无法打开仓库，请重试",
                    onRetry = { pendingRetry++ },
                    modifier = Modifier.align(Alignment.Center)
                )
                current == null -> Unit
                else -> when (val node = current) {
                    is GitHubNode.RepoRoot -> RepoRootContent(
                        repo = node.repo,
                        onOpenCode = {
                            backStack.add(
                                GitHubNode.CodeDir(
                                    repo = node.repo,
                                    path = "",
                                    sha = node.repo.defaultBranch
                                )
                            )
                        },
                        onOpenReleases = { backStack.add(GitHubNode.ReleasesList(node.repo, page = 1)) },
                        onOpenParent = {
                            val parent = node.repo.parentFullName ?: return@RepoRootContent
                            openRepository(parent.substringBefore('/'), parent.substringAfter('/'))
                        }
                    )
                    is GitHubNode.CodeDir -> CodeDirContent(
                        api = api,
                        repo = node.repo,
                        path = node.path,
                        sha = node.sha,
                        onEnterDir = { childPath, childSha ->
                            backStack.add(GitHubNode.CodeDir(node.repo, childPath, childSha))
                        },
                        onDownload = onDownload
                    )
                    is GitHubNode.ReleasesList -> ReleasesListContent(
                        api = api,
                        repo = node.repo,
                        page = node.page,
                        onOpenRelease = { backStack.add(GitHubNode.ReleaseAssets(node.repo, it)) },
                        onLoadMore = { backStack.add(GitHubNode.ReleasesList(node.repo, node.page + 1)) }
                    )
                    is GitHubNode.ReleaseAssets -> ReleaseAssetsContent(
                        repo = node.repo,
                        release = node.release,
                        onDownload = onDownload
                    )
                    is GitHubNode.AccountRepos -> AccountReposContent(
                        api = api,
                        owner = node.owner,
                        page = node.page,
                        onOpenRepo = { owner, repo -> openRepository(owner, repo) },
                        onLoadMore = { backStack.add(GitHubNode.AccountRepos(node.owner, node.page + 1)) }
                    )
                }
            }
        }
    }
}

/** 顶栏标题 */
private fun currentTitle(node: GitHubNode?): String = when (node) {
    is GitHubNode.RepoRoot -> node.repo.fullName
    is GitHubNode.CodeDir -> if (node.path.isBlank()) node.repo.name else node.path
    is GitHubNode.ReleasesList -> "Releases · ${node.repo.name}"
    is GitHubNode.ReleaseAssets -> node.release.tagName
    is GitHubNode.AccountRepos -> "@${node.owner}"
    null -> "GitHub"
}

// ---------- 各节点内容 ----------

@Composable
private fun RepoRootContent(
    repo: GitHubRepo,
    onOpenCode: () -> Unit,
    onOpenReleases: () -> Unit,
    onOpenParent: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text(
                    text = repo.fullName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                // forked from 显示在仓库标题下方（不是列表条目内）
                if (repo.fork && repo.parentFullName != null) {
                    TextButton(onClick = onOpenParent, contentPadding = PaddingValues(vertical = 4.dp)) {
                        Text(
                            text = "forked from ${repo.parentFullName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                repo.description?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            ListItemCard(
                icon = Icons.Outlined.Code,
                title = "代码",
                subtitle = "浏览仓库文件（${repo.defaultBranch} 分支）",
                onClick = onOpenCode
            )
        }
        item {
            ListItemCard(
                icon = Icons.Outlined.Tag,
                title = "Releases",
                subtitle = "版本发布与附件下载",
                onClick = onOpenReleases
            )
        }
    }
}

@Composable
private fun CodeDirContent(
    api: GitHubApi,
    repo: GitHubRepo,
    path: String,
    sha: String,
    onEnterDir: (childPath: String, childSha: String) -> Unit,
    onDownload: (url: String, fileName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var entries by remember(repo.fullName, path, sha) { mutableStateOf<List<GitHubTreeEntry>?>(null) }
    var error by remember(repo.fullName, path, sha) { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }

    LaunchedEffect(repo.fullName, path, sha, retry) {
        entries = null
        error = false
        val r = api.getTree(repo.owner, repo.name, sha)
        if (r == null) error = true else entries = r
    }

    val branch = repo.defaultBranch
    val zipUrl = "https://codeload.github.com/${repo.owner}/${repo.name}/zip/refs/heads/$branch"
    val zipName = "${repo.name}-$branch.zip"

    fun childFullPath(entryPath: String): String =
        if (path.isBlank()) entryPath else "$path/$entryPath"

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = if (path.isBlank()) "代码根目录" else path,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 下载完整源码 ZIP（固定条目，位于文件列表之前）
        item {
            ListItemCard(
                icon = Icons.Outlined.Archive,
                title = "下载完整源码 ZIP",
                subtitle = "$zipName",
                onClick = { onDownload(zipUrl, zipName) }
            )
        }
        when {
            error -> item { ErrorRetryInline("加载目录失败", onRetry = { retry++ }) }
            entries == null -> item { CenterLoadingInline() }
            else -> {
                items(entries!!, key = { it.path }) { entry ->
                    val displayName = entry.path.substringAfterLast('/')
                    if (entry.type == "tree") {
                        ListItemCard(
                            icon = Icons.Outlined.Folder,
                            title = displayName,
                            subtitle = "文件夹",
                            onClick = { onEnterDir(childFullPath(entry.path), entry.sha) }
                        )
                    } else {
                        val fullPath = childFullPath(entry.path)
                        val rawUrl = "https://raw.githubusercontent.com/${repo.owner}/${repo.name}/$branch/$fullPath"
                        ListItemCard(
                            icon = Icons.Outlined.InsertDriveFile,
                            title = displayName,
                            subtitle = entry.size?.let { formatSize(it) },
                            onClick = { onDownload(rawUrl, displayName) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleasesListContent(
    api: GitHubApi,
    repo: GitHubRepo,
    page: Int,
    onOpenRelease: (GitHubRelease) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    var releases by remember(repo.fullName, page) { mutableStateOf<List<GitHubRelease>?>(null) }
    var error by remember(repo.fullName, page) { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }

    LaunchedEffect(repo.fullName, page, retry) {
        releases = null
        error = false
        val r = api.getReleases(repo.owner, repo.name, page)
        if (r == null) error = true else releases = r
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when {
            error -> item { ErrorRetryInline("加载 Releases 失败", onRetry = { retry++ }) }
            releases == null -> item { CenterLoadingInline() }
            else -> {
                items(releases!!, key = { it.tagName }) { release ->
                    ListItemCard(
                        icon = Icons.Outlined.Tag,
                        title = release.tagName,
                        subtitle = release.name ?: release.publishedAt ?: "Release",
                        onClick = { onOpenRelease(release) }
                    )
                }
                // 满页（100 条）才显示「加载更多」，避免空翻页
                if (releases!!.size >= 100) {
                    item {
                        TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                            Text("加载更多")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseAssetsContent(
    repo: GitHubRepo,
    release: GitHubRelease,
    onDownload: (url: String, fileName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column {
                Text(
                    text = release.name ?: release.tagName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                release.publishedAt?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (release.assets.isEmpty()) {
            item {
                Text(
                    text = "该版本无可下载附件",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(release.assets, key = { it.name }) { asset: GitHubAsset ->
            ListItemCard(
                icon = Icons.Outlined.Download,
                title = asset.name,
                subtitle = formatSize(asset.size),
                onClick = { onDownload(asset.downloadUrl, asset.name) }
            )
        }
    }
}

@Composable
private fun AccountReposContent(
    api: GitHubApi,
    owner: String,
    page: Int,
    onOpenRepo: (owner: String, repo: String) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    var repos by remember(owner, page) { mutableStateOf<List<GitHubRepo>?>(null) }
    var error by remember(owner, page) { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }

    LaunchedEffect(owner, page, retry) {
        repos = null
        error = false
        // 账号可能是用户也可能是组织：先按用户查，失败再按组织查
        var r = api.getUserRepos(owner, page)
        if (r == null) r = api.getOrgRepos(owner, page)
        if (r == null) error = true else repos = r
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when {
            error -> item { ErrorRetryInline("加载仓库列表失败", onRetry = { retry++ }) }
            repos == null -> item { CenterLoadingInline() }
            else -> {
                items(repos!!, key = { it.fullName }) { repo ->
                    ListItemCard(
                        icon = Icons.Outlined.Folder,
                        title = repo.name + if (repo.fork) "  (fork)" else "",
                        subtitle = repo.description,
                        onClick = { onOpenRepo(owner, repo.name) }
                    )
                }
                if (repos!!.size >= 100) {
                    item {
                        TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                            Text("加载更多")
                        }
                    }
                }
            }
        }
    }
}

// ---------- 通用小组件 ----------

@Composable
private fun ListItemCard(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

@Composable
private fun CenterLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenterLoadingInline() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(strokeWidth = 2.dp)
    }
}

@Composable
private fun ErrorRetry(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRetry) {
            Icon(Icons.Outlined.Refresh, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("重试")
        }
    }
}

@Composable
private fun ErrorRetryInline(message: String, onRetry: () -> Unit) {
    Card(
        onClick = onRetry,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(text = message, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

/** 字节数格式化 */
private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes B" else String.format("%.1f %s", value, units[unit])
}
