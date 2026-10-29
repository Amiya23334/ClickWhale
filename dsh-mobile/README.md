# DSH Mobile

把电脑上的 [DeepSeek Harness](https://deepseek.com) 搬到手机上。

手机和电脑**镜像同一个会话**——你在电脑上发起的话题，手机上能接着聊；手机上发的消息，电脑上立刻看到。不是投屏，是一个真正的双端客户端。

<p align="center">
  <img src="design/feiyu_icon.png" width="120" alt="Feiyu">
</p>

---

## 它是什么

DSH 桌面版跑一个本地 Web 服务，端口每次启动随机。手机浏览器打开会有一堆问题（Origin 校验 403、Cookie 在 WebSocket 上升级时丢失、局域网权限静默失败）。本项目做了两件事：

| 组件 | 位置 | 作用 |
|---|---|---|
| **DSH Relay** | `relay/dsh-relay.mjs` | 跑在电脑上的中转，把 DSH 的 Web 服务开给局域网，修掉 Origin/Cookie/目录选择器的问题 |
| **DSH Mobile** | `app/` | Android 原生客户端（Kotlin + Jetpack Compose），直连中转，渲染对话 |

```
┌──────────────┐   HTTP/WS    ┌──────────────┐   HTTP/WS   ┌──────────────┐
│  DSH Mobile  │ ───────────▶ │  DSH Relay   │ ──────────▶ │  DSH Desktop │
│  (Android)   │   :8090      │  (Node.js)   │  随机端口   │  (Electron)  │
└──────────────┘              └──────────────┘             └──────────────┘
```

## 特性

**会话**

- **镜像同一会话**：电脑上发起的，手机上能接着聊；手机发完电脑上立刻看到
- **实时流式**：token 逐个往外蹦，不是等全部算完才刷
- **Markdown 渲染**：代码块带语法高亮，表格、引用、列表正常显示
- **工具调用折叠**：默认收起参数和结果，点开才看，不占屏幕
- **思考过程可展开**：模型推理默认折叠，想看再点
- **审批卡片**：需要确认的操作直接在手机上点
- **新建时选工作区**：不用回到电脑前点对话框
- **归档**：左滑收起，误触有 5 秒撤回

**界面（原生 Compose，不是 WebView）**

- **主题三档**：跟随系统 / 夜间 / 日间，跟随时系统日落切深色会当场跟着变
- **八套强调色**：不只换按钮颜色，还会染进底色和卡片（深色 5.5%、浅色 3%），切换后整个界面的气氛都变
- **字体四档**：系统 / 衬线 / 等宽 / **自己的 ttf**（选文件即装，英文跟字体、中文自动回退系统字体）
- **自定义背景图**：支持透明 PNG，叠在底色上；带模糊（0/8/16/28dp）和浓度（100/85/70/50%）两档
- **深色克制**：层级只用间距和字号，不加边框和阴影——手机要滚长对话，每层实时模糊会掉帧
- **矢量图标**：不用 emoji，跨 ROM 显示一致
- **原生手势与转场**：会话列表 ↔ 聊天滑动切换

**文件**

- 浏览电脑文件（不进聊天也能翻目录）
- 下载 / 上传
- 切盘符（C 盘之外的也进得去）

**连接**

- **自动发现**：App 扫局域网自己找到电脑，不用手填 IP
- **访问密钥**：中转校验 `x-relay-key`，填不对连不进来

## 快速开始

### 1. 电脑上启动中转

需要 DSH 桌面版已经在跑，电脑上有 Node.js（18+）。

中转由 `dsh-relay-plugin` 随 DSH 一起启动，**不用手动跑**（手动再起一份会撞端口）：

```
dsh plugin --profile desktop add link:<本仓库绝对路径>/dsh-relay-plugin
```

装好后在 DSH 的插件页打开「点击肥鱼 · 中转」，能看到运行状态、改监听地址和端口。

中转起来后的日志长这样：

```
[relay] 正在监听 http://0.0.0.0:8090
[relay] 已连接 DSH: http://127.0.0.1:1120
[relay] 已开自动发现（UDP 8099）
```

### 2. 手机上安装 App

从 [Releases](../../releases) 下载 APK 安装，或者自己编译：

```bash
cd app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. 连上

打开 App → 「搜索电脑（自动）」，或者手动填中转打印的地址（`http://192.168.x.x:8090`）。

## 自己编译

```bash
# 环境：JDK 17+、Android SDK、Gradle 8.7+
cd app
./gradlew assembleDebug
```

配套版本（实测可用）：AGP 9.4.1 / Gradle 9.8.2 / Kotlin 2.2.10 / compileSdk 37 / minSdk 26。

## 已知限制

- **只支持局域网**。跨网络需要自己搭内网穿透（Tailscale、frp 之类），本项目不做
- **没有加密**。中转和手机之间是明文 HTTP/WS，只在可信局域网里用。不要在公共 Wi-Fi 上跑
- **一个中转对应一个 DSH**。多台电脑要各自跑一份
- **DSH 内部 API 可能变**。客户端接的是 `/api/remote.mux`，不是公开接口，DSH 大版本升级后可能需要跟着改

## 相关项目

- [DSHgo](https://github.com/just-a-FA-for-name/DSHgo) —— 同类项目，也是把 DSH 搬到手机
- [Agent Client Protocol](https://agentclientprotocol.com) —— DSH 自带的官方 ACP 服务（`dsh --profile acp`）适合脚本化/自动化场景，但它是独立进程、只暴露标准 ACP 表面，不支持「镜像你正在用的那个桌面会话」，所以本项目没走这条路

## 许可

MIT。见 [LICENSE](LICENSE)。

本项目是非官方客户端，与 DeepSeek 没有隶属关系。
