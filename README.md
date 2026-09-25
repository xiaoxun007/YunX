# YunX（云析）
 
网盘分享链接解析与高速下载的 Android 应用。粘贴分享链接，就能浏览分享内容并直接下载文件。

> ## 🔀 本 Fork（xiaoxun007/YunX）
>
> 基于上游 [CYQawa/YunX](https://github.com/CYQawa/YunX) 的二次开发，**保留上游全部原有功能**（网盘分享解析、高速下载、登录、认证备份等），在此基础上新增以下能力。安装包请到本仓库 [Releases](https://github.com/xiaoxun007/YunX/releases) 下载（每次构建自动发布）。
>
> ### 1. GitHub 解析器（核心新增）
>
> 把 GitHub 当网盘用：在「解析」页粘贴任意 GitHub 链接即可浏览并下载，支持三类链接：
>
> | 输入 | 解析结果 |
> |---|---|
> | 项目链接 `github.com/owner/repo` | 进入项目根目录，看到 **「代码」** 与 **「Releases」** 两个文件夹；fork 仓库会在根目录显示 `forked from 上游`（可点击直接进入上游仓库） |
> | 账号/组织链接 `github.com/owner` | 该账号所有可见仓库列表（名称 + 简介 + fork 标识），点击进入对应项目 |
> | 文件直链（`/releases/download/...`、`/raw/...`、`raw.githubusercontent.com/...`） | 直接解析出该文件并加入下载 |
>
> - **「代码」文件夹**：默认分支文件树，逐级浏览，首项固定提供「下载完整源码 ZIP」（codeload 直链，一键打包下载整个仓库）
> - **「Releases」文件夹**：所有发布版本，每个版本一个子文件夹，内含该版全部资产文件
> - **懒加载**：默认只解析当前层，点进哪个文件夹才请求哪一层，不一次性拉全树，省流量更流畅
> - **项目根目录展示 README**：解析后根目录下方直接展示该仓库的 README 原文
> - **下载**：所有 GitHub 文件下载复用内置下载器（Range 分片并发 + 断点续传），并自动套用你配置的镜像前缀 / HTTP 代理
>
> ### 2. 自定义 GitHub 下载镜像
>
> 解决国内直连 GitHub 慢 / 失败的问题。设置 → 通用 → **GitHub 下载镜像**：
> - 输入镜像前缀（如 `https://gh.dpik.top/`、`https://github.akams.cn/`），之后更新弹窗「使用镜像站下载」和 GitHub 文件下载都会走该镜像
> - 留空 / 点「恢复默认」→ 使用内置默认镜像（`https://cdn.gh-proxy.org/`）
> - 校验：必须以 `http://` 或 `https://` 开头，自动补全结尾 `/`
>
> ### 3. HTTP 代理加速
>
> 设置 → 通用 → **网络代理**，可配置本地 / 局域网 HTTP 代理（Clash、v2rayNG 等），加速 GitHub 及访问困难的网盘下载：
> - 打开「启用代理」→ 填代理主机（手机本地代理填 `127.0.0.1`，电脑/路由器代理填其局域网 IP）与端口（Clash 常见 `7890`）
> - 保存立即生效、重启后自动恢复；关闭开关即恢复直连，不影响已下载任务
> - ⚠️ 代理填错会导致网络请求失败，关掉开关即可恢复
>
> ### 4. GitHub Token 管理
>
> 「网盘」页新增 **GitHub** 入口（与夸克/百度等网盘平级），可填写 / 清除个人访问令牌：
> - 用途：GitHub 匿名 API 限额为 60 次/小时，解析大树或频繁浏览容易触顶；填入 Token 后限额提升至 5000 次/小时
> - 生成方式：GitHub → Settings → Developer settings → Personal access tokens → Generate new token（勾选 `repo` 或只读权限即可）
> - 安全：Token 经 **Android Keystore AES-GCM** 加密存储（密钥不可导出），输入框不回显、不写日志、不明文落盘
>
> ### 5. 版本与发布策略
>
> - **版本号与上游对齐**：上游发 `1.2.6`，本 fork 即 `1.2.6-gh<n>`；上游升版后后缀从 1 重新计数（如 `1.2.7-gh1`）
> - **构建自动发布**：GitHub Actions 每次构建成功自动创建/更新对应版本的 Release，产物为 `YunX_release_{版本}.apk`，不依赖 Actions 产物过期
> - **更新检查兼容**：内置版本比较已兼容 `-gh<n>` 后缀，不会把 fork 版误判为旧版本
>
> ### 6. 提交与 Release 说明规范
>
> - **单行标题**：前缀 + 主题，前缀用 `feat:`（新功能）/ `fix:`（修复）/ `merge:`（合并上游）/ `docs:`（文档）/ `build:`（版本/构建）/ `chore:`（杂务），如 `feat: GitHub 文件显示更新时间`
> - **多行正文**：标题后空行列要点，说明动机与关键改动；涉及上游合并时写明上游 SHA（如 `合并上游 b0d2eb2 流式落盘修复`）
> - **版本号规则**：每次发版 `versionName` 后缀 `gh<n>` 递增、`versionCode` 同步 +1（gh4=11, gh5=12, ...）
> - **Release notes**：CI 自动从上一个 tag 生成变更列表（commit 短链 + 标题 + @作者 + compare 链接），人工编辑过的 Release 不会被覆盖

## 支持平台

**不建议用百度网盘，可能导致账号被风控！！！**
- 夸克网盘
- UC 网盘
- 迅雷网盘
- 百度网盘
- 123云盘
- 139 网盘（和彩云）
- 123 云盘
- ~~其他网盘懒得写了，如有需要请打开一个issue~~

## 功能

- **分享链接解析**：识别夸克 / UC / 迅雷 / 百度 / 139 / 123 的分享链接，自动匹配提取码
- **高速下载**：Range 分片并发 + 断点续传，任务保存请求头与固定分片规划，并发上限 32
- **临时转存清理**：百度/迅雷取链后清理；夸克保留到下载完成或删除任务后清理
- **登录**：夸克 / UC / 百度 / 139 使用 WebView Cookie；迅雷使用密码/短信；123 使用账号密码换取 JWT
- **认证备份**：使用用户口令派生密钥，以 AES-GCM 加密 Cookie/JWT 备份文件
- **剪贴板识别**：复制分享链接后回到应用，提示一键粘贴解析

## 截图

| 解析直链 | 分享解析 | 下载管理 |
|:---:|:---:|:---:|
| ![解析输入](images/Link.jpg) | ![文件列表](images/Parsing.jpg) | ![下载管理](images/Download.jpg) |

| 网盘登录 | 设置 | 关于 |
|:---:|:---:|:---:|
| ![网盘登录](images/Login.jpg) | ![设置](images/Setting.jpg) | ![关于](images/about.jpg) |

## 使用

1. 在「网盘」页登录需要用的网盘账号
2. 在「解析」页粘贴分享链接（可带提取码）
3. 浏览分享内容，点击文件获取下载直链
4. 「下载」页查看进度，支持暂停 / 继续 / 删除 / 打开

## 技术栈

- Kotlin
- Jetpack Compose + Material 3
- Room（凭证与下载任务持久化）
- OkHttp（网络请求 + 分片下载）
- KSP

## 构建

要求：minSdk 21，targetSdk 34。

本 fork 源码：

```
git clone https://github.com/xiaoxun007/YunX.git
```

用 Android Studio 打开项目直接构建即可。项目在 AndroidIDE 上开发调试，理论上也兼容其它 Android 构建环境。

> 发布物说明：本 fork 的 Releases 产物为 **Release 构建**（R8 混淆 + 资源压缩，体积约 4MB，使用 debug keystore 签名，可直接安装）；`assembleRelease` 即可复现。

## 免责声明

本项目仅供个人学习与技术交流，请勿用于商业用途。下载内容版权归原作者所有，请在下载后 24 小时内删除。使用本项目产生的任何后果由使用者自行承担。

## 开源协议

本项目基于 [GNU AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.html) 协议开源，详见根目录 [LICENSE](./LICENSE)。

## 关于协议逆向

部分网盘平台的解析基于抓包分析与开源项目（如 alist）的协议研究整理，接口可能随官方调整而失效，请以实际运行结果为准。

# 耻辱榜
**倒卖的你是活不起了是吗😂**
* 注：云析完全免费开源，如果你下载到要钱的，那么你就是被骗了，请立马去退款

- 倒卖狗🐶：qq1360735243

## 应用自我保护说明

由于本项目曾遭遇恶意二次打包（注入卡密验证弹窗后收费倒卖），代码中加入了一组**安装包完整性自检逻辑**，针对云注入的检测，用于识别并阻止被篡改的安装包运行。在此向用户与协作者说明：

- 该逻辑为**防御性设计**：不申请额外权限，不收集、不上传任何个人数据，不影响任何正常功能；
- 首次启动会展示一次「官方开源版」安全提示，确认后不再出现；
- 这部分代码刻意不写注释、字符串加密存放，属于对抗逆向篡改的设计需要，**并非后门或恶意代码**，请勿误解；
- 详见 [Agent.md](./Agent.md) 第 9 节。

再次强调：**云析永远免费开源，任何收费版本均为诈骗**。

## Star History

[![Star History Chart](https://api.star-history.com/chart?repos=CYQawa/YunX&type=date&legend=top-left&sealed_token=hccCg_4ek01_Sz38X79eMbjM11mNpOZti6_hLoztWW4Zdtx-8FScydd7YTdiCBUWvgpsuGDO70RrUKP-bOfbI3Gw8BnME1zIl5EHA9JWsv--_DDwWPjvKbZiAGNDslG3ZTDZ-Ssiapu7j08W4fPT6emGWaIIuawHoIw3Nic_xQu7hUSVO6_YeJRGRoEy)](https://www.star-history.com/?repos=YunX%2FYunX%2CCYQawa%2FYunX&type=date&legend=top-left)