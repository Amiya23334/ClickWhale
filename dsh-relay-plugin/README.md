# dsh-relay-plugin

跟着 DSH 一起启动 [dsh-mobile](https://github.com/Amiya23334/dsh-notes) 的中转，让手机 App 连进来。

## 它做什么

- DSH 启动 → 拉起中转（`dsh-mobile/relay/dsh-relay.mjs`，监听 `0.0.0.0:8090`）
- 中转进程退出 → 按 `restartDelayMs` 自动重启
- DSH 退出 → 一起收掉

密钥不归它管：中转自己发现 `~/.dsh/dsh-relay/key.txt` 不存在就生成一份并打印到控制台。

## 配置项

在 DSH 的插件设置里改（`Config` 会渲染成表单）：

| 字段 | 说明 |
|---|---|
| `relayPath` | 中转脚本绝对路径 |
| `host` | 监听地址，`0.0.0.0` = 局域网可访问 |
| `port` | 监听端口，`0` = 用中转默认的 8090 |
| `autoRestart` | 挂了是否自动重启 |
| `restartDelayMs` | 重启前等待毫秒数，最小 1000 |

## 排查

中转起没起来：浏览器打开 `http://<电脑IP>:8090/`（状态页），或看 DSH 日志里的 `[relay]` 行。
