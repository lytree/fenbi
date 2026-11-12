/**
 * 连接信息：从 App 里复制的那串地址就是全部配置。
 *
 *   http://192.168.1.23:8777/?t=k3m9x2q7ab4c
 *   └──────── baseUrl ────────┘ └─ token ─┘
 *
 * token 是服务每次启动新生成的，所以这里不缓存、不落盘——换一次服务就换一次配置。
 */
export interface QbankConfig {
  baseUrl: string
  token: string
}

export class ConfigError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ConfigError'
  }
}

/** 把 App 里那串地址拆成 baseUrl + token */
export function parseQbankUrl(raw: string): QbankConfig {
  const text = raw.trim()
  if (!text) throw new ConfigError('没有配置地址：把 App 里「局域网编辑器」显示的完整链接填进来')

  let url: URL
  try {
    url = new URL(text)
  } catch {
    throw new ConfigError(`地址不合法：「${text}」应该形如 http://192.168.1.23:8777/?t=口令`)
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new ConfigError(`只支持 http/https 地址，收到的是 ${url.protocol}`)
  }

  const token = url.searchParams.get('t')?.trim() ?? ''
  if (!token) {
    throw new ConfigError(
      '地址里没有口令（?t=…）。回手机上点「复制地址」重新复制一次——服务重启后口令会变，旧链接会失效。'
    )
  }

  url.search = ''
  url.hash = ''
  const baseUrl = url.toString().replace(/\/+$/, '')
  return { baseUrl, token }
}

/**
 * 依次找：
 *   1. 命令行 `--url http://…/?t=…`
 *   2. 环境变量 `QBANK_URL`
 *   3. 环境变量 `QBANK_HOST` + `QBANK_TOKEN`
 */
export function configFromEnv(
  env: NodeJS.ProcessEnv = process.env,
  argv: string[] = process.argv.slice(2)
): QbankConfig {
  const flagIndex = argv.findIndex((a) => a === '--url' || a === '-u')
  if (flagIndex >= 0) {
    const value = argv[flagIndex + 1]
    if (!value) throw new ConfigError('--url 后面要跟完整地址（带 ?t=口令）')
    return parseQbankUrl(value)
  }

  const fromEnv = env.QBANK_URL?.trim()
  if (fromEnv) return parseQbankUrl(fromEnv)

  const host = env.QBANK_HOST?.trim()
  const token = env.QBANK_TOKEN?.trim()
  if (host && token) {
    const base = host.includes('://') ? host : `http://${host}`
    return parseQbankUrl(`${base.replace(/\/+$/, '')}/?t=${encodeURIComponent(token)}`)
  }

  throw new ConfigError(
    '还没配置手机地址。设置环境变量 QBANK_URL（App 里「局域网编辑器」页面上的完整链接，含 ?t=口令），' +
      '或在启动时传 --url http://192.168.x.x:8777/?t=…'
  )
}
