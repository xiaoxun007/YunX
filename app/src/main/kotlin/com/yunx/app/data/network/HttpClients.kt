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

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 全局 HTTP 客户端管理：
 * - [apiClient]：平台 API（登录/解析/直链）、HLS 下载、更新检查共用，超时宽松；
 * - [downloadClient]：分片下载专用，大 Dispatcher 保障分片并发（默认实例 maxRequestsPerHost=5 会锁死并发）。
 * 所有构建都使用系统证书链和 OkHttp 主机名校验，不提供进程内绕过开关。
 */
object HttpClients {

    /** 下载客户端排队 Call 上限：真实并发受 DownloadManager 的在飞上限约束，见 buildDownload 注释 */
    private const val MAX_QUEUED_CALLS = 64

    private val lock = Any()

    @Volatile
    private var apiCache: OkHttpClient? = null

    @Volatile
    private var downloadCache: OkHttpClient? = null

    /** 镜像竞速探测专用客户端缓存：短超时、不自动重试 */
    @Volatile
    private var probeCache: OkHttpClient? = null

    /** 当前生效的 HTTP 代理主机；null 表示直连 */
    @Volatile
    private var proxyHost: String? = null

    /** 当前生效的 HTTP 代理端口；0 表示直连 */
    @Volatile
    private var proxyPort: Int = 0

    /** 代理认证用户名（空串=无认证）。fork 内部明文存储，不落日志。 */
    @Volatile
    private var proxyUser: String = ""

    /** 代理认证密码（空串=无认证）。fork 内部明文存储，不落日志。 */
    @Volatile
    private var proxyPass: String = ""

    /** 是否强制 IPv4 优先 DNS（默认 true）。关闭时用 Dns.SYSTEM 原行为。 */
    @Volatile
    private var forceIpv4: Boolean = true

    /**
     * IPv4 优先 DNS：把 IPv4 地址排到列表前面，IPv6 留在尾部兜底。
     * 国内多数移动网络 IPv6 直连不通，OkHttp 顺序尝试地址时先撞 IPv6 失败会造成连接风暴。
     * 纯 IPv6 主机天然不受影响（列表里只有 IPv6，排序不变）。
     */
    private object Ipv4FirstDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            return all.sortedBy { if (it is Inet4Address) 0 else 1 }
        }
    }

    /** 当前生效的 DNS（forceIpv4 开启时 IPv4 优先，否则系统默认） */
    private fun currentDns(): Dns = if (forceIpv4) Ipv4FirstDns else Dns.SYSTEM

    /** 配置是否强制 IPv4 优先；调用后清空 client 缓存立即生效（与 setProxy 同范式） */
    fun setForceIpv4(enabled: Boolean) {
        synchronized(lock) {
            forceIpv4 = enabled
            apiCache = null
            downloadCache = null
            probeCache = null
        }
    }

    /**
     * 配置全局 HTTP 代理。host 为 null 或 port 不在 1-65535 时视为不使用代理（直连）。
     * 调用后清空已构建的客户端缓存，使下次获取时按新代理重建，立即生效。
     * 本对象不持有 Context，仅依赖 java.net 标准库与 OkHttp 内置代理支持。
     */
    fun setProxy(host: String?, port: Int) {
        synchronized(lock) {
            proxyHost = host
            proxyPort = port
            // 使既有客户端失效：下次 apiClient()/downloadClient() 时重建，代理立即生效
            apiCache = null
            downloadCache = null
            probeCache = null
        }
    }

    /**
     * 配置代理认证凭据（明文，fork 内部）。user/pass 均空串表示无认证。
     * 与 [setProxy] 独立调用，同样使既有客户端重建生效。
     */
    fun setProxyAuth(user: String, pass: String) {
        synchronized(lock) {
            proxyUser = user
            proxyPass = pass
            apiCache = null
            downloadCache = null
            probeCache = null
        }
    }

    /** 构建代理实例；仅在代理主机与端口均有效时返回非 null，否则返回 null（直连） */
    private fun currentProxy(): Proxy? {
        val host = proxyHost ?: return null
        if (proxyPort !in 1..65535) return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, proxyPort))
    }

    /**
     * 代理认证器：收到 407 时回 `Proxy-Authorization: Basic base64(user:pass)`。
     * user/pass 均空串时返回 null（不挂认证，行为与改动前一致）。
     */
    private fun proxyAuthenticator(): okhttp3.Authenticator? {
        val user = proxyUser
        val pass = proxyPass
        if (user.isEmpty() && pass.isEmpty()) return null
        return okhttp3.Authenticator { _, response ->
            if (response.code != 407) return@Authenticator null
            // 避免无限重试：已带过 Basic 头则不再重发
            val header = response.request.header("Proxy-Authorization")
            if (header != null && header.startsWith("Basic ")) return@Authenticator null
            val credential = okhttp3.Credentials.basic(user, pass)
            response.request.newBuilder()
                .header("Proxy-Authorization", credential)
                .build()
        }
    }

    /** 普通 API 客户端（各平台 API、HLS、更新检查） */
    fun apiClient(): OkHttpClient {
        apiCache?.let { return it }
        synchronized(lock) {
            apiCache?.let { return it }
            return buildApi().also { apiCache = it }
        }
    }

    /** 下载专用客户端：大 Dispatcher + 长超时，不锁死分片并发 */
    fun downloadClient(): OkHttpClient {
        downloadCache?.let { return it }
        synchronized(lock) {
            downloadCache?.let { return it }
            return buildDownload().also { downloadCache = it }
        }
    }

    /**
     * 镜像竞速探测专用客户端：connect≈8s / read≈10s，[retryOnConnectionFailure]=false。
     * 只用于任务准备阶段对「直连 + 各镜像」做轻量 Range=0-0 测速；**不参与实际分片下载**，
     * 因此超时短、不自动重试（防 connect 超时被 OkHttp 翻倍成 ~30s）。下载主体仍走 [downloadClient]。
     */
    fun probeClient(): OkHttpClient {
        probeCache?.let { return it }
        synchronized(lock) {
            probeCache?.let { return it }
            return buildProbe().also { probeCache = it }
        }
    }

    /**
     * 释放两个客户端的空闲连接（socket + Conscrypt/HTTP2 缓冲），由 Application.onTrimMemory
     * 在系统内存压力下调用。只影响空闲连接，进行中的请求不受影响；任何异常都不得抛给调用方。
     */
    fun evictIdleConnections() {
        runCatching { apiCache?.connectionPool?.evictAll() }
        runCatching { downloadCache?.connectionPool?.evictAll() }
        runCatching { probeCache?.connectionPool?.evictAll() }
    }

    private fun buildApi(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .apply {
                // 配置了有效代理时注入；否则保持默认直连，行为与改动前完全一致
                currentProxy()?.let { proxy -> proxy(proxy) }
                proxyAuthenticator()?.let { auth -> proxyAuthenticator(auth) }
                dns(currentDns())
            }
            .build()
    }

    private fun buildDownload(): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            // 排队 Call 上限：下载分片走同步 call.execute()，根本不经过这个队列 —— 真实并发由
            // DownloadManager 的 inflightLimiter 与 chunkIoDispatcher 决定（见 Agent.md §5.1.1）
            maxRequests = MAX_QUEUED_CALLS
            maxRequestsPerHost = MAX_QUEUED_CALLS
        }
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            // 空闲连接池收紧：默认 64 条 × 5 分钟会常驻 socket + Conscrypt 缓冲，
            // 下载是突发式，1 分钟足够复用
            .connectionPool(
                ConnectionPool(
                    maxIdleConnections = 8,
                    keepAliveDuration = 1,
                    timeUnit = TimeUnit.MINUTES
                )
            )
            // ★ 下载客户端固定只用 HTTP/1.1（2026-10-01）：
            //   OkHttp 的 HTTP/2 客户端对外声明「每流 16MB 接收窗口」（OKHTTP_CLIENT_WINDOW_SIZE，见 Agent.md §5.1.1），
            //   消费端一慢（写盘慢/限速挂起），读线程仍会把数据堆进该流的 readBuffer，几十路叠加即撑满 256MB 堆
            //   —— log/oom 两份崩溃的栈（Http2Stream$FramingSource.receive → SegmentPool.take）正在这里。
            //   HTTP/1.1 没有应用层流窗口，读多少由 TCP 背压决定，堆占用只剩每路 64KB 读缓冲。
            //   代价：分片不再多路复用，每路各占一条连接。若实测总速明显变差，把下面一行改回
            //   listOf(Protocol.HTTP_2, Protocol.HTTP_1_1) 即可；要确认协议是否生效，可在 ChunkDownloader 里临时打印 response.protocol。
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .apply {
                // 配置了有效代理时注入；否则保持默认直连，行为与改动前完全一致
                currentProxy()?.let { proxy -> proxy(proxy) }
                proxyAuthenticator()?.let { auth -> proxyAuthenticator(auth) }
                dns(currentDns())
            }
            .build()
    }

    /** 镜像竞速探测 client：短超时 + 不自动重试（见 [probeClient] 注释） */
    private fun buildProbe(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .apply {
                currentProxy()?.let { proxy -> proxy(proxy) }
                proxyAuthenticator()?.let { auth -> proxyAuthenticator(auth) }
                dns(currentDns())
            }
            .build()
    }
}
