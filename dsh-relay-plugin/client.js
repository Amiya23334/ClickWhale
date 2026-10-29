// 浏览器半边：插件详情页的「手机中转配置」表单。
//
// 格式是 DSH 的 lazy module 约定（window.__ModuleLoader__.load + factory），
// id 必须等于包名。React 从浏览器模块表里 require，不要自带一份。
//
// 数据通路：host 半边注册了 /plugins/dsh-relay-plugin/{state,config,restart}，
// 这个组件只负责渲染和调用它们。
window.__ModuleLoader__.load({
  id: 'dsh-relay-plugin',
  factory(require) {
    const React = require('react')
    const h = React.createElement

    const base = '/plugins/dsh-relay-plugin'
    const label = { color: 'var(--dsw-alias-label-tertiary)', fontSize: 13, flex: 'none' }
    const input = {
      flex: 1,
      minWidth: 0,
      font: 'inherit',
      fontSize: 13,
      padding: '5px 8px',
      borderRadius: 6,
      border: '1px solid var(--dsw-alias-border-l3, #d0d0d6)',
      background: 'var(--dsw-alias-bg-layer-1, #fff)',
      color: 'var(--dsw-alias-label-primary)',
    }

    function Row({ name, children, hint }) {
      return h(
        'div',
        { style: { display: 'flex', flexDirection: 'column', gap: 4, padding: '4px 0' } },
        h(
          'div',
          { style: { display: 'flex', alignItems: 'center', gap: 12 } },
          h('span', { style: { ...label, minWidth: 96 } }, name),
          children
        ),
        hint
          ? h('div', { style: { fontSize: 12, color: 'var(--dsw-alias-label-tertiary)', paddingLeft: 108 } }, hint)
          : null
      )
    }

    const fmt = (ts) => {
      if (!ts) return '—'
      const d = new Date(ts)
      return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`
    }

    function RelayConfigPanel() {
      const [state, setState] = React.useState(null)
      const [form, setForm] = React.useState(null)
      const [busy, setBusy] = React.useState('')
      const [msg, setMsg] = React.useState('')

      const load = React.useCallback(async () => {
        try {
          const res = await fetch(`${base}/state`, { cache: 'no-store' })
          const s = await res.json()
          setState(s)
          setForm({
            relayPath: s.relayPath,
            host: s.host,
            port: s.port,
            autoRestart: s.autoRestart,
            restartDelayMs: s.restartDelayMs,
          })
          setMsg('')
        } catch (err) {
          setMsg('读取失败：' + (err && err.message))
        }
      }, [])

      React.useEffect(() => {
        load()
      }, [load])

      const set = (key) => (event) => {
        const value =
          event.target.type === 'checkbox' ? event.target.checked : event.target.value
        setForm((prev) => ({ ...prev, [key]: value }))
      }

      const save = async () => {
        if (!form) return
        setBusy('save')
        setMsg('')
        try {
          const res = await fetch(`${base}/config`, {
            method: 'POST',
            headers: { 'content-type': 'application/json' },
            body: JSON.stringify({
              relayPath: form.relayPath,
              host: form.host,
              port: Number(form.port) || 0,
              autoRestart: Boolean(form.autoRestart),
              restartDelayMs: Number(form.restartDelayMs) || 5000,
            }),
          })
          const body = await res.json()
          if (!res.ok) throw new Error(body.error || `HTTP ${res.status}`)
          setMsg('已保存，中转正在重启')
          setTimeout(load, 1200)
        } catch (err) {
          setMsg('保存失败：' + (err && err.message))
        } finally {
          setBusy('')
        }
      }

      const restart = async () => {
        setBusy('restart')
        setMsg('')
        try {
          await fetch(`${base}/restart`, { method: 'POST' })
          setMsg('已请求重启')
          setTimeout(load, 1200)
        } catch (err) {
          setMsg('重启失败：' + (err && err.message))
        } finally {
          setBusy('')
        }
      }

      if (!form) {
        return h('div', { style: { fontSize: 13, padding: '8px 0' } }, msg || '正在读取配置…')
      }

      const dot = state && state.running ? '#2f9e44' : '#c92a2a'

      return h(
        'div',
        { style: { display: 'flex', flexDirection: 'column', gap: 2, paddingTop: 4 } },
        h(
          'div',
          { style: { fontSize: 15, fontWeight: 600, marginBottom: 6, display: 'flex', alignItems: 'center', gap: 8 } },
          '手机中转配置',
          h('span', { style: { width: 8, height: 8, borderRadius: 4, background: dot } }),
          h(
            'span',
            { style: { fontSize: 12, fontWeight: 400, color: 'var(--dsw-alias-label-tertiary)' } },
            state && state.running
              ? `运行中 · PID ${state.pid} · 起于 ${fmt(state.startedAt)}`
              : state && state.lastExit
                ? `已停止（上次退出 code=${state.lastExit.code}）`
                : '已停止'
          )
        ),

        h(
          Row,
          { name: '中转脚本', hint: 'dsh-relay.mjs 的绝对路径' },
          h('input', { style: input, value: form.relayPath, onChange: set('relayPath') })
        ),
        h(
          Row,
          { name: '监听地址', hint: '0.0.0.0 表示局域网可访问；只想本机用可填 127.0.0.1' },
          h('input', { style: input, value: form.host, onChange: set('host') })
        ),
        h(
          Row,
          { name: '端口', hint: '0 = 用中转默认的 8090' },
          h('input', {
            style: { ...input, maxWidth: 120 },
            type: 'number',
            value: form.port,
            onChange: set('port'),
          })
        ),
        h(
          Row,
          { name: '自动重启' },
          h('input', {
            type: 'checkbox',
            checked: Boolean(form.autoRestart),
            onChange: set('autoRestart'),
            style: { width: 16, height: 16 },
          }),
          h(
            'span',
            { style: { fontSize: 12, color: 'var(--dsw-alias-label-tertiary)' } },
            `挂了等 ${Math.round(Number(form.restartDelayMs) / 1000) || 5} 秒后再拉`
          )
        ),
        h(
          Row,
          { name: '访问密钥', hint: `存在 ${state ? state.keyFile : ''}，可手改，改完重启中转生效` },
          h(
            'code',
            {
              style: {
                flex: 1,
                fontSize: 12,
                wordBreak: 'break-all',
                color: 'var(--dsw-alias-label-secondary, #555)',
              },
            },
            state && state.key ? state.key : '（还没生成）'
          )
        ),

        h(
          'div',
          { style: { display: 'flex', alignItems: 'center', gap: 10, marginTop: 10 } },
          h(
            'button',
            {
              type: 'button',
              onClick: save,
              disabled: Boolean(busy),
              style: {
                fontSize: 13,
                padding: '6px 14px',
                borderRadius: 7,
                border: '1px solid var(--dsw-alias-label-primary)',
                background: 'var(--dsw-alias-label-primary)',
                color: 'var(--dsw-alias-bg-layer-1, #fff)',
                cursor: busy ? 'default' : 'pointer',
                opacity: busy ? 0.6 : 1,
              },
            },
            busy === 'save' ? '保存中…' : '保存并重启'
          ),
          h(
            'button',
            {
              type: 'button',
              onClick: restart,
              disabled: Boolean(busy),
              style: {
                fontSize: 13,
                padding: '6px 14px',
                borderRadius: 7,
                border: '1px solid var(--dsw-alias-border-l3, #d0d0d6)',
                background: 'transparent',
                color: 'var(--dsw-alias-label-primary)',
                cursor: busy ? 'default' : 'pointer',
              },
            },
            busy === 'restart' ? '重启中…' : '只重启'
          ),
          msg
            ? h(
                'span',
                {
                  style: {
                    fontSize: 12,
                    color: msg.includes('失败') ? '#c92a2a' : 'var(--dsw-alias-label-tertiary)',
                  },
                },
                msg
              )
            : null
        )
      )
    }

    return {
      inject: ['slots'],
      apply(ctx) {
        ctx.slots.inject('plugins.bundle.config', () =>
          // keyed slot 认的是 options.key（不是 id）——报错原文：
          // keyed slot "plugins.bundle.config" requires options.key
          // 这里的 key 必须等于包名，包详情页才认为「这个包有配置」。
          ctx.slots.register(
            { name: 'plugins.bundle.config', key: 'dsh-relay-plugin', order: 5 },
            RelayConfigPanel
          )
        )
      },
    }
  },
})
