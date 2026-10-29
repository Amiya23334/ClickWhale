/**
 * Config 描述器。
 *
 * 抄自 dsh-ops/lib/settings/schema.js：DSH 的设置界面认的是 Standard Schema
 * 协议 + `type`/`meta` 描述树，不是裸 schemastery。这里保留同样的形状，
 * 只把 vendor 改名，免得跟 dsh-ops 的校验结果混在一起。
 */
function descriptor(field) {
  return {
    type: field.type,
    meta: { ...field.meta },
    ...(field.inner ? { inner: descriptor(field.inner) } : {}),
    ...(field.dict
      ? {
          dict: Object.fromEntries(
            Object.entries(field.dict).map(([key, value]) => [key, descriptor(value)])
          ),
        }
      : {}),
    // 宿主校验前会剥掉 volatile 元数据；返回独立副本，避免它改坏后续描述
    toJSON() {
      return JSON.parse(JSON.stringify({ ...this, toJSON: undefined }))
    },
  }
}

export const editable = (type, description, inner) => ({
  type,
  meta: { volatile: true, description },
  ...(inner ? { inner } : {}),
})

export function settingsConfig(normalize, fields) {
  return Object.assign(descriptor({ type: 'object', dict: fields }), {
    '~standard': {
      version: 1,
      vendor: 'dsh-relay-plugin',
      validate(value) {
        try {
          return { value: normalize(value) }
        } catch (error) {
          return { issues: [{ message: error instanceof Error ? error.message : String(error) }] }
        }
      },
    },
  })
}

export function configObject(value) {
  if (value === undefined) return {}
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('config must be an object')
  }
  return value
}
