/**
 * 本地试卷包的资源服务器（只在 `pnpm dev` 下生效）。
 *
 * 编辑器平时读写手机里的题库；没有手机时，用这个中间件直接读写仓库里的
 * JSON 试卷（`qbank_out/`、`sample/`、`templates/`），并提供 `images/…` 图片，
 * 这样分组板在电脑上就能完整跑起来，不用先导入 App。
 *
 * 路由（都挂在 /local 下，仅开发服务器）：
 *   GET  /local/packages          列出所有带 questions 的 JSON 试卷包
 *   GET  /local/package?id=…      读一份试卷包（原样返回 JSON，附 id / path / dir）
 *   POST /local/save              写回同一份 JSON（保留未知顶层字段）
 *   GET  /local/img?p=…&base=…    读图片（相对路径按试卷包目录解析）
 */

import fs from 'node:fs'
import path from 'node:path'
import type { Plugin } from 'vite'

const REPO_ROOT = path.resolve(process.cwd(), '../..')
const PACKAGE_ROOTS = ['qbank_out', 'sample', 'templates'].map((r) => path.join(REPO_ROOT, r))
const MAX_DEPTH = 4
const IMAGE_EXT = /\.(png|jpe?g|webp|gif|bmp)$/i

interface PackageEntry {
  id: string
  title: string
  path: string
  questionCount: number
}

function toId(file: string): string {
  return path.relative(REPO_ROOT, file).split(path.sep).join('/')
}

function insideRepo(file: string): boolean {
  const root = path.resolve(REPO_ROOT)
  const target = path.resolve(file)
  return target === root || target.startsWith(root + path.sep)
}

function walk(dir: string, depth: number, out: string[]) {
  if (depth > MAX_DEPTH) return
  let entries: any[]
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true })
  } catch {
    return
  }
  for (const e of entries) {
    const full = path.join(dir, e.name)
    if (e.isDirectory()) {
      if (e.name === 'node_modules' || e.name.startsWith('.')) continue
      walk(full, depth + 1, out)
    } else if (e.isFile() && e.name.toLowerCase().endsWith('.json')) {
      out.push(full)
    }
  }
}

function readJson(file: string): any | null {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'))
  } catch {
    return null
  }
}

function listPackages(): PackageEntry[] {
  const files: string[] = []
  PACKAGE_ROOTS.forEach((root) => walk(root, 0, files))

  const out: PackageEntry[] = []
  for (const file of files) {
    const data = readJson(file)
    if (!data || !Array.isArray(data.questions) || !data.questions.length) continue
    out.push({
      id: toId(file),
      title: String(data.bank || data.title || path.basename(file, '.json')),
      path: toId(file),
      questionCount: data.questions.length,
    })
  }
  return out.sort((a, b) => a.path.localeCompare(b.path, 'zh'))
}

/** `id` 只认仓库内的 JSON 文件，别让它顺着 `..` 翻到别处 */
function packageFile(id: string): string | null {
  if (!id) return null
  const file = path.resolve(REPO_ROOT, id)
  if (!insideRepo(file) || !file.toLowerCase().endsWith('.json')) return null
  if (!fs.existsSync(file) || !fs.statSync(file).isFile()) return null
  return file
}

function mimeOf(file: string): string {
  const ext = path.extname(file).toLowerCase()
  if (ext === '.png') return 'image/png'
  if (ext === '.jpg' || ext === '.jpeg') return 'image/jpeg'
  if (ext === '.webp') return 'image/webp'
  if (ext === '.gif') return 'image/gif'
  if (ext === '.bmp') return 'image/bmp'
  return 'application/octet-stream'
}

/** 示例包里用 `asset://samples/x.png` 引用内置图，开发服务器按文件名在 sample 下找一遍 */
function assetCandidates(src: string): string[] {
  const rest = src.replace(/^asset:\/\/samples?\//i, '')
  const names = [rest, path.basename(rest)]
  const dirs = [
    path.join(REPO_ROOT, 'sample'),
    path.join(REPO_ROOT, 'sample', '资料分析试卷包', 'images'),
    path.join(REPO_ROOT, 'sample', 'images'),
  ]
  const out: string[] = []
  for (const dir of dirs) for (const name of names) out.push(path.join(dir, name))
  return out
}

function resolveImage(rawPath: string, base: string): string | null {
  const p = (rawPath || '').trim()
  if (!p || p.startsWith('data:')) return null

  if (/^asset:\/\//i.test(p)) {
    for (const file of assetCandidates(p)) {
      if (fs.existsSync(file) && fs.statSync(file).isFile()) return file
    }
    return null
  }

  const absolute = path.isAbsolute(p) || /^[A-Za-z]:[\\/]/.test(p)
  const file = absolute ? p : path.resolve(base || REPO_ROOT, p)
  if (!insideRepo(file)) return null
  if (!IMAGE_EXT.test(file)) return null
  if (!fs.existsSync(file) || !fs.statSync(file).isFile()) return null
  return file
}

function sendJson(res: any, status: number, body: unknown) {
  const text = JSON.stringify(body)
  res.statusCode = status
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.setHeader('Cache-Control', 'no-store')
  res.end(text)
}

function readBody(req: any): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: any[] = []
    req.on('data', (c: any) => chunks.push(c))
    req.on('end', () => resolve(chunks.map((c) => String(c)).join('')))
    req.on('error', reject)
  })
}

export function localServer(): Plugin {
  return {
    name: 'fenbi-local-papers',
    configureServer(server) {
      server.middlewares.use('/local', async (req: any, res: any) => {
        const url = new URL(req.url || '/', 'http://localhost')
        const route = url.pathname

        try {
          if (route === '/packages' && req.method === 'GET') {
            sendJson(res, 200, { packages: listPackages() })
            return
          }

          if (route === '/package' && req.method === 'GET') {
            const file = packageFile(url.searchParams.get('id') || '')
            if (!file) return sendJson(res, 404, { error: '找不到这份本地试卷包' })
            const data = readJson(file)
            if (!data) return sendJson(res, 400, { error: '这份 JSON 解析失败' })
            sendJson(res, 200, {
              ...data,
              id: toId(file),
              path: file,
              dir: path.dirname(file),
            })
            return
          }

          if (route === '/save' && req.method === 'POST') {
            const body = JSON.parse(await readBody(req))
            const file = packageFile(String(body.path || ''))
            if (!file) return sendJson(res, 400, { error: '只能保存到仓库内已有的 JSON 试卷包' })
            if (!Array.isArray(body.questions)) return sendJson(res, 400, { error: 'questions 必须是数组' })

            const old = readJson(file) || {}
            const next = {
              ...old,
              bank: String(body.bank || old.bank || path.basename(file, '.json')),
              description: String(body.description ?? old.description ?? ''),
              questions: body.questions,
            }
            fs.writeFileSync(file, `${JSON.stringify(next, null, 2)}\n`, 'utf8')
            sendJson(res, 200, { ok: true, path: toId(file), questionCount: body.questions.length })
            return
          }

          if (route === '/img' && req.method === 'GET') {
            const file = resolveImage(
              url.searchParams.get('p') || '',
              url.searchParams.get('base') || '',
            )
            if (!file) {
              res.statusCode = 404
              res.setHeader('Content-Type', 'text/plain; charset=utf-8')
              res.end('图片不存在')
              return
            }
            res.statusCode = 200
            res.setHeader('Content-Type', mimeOf(file))
            res.setHeader('Cache-Control', 'no-store')
            res.end(fs.readFileSync(file))
            return
          }

          sendJson(res, 404, { error: `未知的本地接口：${route}` })
        } catch (e) {
          sendJson(res, 500, { error: e instanceof Error ? e.message : '本地资源服务器出错' })
        }
      })
    },
  }
}
