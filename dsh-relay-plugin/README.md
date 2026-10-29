# dsh-relay-plugin

跟着 DSH 一起启动手机中转，让 [点击肥鱼](../../README.md)（安卓 App）连进来。

## 一键安装

双击 `install.cmd`，或者命令行：

```
install.cmd            :: 默认装进 desktop profile
install.cmd web        :: 装进别的 profile
```

它做两件事，两件都必须做：

1. 往 profile 的 `package.json` 里写依赖（`link:` 指向本目录）
2. 把插件名加进 `dsh.profile.bundles`

只做第 1 步是不够的——DSH 决定加载哪些 bundle 看的是 `dsh.profile.bundles`，
不在那个数组里的话，包装了也不会加载。装完**重启 DSH** 才生效。

如果 `install.cmd` 报错，按它的提示处理；它失败通常是因为 Node.js 不在 PATH 里。

## 它做什么

- DSH 启动 → 拉起中转（默认找本仓库上一级的 `dsh-mobile/relay/dsh-relay.mjs`，监听 `0.0.0.0:8090`）
- 中转进程退出 → 按 `restartDelayMs` 自动重启
- DSH 退出 → 一起收掉
- 插件详情页里有一块「手机中转配置」：改监听地址/端口/自动重启，看运行状态和访问密钥

密钥不归它管：中转自己发现 `~/.dsh/dsh-relay/key.txt` 不存在就生成一份并打印到控制台。

改过的配置存在 `~/.dsh/dsh-relay/config.json`，优先级是「这个文件 > patch 行里的 config > 默认值」。
不写回 `cordis.patch.yml`——那是 DSH 管的，下次组合会被覆盖。

## 配置项

在 DSH 的插件详情页里改：

| 字段 | 说明 |
|---|---|
| `relayPath` | 中转脚本绝对路径；留空则自动推断 |
| `host` | 监听地址，`0.0.0.0` = 局域网可访问 |
| `port` | 监听端口，`0` = 用中转默认的 8090 |
| `autoRestart` | 挂了是否自动重启 |
| `restartDelayMs` | 重启前等待毫秒数，最小 1000 |

## 排查

中转起没起来：浏览器打开 `http://<电脑IP>:8090/`（状态页），或看 DSH 日志里的 `[relay]` 行。

插件详情页显示「已停止」但端口有人在听：多半是上一个实例的孤儿进程没退干净（Windows 上
`child.kill()` 发 SIGTERM 未必生效），点「只重启」会硬杀一次。
