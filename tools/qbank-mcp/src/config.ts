/**
 * 连接信息：手机地址 + 写入口令。
 *
 *   QBANK_URL = http://192.168.1.23:8777
 *   QBANK_KEY = k3m9x2q7ab4c          ← 只走请求头 X-Lan-Key，不进 URL
 *
 * 网页地址不再带口令，所以这里也不用再拆 `?t=`。老配置里如果还带着 `?t=xxx`，
 * 会被当成 key 继续用——升级一次不用改配置。
 *
 * 没配 key 也能连：读接口照样通，只是发布会变成「等手机上点确认」。
 * key 每次手机重启服务都会变，所以不缓存、不落盘。
 */
export interface QbankConfig {
  baseUrl: string
  /** 写入口令（请求头 X-Lan-Key）；空字符串 = 网页同级权限，发布要手机确认 */
  key: string
}

export class ConfigError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ConfigError'
  }
}

/**
 * 报错文案里不能出现完整口令。
 *
 * 这段字会进模型上下文，也会被用户整段贴到 issue / 聊天里；地址漏写 `http://`
 * 是最常见的手误，而报错信息恰好会把整串（含 `t=`）原样吐出来。
 */
export function redact(text: string): string {
  return text.replace(/([?&]\s*t=)[^&\s"'）)]*/gi, '$1***')
}

function normalizeBase(url: URL): string {
  url.search = ''
  url.hash = ''
  return url.toString().replace(/\/+$/, '')
}

/** 把 App 里复制的地址拆成 baseUrl（兼容老链接里的 `?t=口令`） */
export function parseQbankUrl(raw: string, key = ''): QbankConfig {
  const text = raw.trim()
  if (!text) throw new ConfigError('没有配置地址：填 http://手机IP:8777（App「局域网编辑器」页面上显示的那个）')

  let url: URL
  try {
    url = new URL(text)
  } catch {
    throw new ConfigError(
      `地址不合法：「${redact(text)}」应该形如 http://192.168.1.23:8777`
    )
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new ConfigError(`只支持 http/https 地址，收到的是 ${url.protocol}`)
  }

  // 老配置里那串 `?t=` 继续当 key 用，不用为了升级重配一遍
  const legacyKey = url.searchParams.get('t')?.trim() ?? ''
  return { baseUrl: normalizeBase(url), key: (key.trim() || legacyKey) }
}

function argValue(argv: string[], names: string[]): string | undefined {
  const i = argv.findIndex((a) => names.includes(a))
  if (i < 0) return undefined
  const value = argv[i + 1]
  if (!value || value.startsWith('-')) throw new ConfigError(`${names[0]} 后面要跟一个值`)
  return value
}

/**
 * 依次找：
 *   1. 命令行 `--url http://…` + `--key …`
 *   2. 环境变量 `QBANK_URL` + `QBANK_KEY`
 *   3. 环境变量 `QBANK_HOST` / `QBANK_TOKEN`（老写法）
 */
export function configFromEnv(
  env: NodeJS.ProcessEnv = process.env,
  argv: string[] = process.argv.slice(2)
): QbankConfig {
  const key = argValue(argv, ['--key', '-k']) ?? env.QBANK_KEY?.trim() ?? ''

  const flagUrl = argValue(argv, ['--url', '-u'])
  if (flagUrl) return parseQbankUrl(flagUrl, key)

  const fromEnv = env.QBANK_URL?.trim()
  if (fromEnv) return parseQbankUrl(fromEnv, key)

  const host = env.QBANK_HOST?.trim()
  if (host) {
    const base = host.includes('://') ? host : `http://${host}`
    return parseQbankUrl(base, key || env.QBANK_TOKEN?.trim() || '')
  }

  throw new ConfigError(
    '还没配置手机地址。设置环境变量 QBANK_URL（App「局域网编辑器」页面上的地址，形如 http://192.168.1.23:8777），' +
      '或在启动时传 --url http://192.168.x.x:8777；写题还要配 QBANK_KEY（同一页面的「AI 接入」里有现成配置）。'
  )
}
