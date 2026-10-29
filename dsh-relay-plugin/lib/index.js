/**
 * DSH Relay 插件。
 *
 * 以前中转要人手双击 `tools/relay/启动中转.cmd`——忘了开、开了两个、开着旧版本，
 * 三种都真发生过。这个插件把它挂到 DSH 生命周期上：DSH 起来中转就起来，
 * 进程挂了 5 秒后自动重启，DSH 退出时一起收掉。
 *
 * 密钥不在这里管：中转自己发现 ~/.dsh/dsh-relay/key.txt 不存在就会生成一份，
 * 并打印到控制台。这样「密钥」只有一个来源，改也只需要改那个文件。
 *
 * 配置来源的优先级：配置文件 > patch 行里的 config > 内置默认值。
 * 为什么要额外的配置文件：patch.yml 是 DSH 管的，界面改完写回去会被下一次
 * 组合覆盖，所以在 ~/.dsh/dsh-relay/config.json 里存用户改过的值。
 */
import { spawn } from 'node:child_process'
import http from 'node:http'
import net from 'node:net'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { configObject, editable, settingsConfig } from './schema.js'

export const name = 'dsh-relay-plugin'

/** webServer 用局部 inject 等（见 apply），这里不阻塞整行注册 */
export const inject = []

const STATE_DIR = path.join(os.homedir(), '.dsh', 'dsh-relay')
const CONFIG_FILE = path.join(STATE_DIR, 'config.json')
const KEY_FILE = path.join(STATE_DIR, 'key.txt')

/**
 * 往上一级找同仓库里的 dsh-mobile/relay。
 * 写死绝对路径的话别人 clone 下去必然跑不起来；这份仓库里
 * dsh-relay-plugin 和 dsh-mobile 是并列的两个目录，推断得出来。
 */
const PLUGIN_ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const GUESSED_RELAY = path.resolve(PLUGIN_ROOT, '..', 'dsh-mobile', 'relay', 'dsh-relay.mjs')

const DEFAULTS = {
  // 留空 = 用推断出来的路径；也可以在插件设置里显式填
  relayPath: '',
  host: '0.0.0.0',
  port: 0,
  autoRestart: true,
  restartDelayMs: 5000,
}

export const Config = settingsConfig(
  (value) => {
    const raw = configObject(value ?? {})
    const out = { ...DEFAULTS, ...raw }
    if (typeof out.relayPath !== 'string') out.relayPath = ''
    out.port = Number(out.port) || 0
    out.restartDelayMs = Math.max(1000, Number(out.restartDelayMs) || 5000)
    return out
  },
  {
    relayPath: editable('string', '中转脚本的绝对路径（dsh-relay.mjs）'),
    host: editable('string', '监听地址，0.0.0.0 表示局域网可访问'),
    port: editable('number', '监听端口；0 = 用中转自己的默认值 8090'),
    autoRestart: editable('boolean', '中转进程挂掉后自动重启'),
    restartDelayMs: editable('number', '自动重启前等待的毫秒数'),
  }
)

function readSavedConfig() {
  try {
    const raw = JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8'))
    return raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {}
  } catch {
    return {}
  }
}

function writeSavedConfig(patch) {
  fs.mkdirSync(STATE_DIR, { recursive: true })
  const merged = { ...readSavedConfig(), ...patch }
  fs.writeFileSync(CONFIG_FILE, JSON.stringify(merged, null, 2) + '\n', 'utf8')
  return merged
}

function readKeyMasked() {
  try {
    return fs.readFileSync(KEY_FILE, 'utf8').trim()
  } catch {
    return ''
  }
}

export function apply(ctx, config) {
  const cfg = { ...DEFAULTS, ...configObject(config ?? {}), ...readSavedConfig() }
  if (!cfg.relayPath || !cfg.relayPath.trim()) cfg.relayPath = GUESSED_RELAY
  if (!fs.existsSync(cfg.relayPath)) {
    console.warn('[relay-plugin] 找不到中转脚本：' + cfg.relayPath)
    console.warn('[relay-plugin] 请在插件设置里把「中转脚本」填成 dsh-relay.mjs 的绝对路径')
  }

  let child = null
  let restartTimer = null
  let disposed = false
  let startedAt = 0
  let lastExit = null

  const start = () => {
    if (disposed) return
    const args = [cfg.relayPath, '--host', cfg.host || '0.0.0.0']
    if (cfg.port) args.push('--port', String(cfg.port))

    startedAt = Date.now()
    child = spawn(process.execPath, args, {
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
    })

    child.stdout.on('data', (buf) => process.stdout.write(buf))
    child.stderr.on('data', (buf) => process.stderr.write(buf))

    child.on('error', (err) => {
      console.warn(`[relay-plugin] 启动失败：${err?.message ?? err}`)
    })

    child.on('exit', (code, signal) => {
      child = null
      lastExit = { code, signal, at: Date.now() }
      if (disposed) return
      if (!cfg.autoRestart) {
        console.warn(`[relay-plugin] 中转退出（code=${code} signal=${signal}），autoRestart 关闭，不再拉起`)
        return
      }
      console.warn(
        `[relay-plugin] 中转退出（code=${code} signal=${signal}），${cfg.restartDelayMs / 1000}s 后重启`
      )
      restartTimer = setTimeout(start, cfg.restartDelayMs)
    })
  }

  /**
   * 硬杀子进程。
   *
   * Windows 上 child.kill() 发的是 SIGTERM，node 子进程不一定响应；
   * 实测会留下占着 8090 的孤儿，下一个实例直接 EADDRINUSE 退出，
   * 界面就一直显示「已停止」但端口其实有人听。所以直接 taskkill /F。
   */
  const hardKill = (proc) => {
    if (!proc) return
    try {
      if (process.platform === 'win32') {
        spawn('taskkill', ['/PID', String(proc.pid), '/T', '/F'], {
          stdio: 'ignore',
          windowsHide: true,
        })
      } else {
        proc.kill('SIGKILL')
      }
    } catch {}
  }

  /**
   * 立刻重启：硬杀当前进程，显式置空 child，等它真退出再拉新的。
   * 不能只依赖 exit 处理器——autoRestart 关掉时那条路不会走，
   * 重启按钮就变成「杀了不回来」。
   */
  const restartNow = () => {
    if (restartTimer) {
      clearTimeout(restartTimer)
      restartTimer = null
    }
    hardKill(child)
    child = null
    // 给系统几百毫秒释放端口，否则新进程直接 EADDRINUSE
    setTimeout(start, 700)
  }

  start()

  // ── 给插件详情页用的读写端点 ──
  // 用局部 inject：webServer 没起的时候不该让整行加载失败。
  ctx.inject(['webServer'], (wctx) => {
    const json = (res, code, body) => {
      res.writeHead(code, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
      res.end(JSON.stringify(body))
    }

    const readBody = (req) =>
      new Promise((resolve, reject) => {
        let raw = ''
        req.on('data', (chunk) => {
          raw += chunk
          if (raw.length > 64 * 1024) reject(new Error('body too large'))
        })
        req.on('end', () => {
          try {
            resolve(raw ? JSON.parse(raw) : {})
          } catch (err) {
            reject(err)
          }
        })
        req.on('error', reject)
      })

    /**
     * 中转到底活没活，用 HTTP ping 探测，而不是只看 child。
     * 插件会被 apply 多次（装/卸载/热更新），child 只反映最后一次 apply 的那个实例，
     * 出现「端口在听但 running 显示 false」正是这么来的。
     */
    // 用 TCP 连一下判断端口有没有人听（不依赖 HTTP、不受代理影响）
    const probeRelay = () =>
      new Promise((resolve) => {
        const socket = net.connect({ host: '127.0.0.1', port: cfg.port || 8090 })
        const finish = (value) => {
          try {
            socket.destroy()
          } catch {}
          resolve(value)
        }
        socket.on('connect', () => finish(true))
        socket.on('error', () => finish(false))
        setTimeout(() => finish(false), 900)
      })

    const state = async () => ({
      relayPath: cfg.relayPath,
      host: cfg.host,
      port: cfg.port,
      autoRestart: cfg.autoRestart,
      restartDelayMs: cfg.restartDelayMs,
      running: (child !== null) || (await probeRelay()),
      owned: child !== null,
      // 端口上已经有别人在听（多半是上一个实例没收干净的孤儿进程）
      orphan: !child && (await probeRelay()),
      pid: child?.pid ?? null,
      startedAt: child ? startedAt : 0,
      lastExit,
      keyFile: KEY_FILE,
      key: readKeyMasked(),
    })

    const disposers = []

    disposers.push(
      wctx.webServer.register({
        kind: 'exact',
        path: '/plugins/dsh-relay-plugin/state',
        handler: async (req, res) => {
          if (req.method !== 'GET') return json(res, 405, { error: 'method not allowed' })
          json(res, 200, await state())
        },
      })
    )

    disposers.push(
      wctx.webServer.register({
        kind: 'exact',
        path: '/plugins/dsh-relay-plugin/config',
        handler: async (req, res) => {
          if (req.method !== 'POST') return json(res, 405, { error: 'method not allowed' })
          try {
            const body = await readBody(req)
            const patch = {}
            for (const key of Object.keys(DEFAULTS)) {
              if (body[key] !== undefined) patch[key] = body[key]
            }
            if (patch.relayPath !== undefined) {
              if (typeof patch.relayPath !== 'string' || !patch.relayPath.trim()) {
                return json(res, 400, { error: 'relayPath 不能为空' })
              }
              cfg.relayPath = patch.relayPath.trim()
            }
            if (patch.host !== undefined) cfg.host = String(patch.host || '0.0.0.0')
            if (patch.port !== undefined) cfg.port = Number(patch.port) || 0
            if (patch.autoRestart !== undefined) cfg.autoRestart = Boolean(patch.autoRestart)
            if (patch.restartDelayMs !== undefined) {
              cfg.restartDelayMs = Math.max(1000, Number(patch.restartDelayMs) || 5000)
            }
            writeSavedConfig({
              relayPath: cfg.relayPath,
              host: cfg.host,
              port: cfg.port,
              autoRestart: cfg.autoRestart,
              restartDelayMs: cfg.restartDelayMs,
            })
            restartNow()
            json(res, 200, { ok: true, restarted: true })
          } catch (err) {
            json(res, 400, { error: err instanceof Error ? err.message : String(err) })
          }
        },
      })
    )

    disposers.push(
      wctx.webServer.register({
        kind: 'exact',
        path: '/plugins/dsh-relay-plugin/restart',
        handler: (req, res) => {
          if (req.method !== 'POST') return json(res, 405, { error: 'method not allowed' })
          restartNow()
          json(res, 200, { ok: true })
        },
      })
    )

    wctx.on('dispose', () => {
      for (const dispose of disposers) {
        try {
          dispose()
        } catch {}
      }
    })
  })

  ctx.on('dispose', () => {
    disposed = true
    if (restartTimer) clearTimeout(restartTimer)
    hardKill(child)
  })
}
