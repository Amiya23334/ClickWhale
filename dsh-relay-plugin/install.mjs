#!/usr/bin/env node
/**
 * 一键安装：把本插件装进 DSH 的某个 profile。
 *
 * 为什么不能只跑 `dsh plugin add`：那条命令只是把参数转发给 profile 里的 pnpm，
 * 也就是只加一条 dependency。DSH 决定「加载哪些 bundle」看的是
 * package.json 的 `dsh.profile.bundles`，不在那个数组里的包装了也不会加载。
 * 所以这里做两步：写依赖 + 写 bundles，最后再让 pnpm 建链接。
 *
 * 输出一律英文：这个脚本是在 cmd 窗口里跑的，cmd 默认 GBK，中文会乱码。
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const PLUGIN_DIR = path.dirname(fileURLToPath(import.meta.url))
const pkgFile = path.join(PLUGIN_DIR, 'package.json')
if (!fs.existsSync(pkgFile)) {
  console.error(`[install] package.json not found next to this script: ${pkgFile}`)
  process.exit(1)
}
const pkg = JSON.parse(fs.readFileSync(pkgFile, 'utf8'))
const NAME = pkg.name

const profile = process.argv[2] || process.env.DSH_PROFILE || 'desktop'
const dshHome = process.env.DSH_HOME || path.join(os.homedir(), '.dsh')
const profileDir = path.join(dshHome, 'profiles', profile)

if (!fs.existsSync(profileDir)) {
  console.error(`[install] profile directory not found: ${profileDir}`)
  console.error('[install] Start DSH once so the profile gets created,')
  console.error(`[install] or pass the profile name: install.cmd <name>`)
  process.exit(1)
}

const profilePkgFile = path.join(profileDir, 'package.json')
const profilePkg = JSON.parse(fs.readFileSync(profilePkgFile, 'utf8'))

profilePkg.dependencies = profilePkg.dependencies || {}
profilePkg.dependencies[NAME] = `link:${PLUGIN_DIR}`

profilePkg.dsh = profilePkg.dsh || {}
profilePkg.dsh.profile = profilePkg.dsh.profile || {}
const bundles = Array.isArray(profilePkg.dsh.profile.bundles)
  ? profilePkg.dsh.profile.bundles
  : []
if (!bundles.includes(NAME)) bundles.push(NAME)
profilePkg.dsh.profile.bundles = bundles

fs.writeFileSync(profilePkgFile, JSON.stringify(profilePkg, null, 2) + '\n', 'utf8')
console.log(`[install] profile: ${profileDir}`)
console.log(`[install] dependency ${NAME} -> link:${PLUGIN_DIR}`)
console.log(`[install] bundles now has ${bundles.length} entries`)

// Windows 上 dsh 是 .cmd，得经 cmd.exe 转一次。这里显式调 cmd.exe 而不是
// spawn 的 shell: true —— 后者会把参数拼成命令行，node 会报 DEP0190。
const pluginArgs = ['plugin', '--profile', profile, 'add', `link:${PLUGIN_DIR}`]
const res =
  process.platform === 'win32'
    ? spawnSync('cmd.exe', ['/c', 'dsh', ...pluginArgs], { stdio: 'inherit' })
    : spawnSync('dsh', pluginArgs, { stdio: 'inherit' })

if (res.error || res.status !== 0) {
  console.error('')
  console.error('[install] pnpm step failed. You can run it manually:')
  console.error(`  cd "${profileDir}" && pnpm install`)
  process.exit(res.status || 1)
}

console.log('')
console.log('[install] Done. Restart DSH, then open the Plugins page.')
