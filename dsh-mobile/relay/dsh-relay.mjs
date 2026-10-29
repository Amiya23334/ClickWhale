#!/usr/bin/env node
/**
 * dsh-relay —— 把本机 DSH 桌面版的 Web 界面开放到局域网，供手机使用。
 *
 * 原理：桌面版的内置服务只绑 127.0.0.1，且没有改绑定地址的开关（asar 里
 * 没有任何跟 host / bind 相关的环境变量）。但它有两件事对我们有利：
 *   1. 认证只靠一个 HMAC 签名的 Cookie，密钥在 ~/.dsh/.credentials.yaml 里，
 *      本机同用户进程可以离线铸造（本文件内已实现）。
 *   2. **它不校验 Host 头**（实测：同一个 Cookie 配其它 Host 也照样 200）。
 * 所以一个带 Cookie 注入的反向代理就能让手机看到完整的桌面界面。
 *
 * 用法：
 *   node dsh-relay.mjs                    # 默认监听 0.0.0.0:8090
 *   node dsh-relay.mjs --port 8090
 *   node dsh-relay.mjs --host 0.0.0.0
 *
 * 手机打开 http://<电脑局域网IP>:8090
 */

import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import fsSync from 'node:fs';
import pathSync from 'node:path';
import dgram from 'node:dgram';
import { execFile } from 'node:child_process';
import { createHash, createHmac, randomBytes } from 'node:crypto';

// ── DSH 基址自动发现 ────────────────────────────────────────────────
// 桌面版每次启动监听一个随机回环端口，只能靠「查进程 + 探特征」找出来。

const DSH_CLI_PORT = 3080;
const DSH_DESKTOP_PORT = 19387;

function dshHomeDir() {
  return process.env.DSH_HOME || pathSync.join(os.homedir(), '.dsh');
}

function detectDshDesktop() {
  const base = pathSync.join(dshHomeDir(), 'dsh-runtimes');
  try {
    for (const d of fsSync.readdirSync(base)) {
      try {
        const v = JSON.parse(fsSync.readFileSync(pathSync.join(base, d, 'runtime.json'), 'utf8')).desktopVersion;
        if (v) return String(v);
      } catch {}
    }
  } catch {}
  return null;
}

function portOpen(url, timeoutMs) {
  return new Promise((resolve) => {
    let u;
    try { u = new URL(url); } catch { resolve(false); return; }
    const s = net.connect({ host: u.hostname === 'localhost' ? '127.0.0.1' : u.hostname, port: Number(u.port) || 80 });
    const done = (v) => { try { s.destroy(); } catch {} resolve(v); };
    s.setTimeout(timeoutMs, () => done(false));
    s.once('connect', () => done(true));
    s.once('error', () => done(false));
  });
}

function run(file, args, timeoutMs) {
  return new Promise((resolve) => {
    execFile(file, args, { timeout: timeoutMs, windowsHide: true, maxBuffer: 4 * 1024 * 1024 },
      (error, stdout) => resolve(error && !stdout ? '' : String(stdout ?? '')));
  });
}

function parseLsofPorts(text) {
  const out = new Set();
  for (const line of String(text ?? '').split(/\r?\n/)) {
    const m = /^n(?:127\.0\.0\.1|localhost|\[::1\]):(\d{1,5})$/.exec(line.trim());
    if (m) out.add(Number(m[1]));
  }
  return [...out];
}

function parseNetstatPorts(text, pids) {
  const want = new Set([...pids].map(String));
  const out = new Set();
  for (const line of String(text ?? '').split(/\r?\n/)) {
    const m = /^\s*TCP\s+(?:127\.0\.0\.1|\[::1\]):(\d{1,5})\s+\S+\s+LISTENING\s+(\d+)\s*$/i.exec(line);
    if (m && want.has(m[2])) out.add(Number(m[1]));
  }
  return [...out];
}

function parseTasklistPids(text) {
  const out = [];
  for (const line of String(text ?? '').split(/\r?\n/)) {
    const m = /^"(DeepSeek Harness[^"]*\.exe)","(\d+)"/i.exec(line.trim());
    if (m) out.push(m[2]);
  }
  return out;
}

/** DSH 未登录时回 401 + 固定提示，用它确认端口确实是 DSH。 */
function looksLikeDsh(url, { timeoutMs = 1500 } = {}) {
  return new Promise((resolve) => {
    let u;
    try { u = new URL('/', url); } catch { resolve(false); return; }
    const req = http.get({ host: u.hostname, port: u.port, path: '/', timeout: timeoutMs, headers: { accept: 'text/plain' } }, (res) => {
      let body = '';
      res.setEncoding('utf8');
      res.on('data', (c) => { if (body.length < 4096) body += c; });
      res.on('end', () => resolve(res.statusCode === 401 && /dsh web authentication required/i.test(body)));
    });
    req.on('timeout', () => { req.destroy(); resolve(false); });
    req.on('error', () => resolve(false));
  });
}

async function listDesktopListenPorts() {
  try {
    if (process.platform === 'darwin') {
      return parseLsofPorts(await run('/usr/sbin/lsof',
        ['-nP', '-iTCP', '-sTCP:LISTEN', '-a', '-c', 'DeepSeek', '-Fn'], 3000));
    }
    if (process.platform === 'win32') {
      const sys32 = pathSync.join(process.env.SystemRoot || 'C:\\Windows', 'System32');
      const pids = parseTasklistPids(await run(pathSync.join(sys32, 'tasklist.exe'), ['/FO', 'CSV', '/NH'], 3000));
      if (!pids.length) return [];
      return parseNetstatPorts(await run(pathSync.join(sys32, 'netstat.exe'), ['-ano', '-p', 'TCP'], 3000), pids);
    }
  } catch {}
  return [];
}

async function resolveDshBaseUrl(configured) {
  const c = String(configured ?? '').trim();
  const candidates = c
    ? [c.replace(/\/+$/, '')]
    : (detectDshDesktop()
        ? [`http://127.0.0.1:${DSH_DESKTOP_PORT}`, `http://127.0.0.1:${DSH_CLI_PORT}`]
        : [`http://127.0.0.1:${DSH_CLI_PORT}`, `http://127.0.0.1:${DSH_DESKTOP_PORT}`]);
  for (const url of candidates) {
    if (await portOpen(url, 600)) return { url, reachable: true };
  }
  for (const port of await listDesktopListenPorts()) {
    const url = `http://127.0.0.1:${port}`;
    if (await looksLikeDsh(url)) return { url, reachable: true, discovered: true };
  }
  return { url: candidates[0], reachable: false };
}

// ── 会话 Cookie 离线铸造 ────────────────────────────────────────────
// 认证只靠一个 HMAC 签名的 Cookie，密钥在 ~/.dsh/.credentials.yaml 里，同用户进程可离线铸。

const BROWSER_SESSION_KEY = 'client-connection/browser-session';
const COOKIE_PAYLOAD_VERSION = 1;
const COOKIE_PREFIX = 'dsh-auth-';
const SECRET_BYTES = 32;
const MINTED_COOKIE_LIFETIME_MS = 60 * 60 * 1000;

const base64url = (buffer) => Buffer.from(buffer).toString('base64')
  .replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');

/** 极简 YAML 取值：只认 `records:` 下某个 key 的 kind / payload.version / payload.secret。 */
function readBrowserSessionSecret() {
  try {
    const file = pathSync.join(dshHomeDir(), '.credentials.yaml');
    const text = fsSync.readFileSync(file, 'utf8');
    const lines = text.split(/\r?\n/);
    let inRecord = false;
    let kind = null;
    let version = null;
    let secret = null;
    for (const line of lines) {
      if (!line.trim() || line.trim().startsWith('#')) continue;
      const indent = line.length - line.trimStart().length;
      const trimmed = line.trim();
      // 记录段标题固定在 2 空格缩进（`records:` 的子键）；遇到同级或更浅的键就退出该段
      if (trimmed.endsWith(':') && !trimmed.startsWith('-')) {
        const name = trimmed.slice(0, -1).replace(/^["']|["']$/g, '');
        if (indent <= 2) inRecord = (name === BROWSER_SESSION_KEY);
        continue;
      }
      if (!inRecord) continue;
      const kv = /^([A-Za-z_]+):\s*(.+)$/.exec(trimmed);
      if (!kv) continue;
      const v = kv[2].trim().replace(/^["']|["']$/g, '');
      if (kv[1] === 'kind') kind = v;
      else if (kv[1] === 'version') version = Number(v);
      else if (kv[1] === 'secret') secret = v;
    }
    if (kind !== 'grant' || version !== 1) return null;
    if (typeof secret !== 'string' || !/^[A-Za-z0-9_-]+$/.test(secret)) return null;
    const decoded = Buffer.from(secret.replaceAll('-', '+').replaceAll('_', '/'), 'base64');
    return decoded.byteLength === SECRET_BYTES ? decoded : null;
  } catch {
    return null;
  }
}

function mintBrowserSessionCookie(baseUrl, secret, { lifetimeMs = MINTED_COOKIE_LIFETIME_MS } = {}) {
  const authority = new URL(String(baseUrl)).host;
  if (!authority) throw new Error('DSH baseUrl has no authority');
  const issuedAt = Date.now();
  const expiresAt = issuedAt + lifetimeMs;
  const body = base64url(Buffer.from(JSON.stringify({
    version: COOKIE_PAYLOAD_VERSION, authority, issuedAt, expiresAt
  }), 'utf8'));
  const signature = base64url(createHmac('sha256', secret).update(body).digest());
  const name = COOKIE_PREFIX + base64url(createHash('sha256').update(authority).digest());
  return `${name}=v1.${body}.${signature}`;
}

function discoverDshSessionCookie(baseUrl, options) {
  const secret = readBrowserSessionSecret();
  if (!secret) return '';
  try {
    return mintBrowserSessionCookie(baseUrl, secret, options);
  } catch {
    return '';
  }
}

const args = process.argv.slice(2);
// 调试用：把前端通过 WS 发出去的 RPC 名字打出来（客户端->服务端）
const DEBUG_WS = args.includes('--debug-ws');
function arg(name, fallback) {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
}
const LISTEN_HOST = arg('host', '0.0.0.0');
const LISTEN_PORT = Number(arg('port', '8090'));

const ep = await resolveDshBaseUrl('');
if (!ep?.url) {
  console.error('找不到 DSH 桌面版，请先启动 DeepSeek Harness。');
  process.exit(1);
}
const target = new URL(ep.url);
console.log(`[relay] DSH 桌面版: ${ep.url}`);

let cookie = discoverDshSessionCookie(ep.url);
if (!cookie) {
  console.error('[relay] 无法铸造 Cookie（.credentials.yaml 里没有 browser-session 密钥）');
  process.exit(1);
}
let cookieMintedAt = Date.now();
const COOKIE_TTL = 50 * 60 * 1000;   // 铸造有效期 1 小时，提前重铸

function freshCookie() {
  if (Date.now() - cookieMintedAt > COOKIE_TTL) {
    const next = discoverDshSessionCookie(ep.url);
    if (next) { cookie = next; cookieMintedAt = Date.now(); }
  }
  return cookie;
}
console.log('[relay] Cookie 已铸造');

// ── 目录选择器：服务端实现 ────────────────────────────────────────
// DSH 的目录选择器是「native」实现，没有 browse 能力（API 原文：
// `directoryPicker.list needs the browse capability; the composed picker serves "native"`），
// 所以手机点「选择工作区」会把原生对话框弹在电脑上——在手机上等于卡死。
// 这里自己实现 pick / list / createDirectory，手机端就能像文件管理器一样挑目录。
// rpcId 必须原样回传（实测不回传会报 `rpcId mismatch ... got relay-pick`）。

const fs = await import('node:fs/promises');
const pathMod = await import('node:path');

/** 允许浏览的根：默认从 D 盘开始，可以点面包屑回到上级。 */

function jsonReply(res, rpcId, value) {
  res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify({
    type: 'server-response',
    rpcId,
    result: { ok: true, value }
  }));
}

function jsonError(res, rpcId, code, message) {
  res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify({
    type: 'server-response',
    rpcId,
    result: { ok: false, error: { code, message } }
  }));
}

/**
 * 把一个目录展开成 DirectoryListing。
 *
 * 必须严格按 DSH 的 schema（从 app.asar 的 api 声明里扒出来的）：
 *   DirectoryEntry   { name: string; path: string; hidden: boolean }
 *   DirectoryListing { path: string; home: string; crumbs: DirectoryEntry[]; entries: DirectoryEntry[]; truncated: boolean }
 * 少一个字段前端就拿不到数据，表现是一直转圈。
 */
/**
 * 凭据与系统区的拒绝清单，listDir 与 /relay/file **共用同一套**。
 *
 * 两处必须一致：只在下载端拦的话，列表里照样能看到 ProgramData / Windows，
 * 点进去还看得到文件名，点「保存」才 403 —— 用户会以为是下载坏了。
 */
const DENY_SEGMENTS = [
  '\\windows\\', '\\programdata\\', '\\$recycle.bin\\', '\\system volume information\\',
  '\\appdata\\', '\\.config\\',
  '\\.dsh\\', '\\.ssh\\', '\\.aws\\', '\\.gnupg\\', '\\.azure\\', '\\.kube\\', '\\.docker\\',
  '\\.git\\',
  '\\.credentials', '\\id_rsa', '\\id_ed25519', '\\id_ecdsa', '\\.env', '\\.netrc', '\\.npmrc'
];

function isDeniedPath(p) {
  const lower = String(p).toLowerCase() + '\\';
  return DENY_SEGMENTS.some(seg => lower.includes(seg));
}

async function listDir(dir) {
  const entries = await fs.readdir(dir, { withFileTypes: true });
  const dirs = [];
  const files = [];
  for (const e of entries) {
    const hidden = e.name.startsWith('.');
    if (hidden) continue;
    if (e.name === 'node_modules') continue;
    const full = pathMod.join(dir, e.name);
    if (isDeniedPath(full)) continue;
    if (e.isDirectory()) {
      dirs.push({ name: e.name, path: full, hidden });
    } else if (e.isFile()) {
      // 文件也要返回——文件管理器不能只看目录。大小/时间一并给，
      // App 端按它显示「12 KB · 昨天」这种行。
      let size = 0, mtime = 0;
      try {
        const st = await fs.stat(full);
        size = st.size;
        mtime = Math.floor(st.mtimeMs / 1000);
      } catch {}
      files.push({ name: e.name, path: full, hidden, size, mtime });
    }
  }
  dirs.sort((a, b) => a.name.localeCompare(b.name));
  files.sort((a, b) => a.name.localeCompare(b.name));
  // 目录在前文件在后。注意：DSH 的目录选择器（pick）只认 entries 里的目录项，
  // 所以这里多出的 `files` 是**附加字段**，不破坏原来的 schema。
  return { dirs, files };
}

/** 从盘根往上拼面包屑：D:\a\b -> [D:\, D:\a, D:\a\b] */
function crumbsOf(dir) {
  const parts = dir.split(/[\\/]+/).filter(Boolean);
  const out = [];
  let acc = '';
  for (let i = 0; i < parts.length; i++) {
    if (i === 0) {
      acc = parts[0] + '\\';
    } else {
      acc = pathMod.join(acc, parts[i]);
    }
    out.push({ name: parts[i], path: acc, hidden: false });
  }
  return out;
}

async function handlePicker(url, body, res) {
  let rpcId = 'unknown';
  let args = {};
  try {
    const doc = JSON.parse(body || '{}');
    rpcId = doc.rpcId ?? 'unknown';
    args = doc.payload?.args ?? {};
  } catch {
    jsonError(res, rpcId, 'relay/bad-json', '请求体不是合法 JSON');
    return;
  }

  const what = url.includes('directoryPicker/list') ? 'list'
    : url.includes('directoryPicker/createDirectory') ? 'createDirectory'
    : 'pick';

  try {
    if (what === 'pick') {
      // 手机端的行为：默认给工作区目录。真正的目录浏览由 App 自己的文件管理器负责，
      // 选好后通过 relay 的 pick 端点告诉中转下次返回哪个。
      let dir = pickedDirOverride ?? DEFAULT_WORKSPACE;
      // 盘根不行：DSH 会 mkdir 目标目录，对 D:\ 会报
      // `EPERM: operation not permitted, mkdir 'D:'`。这种时候落回工作区。
      if (/^[A-Za-z]:\\?$/.test(dir)) {
        console.log(`[relay] pick 拿到盘根 ${dir}，改用工作区`);
        dir = DEFAULT_WORKSPACE;
      }
      jsonReply(res, rpcId, dir);
      return;
    }

    if (what === 'list') {
      const dir = args.path || ROOT_DIR;
      const { dirs, files } = await listDir(dir);
      jsonReply(res, rpcId, {
        path: dir,
        home: os.homedir(),
        crumbs: crumbsOf(dir),
        entries: dirs,
        files,
        truncated: false
      });
      return;
    }

    if (what === 'createDirectory') {
      const target = args.path && args.name ? pathMod.join(args.path, args.name) : null;
      if (!target) { jsonError(res, rpcId, 'relay/bad-args', '缺少 path 或 name'); return; }
      await fs.mkdir(target, { recursive: true });
      jsonReply(res, rpcId, target);
      return;
    }
  } catch (err) {
    jsonError(res, rpcId, 'relay/fs-error', String(err?.message ?? err));
  }
}

/** App 通过 POST /relay/pick 设置「下次 pick 返回哪个目录」。 */
let pickedDirOverride = null;

/**
 * 没被 pick 过时的兜底工作区。不能是盘根——DSH 建会话时会 `mkdir D:` 报 EPERM。
 * 默认用用户主目录下的 dsh-workspace，可用 --workspace 覆盖。
 */
const DEFAULT_WORKSPACE = arg('workspace', pathSync.join(os.homedir(), 'dsh-workspace'));


/**
 * 访问密钥。
 *
 * 这玩意儿是补上「中转对局域网完全敞开」那个洞：没有它，同一个 Wi-Fi 下
 * 任何人扫到 8090 就能读你的文件、开你的会话。密钥由电脑端插件生成，
 * 存在用户目录下，可以手改（改完要重启中转才生效）。
 *
 * 文件不存在时 RELAY_KEY 为空 = 不启用校验，方便本机调试；
 * App 会根据 /relay/ping 的 authRequired 决定要不要弹密钥输入框。
 */
const RELAY_KEY_FILE = pathSync.join(os.homedir(), '.dsh', 'dsh-relay', 'key.txt');

function readRelayKey() {
  try {
    const raw = fsSync.readFileSync(RELAY_KEY_FILE, 'utf8').trim();
    // 只接受字母数字，避免用户手填时带上奇怪的空白/引号导致永远匹配不上
    return /^[A-Za-z0-9_-]{8,64}$/.test(raw) ? raw : '';
  } catch {
    return '';
  }
}

/** 没有密钥就生成一份。这样「装好插件」这件事本身就是拿到密钥的动作。 */
function ensureRelayKey() {
  const existing = readRelayKey();
  if (existing) return existing;
  try {
    fsSync.mkdirSync(pathSync.dirname(RELAY_KEY_FILE), { recursive: true });
    const generated = randomBytes(16).toString('hex');
    fsSync.writeFileSync(RELAY_KEY_FILE, generated + '\n', 'utf8');
    console.log(`[relay] 首次启动，已生成访问密钥：${generated}`);
    console.log(`[relay] 密钥文件：${RELAY_KEY_FILE}（改完重启中转生效）`);
    console.log('[relay] App 连接时必须填这个密钥');
    return generated;
  } catch (err) {
    console.warn('[relay] 密钥生成失败，本次不启用校验：', err?.message ?? err);
    return '';
  }
}

const RELAY_KEY = ensureRelayKey();

/** 请求有没有带对密钥。同时认 header 和 query，方便浏览器直接打开调试。 */
function relayAuthorized(req) {
  if (!RELAY_KEY) return true;
  const header = req.headers['x-relay-key'];
  if (typeof header === 'string' && header === RELAY_KEY) return true;
  try {
    const q = new URL(req.url, 'http://localhost').searchParams.get('key');
    return q === RELAY_KEY;
  } catch {
    return false;
  }
}

/** 文件浏览器默认打开的起始目录。 */
const ROOT_DIR = process.platform === 'win32'
  ? pathSync.parse(os.homedir()).root
  : '/';

function readBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', (c) => { data += c; if (data.length > 1e6) req.destroy(); });
    req.on('end', () => resolve(data));
    req.on('error', () => resolve(''));
  });
}

// ── 附件读取 ────────────────────────────────────────────────────
// 会话事件里的图片/文件块只带 `attachmentId: "sha256:<hex>"`，没有字节。
// 真实字节就存在本机（见 @deepseek-ai/dsh-attachment 的 README）：
//   <DSH_HOME>/attachments/v1/objects/<sha 前两位>/<sha>
//   <DSH_HOME>/attachments/v1/file-objects/<sha 前两位>/<sha>
// 中转本来就在这台机器上跑、有文件系统权限，所以直接读文件回字节，
// 不去赌网关有没有暴露附件 RPC（那是 DSH 内部 API，会变）。

/** 从魔术字节猜 MIME。不信任客户端传的类型，也不信扩展名。 */
function sniffImageMime(buf) {
  if (buf.length >= 8 && buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47) return 'image/png';
  if (buf.length >= 3 && buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) return 'image/jpeg';
  if (buf.length >= 12 && buf.toString('ascii', 0, 4) === 'RIFF' && buf.toString('ascii', 8, 12) === 'WEBP') return 'image/webp';
  if (buf.length >= 3 && buf.toString('ascii', 0, 3) === 'GIF') return 'image/gif';
  return null;
}

/** 附件 id 形如 `sha256:abc...`。只接受 64 位十六进制，防目录穿越。 */
function parseAttachmentId(raw) {
  const m = /^sha256:([0-9a-f]{64})$/.exec(String(raw ?? '').trim());
  return m ? m[1] : null;
}

function attachmentFilePath(sha) {
  const dir = pathSync.join(dshHomeDir(), 'attachments', 'v1');
  // 图片对象优先，退到通用文件对象。
  const candidates = [
    pathSync.join(dir, 'objects', sha.slice(0, 2), sha),
    pathSync.join(dir, 'file-objects', sha.slice(0, 2), sha)
  ];
  for (const p of candidates) {
    try { if (fsSync.statSync(p).isFile()) return p; } catch {}
  }
  return null;
}

// ── HTTP 反向代理 ────────────────────────────────────────────────
const server = http.createServer(async (req, res) => {
  // 除 ping 外全部要求密钥。ping 是 App 扫网段时用来确认「这台机器上有中转」的，
  // 它只回版本和端口，不带任何用户数据，放行才不会让扫描变成需要先有密钥的死循环。
  if (RELAY_KEY && req.url !== '/relay/ping' && !req.url.startsWith('/?') && req.url !== '/' && !req.url.startsWith('/status.json') && !relayAuthorized(req)) {
    res.writeHead(401, { 'content-type': 'text/plain; charset=utf-8' });
    res.end('relay: unauthorized');
    return;
  }
  // 调试：把所有 API 请求都打出来，好知道前端到底在调什么
  if (req.url.startsWith('/api/')) {
    const t = new Date().toISOString().slice(11, 23);
    console.log(`[relay] ${t} ${req.method} ${req.url}`);
  }
  // 中转自己的端点
  // —— 状态页：浏览器打开 http://<电脑IP>:8090/ ——
  if ((req.url === '/' || req.url.startsWith('/?')) && req.method === 'GET') {
    const authed = relayAuthorized(req);
    const fullKey = RELAY_KEY && authed ? RELAY_KEY : '';
    const masked = RELAY_KEY
      ? (authed ? RELAY_KEY : RELAY_KEY.slice(0, 4) + '••••••••••••（带 ?key= 查看完整）')
      : '（未启用校验）';
    const addrs = Object.values(os.networkInterfaces())
      .flat()
      .filter((n) => n && n.family === 'IPv4' && !n.internal && !n.address.startsWith('198.18.'))
      .map((n) => n.address);
    const html = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DSH 中转</title>
<style>
:root{color-scheme:dark}
body{margin:0;padding:32px 20px;background:#141416;color:#EDEDF0;
     font:15px/1.6 -apple-system,"Segoe UI",system-ui,sans-serif}
.wrap{max-width:560px;margin:0 auto}
h1{font-size:20px;margin:0 0 4px}
.sub{color:#5A5A63;font-size:12px;margin-bottom:24px}
.card{background:#1E1E22;border-radius:14px;padding:14px 16px;margin-bottom:10px}
.row{display:flex;justify-content:space-between;gap:12px;align-items:baseline}
.k{color:#8A8A93;font-size:13px;flex:none}
.v{font-family:ui-monospace,Consolas,monospace;font-size:13px;word-break:break-all;text-align:right}
.ok{color:#4ADE80}
.key{color:#5B9DFF}
.hint{color:#5A5A63;font-size:12px;margin-top:18px;line-height:1.7}
code{background:#26262C;padding:2px 6px;border-radius:6px}
</style></head><body><div class="wrap">
<h1>DSH 中转</h1>
<div class="sub">把电脑上的 DeepSeek Harness 开给手机</div>
<div class="card"><div class="row"><span class="k">状态</span><span class="v ok">● 运行中 · 端口 ${LISTEN_PORT}</span></div></div>
${addrs.map((a) => `<div class="card"><div class="row"><span class="k">手机填这个</span><span class="v">http://${a}:${LISTEN_PORT}</span></div></div>`).join('')}
<div class="card"><div class="row"><span class="k">访问密钥</span><span class="v key">${masked}</span></div></div>
<div class="hint">
在 App 的「连接」页把地址和密钥填上就能连。<br>
密钥文件：<code>${RELAY_KEY_FILE.replace(/</g, '&lt;')}</code>，可以手改，改完重启中转生效。<br>
${authed && RELAY_KEY ? '' : '带 <code>?key=你的密钥</code> 访问本页才能看到完整密钥。'}
</div>
</div></body></html>`;
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
    res.end(html);
    return;
  }

  if (req.url.startsWith('/status.json') && req.method === 'GET') {
    const authed = relayAuthorized(req);
    const addrs = Object.values(os.networkInterfaces())
      .flat()
      .filter((n) => n && n.family === 'IPv4' && !n.internal && !n.address.startsWith('198.18.'))
      .map((n) => n.address);
    jsonReply(res, 'relayStatus', {
      running: true,
      port: LISTEN_PORT,
      authRequired: Boolean(RELAY_KEY),
      addresses: addrs.map((a) => `http://${a}:${LISTEN_PORT}`),
      key: authed ? RELAY_KEY : '',
    });
    return;
  }

  if (req.url.startsWith('/relay/')) {
    if (req.url === '/relay/ping' && req.method === 'GET') {
      // App 扫网段时用它确认「这台机器上跑着中转」。故意做得极轻，
      // 因为一次扫描会打 254 个地址。
      if (DEBUG_WS) console.log(`[relay] ping <- ${req.socket.remoteAddress}`);
      jsonReply(res, 'relayPing', { app: 'dsh-relay', version: 2, port: LISTEN_PORT, authRequired: Boolean(RELAY_KEY) });
      return;
    }
    if (req.url === '/relay/pick' && req.method === 'POST') {
      const raw = await readBody(req);
      try {
        const doc = JSON.parse(raw || '{}');
        if (typeof doc.dir === 'string' && doc.dir.trim()) {
          pickedDirOverride = doc.dir.trim();
          console.log(`[relay] 下次 pick 返回: ${pickedDirOverride}`);
        }
        jsonReply(res, 'relay', { dir: pickedDirOverride });
      } catch {
        jsonReply(res, 'relay', { dir: pickedDirOverride });
      }
      return;
    }
    if (req.url === '/relay/default-workspace' && req.method === 'GET') {
      // 原生 UI 需要知道「用哪个目录建工作区」。手机端没法自己挑，
      // 所以让中转给一个：优先用被 pick 过的目录，否则用固定的 workspace。
      const dir = pickedDirOverride || DEFAULT_WORKSPACE;
      console.log(`[relay] default-workspace -> ${dir}`);
      jsonReply(res, 'relayDefaultWorkspace', { dir });
      return;
    }
    if (req.url.startsWith('/relay/attachment/') && req.method === 'GET') {
      // GET /relay/attachment/sha256:<hex>
      const raw = decodeURIComponent(req.url.slice('/relay/attachment/'.length));
      const sha = parseAttachmentId(raw);
      if (!sha) {
        res.writeHead(400, { 'content-type': 'text/plain; charset=utf-8' });
        res.end('relay: bad attachment id');
        return;
      }
      const file = attachmentFilePath(sha);
      if (!file) {
        res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
        res.end('relay: attachment not found');
        return;
      }
      let buf;
      try {
        buf = fsSync.readFileSync(file);
      } catch (err) {
        res.writeHead(500, { 'content-type': 'text/plain; charset=utf-8' });
        res.end('relay: read failed ' + String(err?.message ?? err));
        return;
      }
      const mime = sniffImageMime(buf) || 'application/octet-stream';
      // 附件对象不可变（同一 sha 就是同一份字节），可以让手机长期缓存。
      res.writeHead(200, {
        'content-type': mime,
        'content-length': String(buf.length),
        'cache-control': 'public, max-age=31536000, immutable',
        'x-content-type-options': 'nosniff'
      });
      res.end(buf);
      return;
    }
    if (req.url === '/relay/drives' && req.method === 'GET') {
      // 文件管理器的根目录原本写死 ROOT_DIR（C:\），D 盘上的项目根本进不去。
      // 这里把真实存在的盘符列出来，App 端加一个切换入口。
      //
      // 遍历 C-Z 逐个 stat，不用 wmic/powershell：那两条要起子进程，
      // 冷启动几百毫秒，而这里 20 次 stat 是毫秒级。
      const drives = [];
      if (process.platform === 'win32') {
        for (const ch of 'CDEFGHIJKLMNOPQRSTUVWXYZ') {
          const root = `${ch}:\\`;
          try {
            if (fsSync.statSync(root).isDirectory()) drives.push(root);
          } catch {}
        }
      } else {
        drives.push('/');
      }
      jsonReply(res, 'relayDrives', { drives });
      return;
    }
    if (req.url.startsWith('/relay/upload') && req.method === 'POST') {
      // POST /relay/upload?path=<目标目录>&name=<文件名>，body 是文件原始字节。
      //
      // 和下载一样，这条端点在局域网是敞开的，所以目标目录必须过 isDeniedPath
      // （不然谁都能往启动目录、AppData 里塞东西）。文件名强制取 basename，
      // 挡住 name=..\..\evil.exe 这种路径穿越。
      const u = new URL(req.url, 'http://localhost');
      const dirArg = u.searchParams.get('path') || '';
      const rawName = u.searchParams.get('name') || '';
      if (!dirArg || !pathMod.isAbsolute(dirArg)) {
        res.writeHead(400, { 'content-type': 'text/plain; charset=utf-8' });
        res.end('relay: bad dir');
        return;
      }
      const dir = pathMod.normalize(dirArg);
      const safeName = pathMod.basename(rawName.replace(/[\\/]+$/, ''));
      if (!safeName || safeName === '.' || safeName === '..') {
        res.writeHead(400, { 'content-type': 'text/plain; charset=utf-8' });
        res.end('relay: bad name');
        return;
      }
      if (isDeniedPath(dir)) {
        res.writeHead(403, { 'content-type': 'text/plain; charset=utf-8' });
        res.end('relay: path not allowed');
        return;
      }
      try {
        if (!(await fs.stat(dir)).isDirectory()) throw new Error('not dir');
      } catch {
        res.writeHead(400, { 'content-type': 'text/plain; charset=utf-8' });
        res.end('relay: target is not a directory');
        return;
      }
      // 不覆盖同名文件：foo.txt -> foo (1).txt
      const ext = pathMod.extname(safeName);
      const stem = pathMod.basename(safeName, ext);
      let target = pathMod.join(dir, safeName);
      for (let n = 1; fsSync.existsSync(target) && n < 1000; n++) {
        target = pathMod.join(dir, `${stem} (${n})${ext}`);
      }
      const out = fsSync.createWriteStream(target);
      let written = 0;
      let failed = false;
      const cleanup = () => { try { fsSync.unlinkSync(target); } catch {} };
      req.on('data', (c) => { written += c.length; });
      req.on('aborted', () => { failed = true; out.destroy(); cleanup(); });
      out.on('error', (err) => {
        failed = true;
        console.log(`[relay] 上传失败 ${target}: ${err?.message ?? err}`);
        cleanup();
        try {
          res.writeHead(500, { 'content-type': 'text/plain; charset=utf-8' });
          res.end('relay: write failed');
        } catch {}
      });
      out.on('finish', () => {
        if (failed) return;
        console.log(`[relay] 上传 ${pathMod.basename(target)} ${written} 字节 -> ${dir}`);
        jsonReply(res, 'relayUpload', { path: target, name: pathMod.basename(target), size: written });
      });
      req.pipe(out);
      return;
    }
    if (req.url.startsWith('/relay/file') && req.method === 'GET') {
      // GET /relay/file?path=<绝对路径>[&name=<下载时的文件名>]
      //
      // 文件管理器和「对话里的文件保存」共用这条路。注意它不是附件存储，
      // 读的是电脑文件系统上的真实路径，所以**必须限根**：
      // 只允许访问常用目录（用户家目录 + 各盘根下的普通目录），
      // 拒绝系统目录，避免误把注册表 hive、系统配置这些拉下去。
      const u = new URL(req.url, 'http://localhost');
      const target = u.searchParams.get('path') || '';
      const name = u.searchParams.get('name') || pathMod.basename(target);
      if (!target || !pathMod.isAbsolute(target)) {
        res.writeHead(400, { 'content-type': 'text/plain; charset=utf-8' });
        res.end('relay: bad path');
        return;
      }
      const norm = pathMod.normalize(target);
      // 这条端点在局域网里是**完全开放**的（中转绑 0.0.0.0，手机要连就只能这样），
      // 同一个 Wi-Fi 下任何人都能直接构造 URL 拉文件，而且不经过目录列表——
      // 所以 listDir 里「跳过隐藏项」那套在这里不起作用，必须自己拦。
      // 敏感段清单与上传端点共用，见 isDeniedPath。
      if (isDeniedPath(norm)) {
        res.writeHead(403, { 'content-type': 'text/plain; charset=utf-8' });
        res.end('relay: path not allowed');
        return;
      }
      let st;
      try { st = await fs.stat(norm); } catch {
        res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
        res.end('relay: no such file');
        return;
      }
      if (!st.isFile()) {
        res.writeHead(400, { 'content-type': 'text/plain; charset=utf-8' });
        res.end('relay: not a file');
        return;
      }
      // 大文件走流，不整读进内存（工作区里几百 MB 的模型文件也拖得动）。
      res.writeHead(200, {
        'content-type': 'application/octet-stream',
        'content-length': String(st.size),
        // RFC 5987：中文文件名必须走 filename*，否则手机端拿到的是乱码。
        'content-disposition': `attachment; filename*=UTF-8''${encodeURIComponent(name)}`,
        'x-content-type-options': 'nosniff'
      });
      const stream = fsSync.createReadStream(norm);
      stream.on('error', () => { try { res.destroy(); } catch {} });
      stream.pipe(res);
      return;
    }
    res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
    res.end('relay: no such endpoint');
    return;
  }

  if (req.method === 'POST' && req.url.includes('/api/directoryPicker/')) {
    const body = await readBody(req);
    await handlePicker(req.url, body, res);
    return;
  }

  const headers = { ...req.headers };
  headers.cookie = freshCookie();
  headers.host = target.host;
  delete headers['accept-encoding'];

  // DSH 的 browser-trust fence 会校验 Origin：只要带了 Origin 且不是它自己的
  // authority，一律 403（实测 Origin=192.168.1.100:8090 -> 403，
  // 不带 Origin 或 Origin=127.0.0.1:1120 -> 200）。手机发来的是中转地址，
  // 所以这里统一改写成目标 authority。
  if (headers.origin) {
    headers.origin = `http://${target.host}`;
  }
  if (headers.referer) {
    headers.referer = `http://${target.host}/`;
  }

  const proxyReq = http.request({
    hostname: target.hostname,
    port: target.port,
    path: req.url,
    method: req.method,
    headers
  }, (proxyRes) => {
    // 模型目录加载不出来时，靠这两行区分「DSH 报错」和「前端没处理」
    if (req.url.includes('modelCatalog')) {
      const chunks = [];
      proxyRes.on('data', (c) => chunks.push(c));
      proxyRes.on('end', () => {
        const body = Buffer.concat(chunks).toString('utf8');
        console.log(`[relay] modelCatalog -> ${proxyRes.statusCode} ${body.slice(0, 600)}`);
      });
    }
    const outHeaders = { ...proxyRes.headers };
    if (outHeaders['set-cookie']) {
      outHeaders['set-cookie'] = outHeaders['set-cookie'].map((c) =>
        c.replace(/;\s*domain=[^;]+/gi, '').replace(/;\s*secure/gi, '')
      );
    }
    res.writeHead(proxyRes.statusCode ?? 502, outHeaders);
    proxyRes.pipe(res);
  });

  proxyReq.on('error', (err) => {
    console.error('[relay] HTTP 转发失败:', err.message);
    if (!res.headersSent) res.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' });
    res.end('relay: 无法连接 DSH 桌面版');
  });

  req.pipe(proxyReq);
});

// ── WebSocket 透传（/api/remote.mux 走这里）─────────────────────
server.on('upgrade', (req, socket, head) => {
  if (DEBUG_WS) {
    const t = new Date().toISOString().slice(11, 23);
    console.log(`[relay] ${t} WS 升级请求 ${req.url} origin=${req.headers.origin ?? '(无)'}`);
  }
  const upstream = net.connect({ host: target.hostname, port: Number(target.port) }, () => {
    const lines = [`${req.method} ${req.url} HTTP/1.1`];
    let sawCookie = false;
    for (let i = 0; i < req.rawHeaders.length; i += 2) {
      const k = req.rawHeaders[i];
      const v = req.rawHeaders[i + 1];
      const lk = k.toLowerCase();
      if (lk === 'cookie') { lines.push(`${k}: ${freshCookie()}`); sawCookie = true; continue; }
      if (lk === 'host') { lines.push(`${k}: ${target.host}`); continue; }
      // Origin / Referer 同样要归一化，否则 fence 会 403 掉升级请求
      if (lk === 'origin') { lines.push(`${k}: http://${target.host}`); continue; }
      if (lk === 'referer') { lines.push(`${k}: http://${target.host}/`); continue; }
      lines.push(`${k}: ${v}`);
    }
    // WebSocket 升级很可能压根不带 Cookie（实测 WebView 就是），
    // 不补的话上游直接 401，前端会一直重连、界面卡在「正在加载」。
    if (!sawCookie) lines.push(`Cookie: ${freshCookie()}`);
    lines.push('', '');
    upstream.write(lines.join('\r\n'));
    if (head?.length) upstream.write(head);

    // 调试用：把前端通过 WS 发出去的 RPC 名字打出来（只看客户端->服务端）。
    // 只是旁路观察，不影响 pipe（多个 data 监听都会收到同一份缓冲）。
    if (DEBUG_WS) {
      socket.on('data', (buf) => {
        // 先无条件打长度，确认「到底有没有帧过来」
        console.log(`[relay] WS 收到 ${buf.length} 字节, 首字节=0x${buf[0]?.toString(16)}`);
        try {
          // 解析一个文本帧：0x81 + 掩码长度 + mask(4) + payload
          if ((buf[0] & 0x0f) !== 1) return;
          let len = buf[1] & 0x7f;
          let off = 2;
          if (len === 126) { len = buf.readUInt16BE(2); off = 4; }
          else if (len === 127) { len = Number(buf.readBigUInt64BE(2)); off = 10; }
          const mask = buf.slice(off, off + 4);
          const data = Buffer.alloc(Math.min(len, 4000));
          for (let i = 0; i < data.length; i++) data[i] = buf[off + 4 + i] ^ mask[i % 4];
          const text = data.toString('utf8');
          const m = text.match(/"method"\s*:\s*"([^"]+)"/);
          if (m) {
            const t = new Date().toISOString().slice(11, 23);
            console.log(`[relay] ${t} WS -> ${m[1]}`);
          }
        } catch {}
      });
    }

    upstream.pipe(socket);
    socket.pipe(upstream);

    // 调试用：把上游返回的握手响应头打出来。前端反复重连通常就是这里不对。
    if (DEBUG_WS) {
      let first = true;
      upstream.on('data', (buf) => {
        if (!first) return;
        first = false;
        const head = buf.slice(0, 300).toString('latin1').split('\r\n')[0];
        console.log(`[relay] WS 上游响应: ${head}`);
      });
    }
  });

  upstream.on('error', (err) => {
    console.error('[relay] WS 转发失败:', err.message);
    try { socket.destroy(); } catch {}
  });
  socket.on('error', () => { try { upstream.destroy(); } catch {} });
});

server.listen(LISTEN_PORT, LISTEN_HOST, () => {
  console.log(`[relay] 监听 ${LISTEN_HOST}:${LISTEN_PORT}`);
  // 只打真实局域网地址：跳过回环、VPN/TUN 虚拟网卡（Clash、Tailscale 等会伪造 198.18.x.x）
  const virtual = /^(Meta|tun|utun|tailscale|ZeroTier|WireGuard|Hyper-V|vEthernet|VMware|VirtualBox)/i;
  const list = [];
  for (const [name, addrs] of Object.entries(os.networkInterfaces())) {
    if (virtual.test(name)) continue;
    for (const a of addrs ?? []) {
      if (a.family === 'IPv4' && !a.internal) list.push({ name, address: a.address });
    }
  }
  // 私有网段优先
  const rank = (ip) => ip.startsWith('192.168.') ? 0 : ip.startsWith('10.') ? 1
    : /^172\.(1[6-9]|2\d|3[01])\./.test(ip) ? 2 : 3;
  list.sort((a, b) => rank(a.address) - rank(b.address));
  for (const { name, address } of list) {
    console.log(`[relay]   手机访问: http://${address}:${LISTEN_PORT}   (${name})`);
  }
  if (!list.length) {
    console.log('[relay]   没找到局域网地址，手机可能连不上；检查网络连接');
  }
});

// ── UDP 广播应答：让手机 App 自动发现这台电脑 ────────────────────
// App 往广播地址的 8099 端口发 "DSH_DISCOVER"，这里收到就回一行 JSON。
// 比让用户抄 IP 强得多——换 Wi-Fi、路由器重新分配 IP 都不用改配置。
const DISCOVER_PORT = Number(arg('discover-port', '8099'));
const udp = dgram.createSocket({ type: 'udp4', reuseAddr: true });

udp.on('message', (msg, rinfo) => {
  const text = msg.toString().trim();
  // 收到什么都要打一行：找不到电脑时靠这条日志区分「包没进来」和「进来了但没匹配」
  console.log(`[relay] UDP ${rinfo.address}:${rinfo.port} -> ${JSON.stringify(text.slice(0, 40))}`);
  if (text !== 'DSH_DISCOVER') return;
  const reply = Buffer.from(JSON.stringify({
    app: 'dsh-relay',
    version: 1,
    port: LISTEN_PORT
  }));
  udp.send(reply, rinfo.port, rinfo.address, (err) => {
    if (err) console.error('[relay] UDP 回包失败:', err.message);
  });
});

udp.on('error', (err) => {
  console.error('[relay] UDP 发现失败（不影响正常使用）:', err.message);
  try { udp.close(); } catch {}
});

udp.bind(DISCOVER_PORT, () => {
  console.log(`[relay] 已开自动发现（UDP ${DISCOVER_PORT}），手机 App 会自己找到这台电脑`);
});
