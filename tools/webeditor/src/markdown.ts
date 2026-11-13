import { api } from './api'
import { esc } from './util'

/**
 * 图片地址 → <img>。网络图片 App 读不了，只提示不加载。
 *
 * `full` = 铺满这一栏（材料里写 `{size=full}` 或 `:::figure` 的 `size: full`）；
 * 其余按「原图尺寸、最多放大 2 倍」排——和 App 的 AdaptiveImageView 同一口径：
 * 图比行窄就居中放着，别为填满一行把小图拉糊。
 */
function imgTag(src: string, full = false): string {
  const s = (src || '').trim()
  if (!s) return ''
  if (s.startsWith('data:')) {
    return `<img class="pv-img${full ? ' pv-img-full' : ''}" src="${esc(s)}">`
  }
  if (/^(https?:)?\/\//.test(s)) {
    return `<div class="pv-note">⚠ 网络图片 App 不支持（只读手机本地图片），这里只是预览</div>`
  }
  const rel = s.replace(/^\.\//, '')
  const cls = `pv-img${full ? ' pv-img-full' : ''}`
  // 2 倍上限跟 App 一致：原图像素宽 ×2 是允许的最大显示宽度，比这更宽的行不再拉
  const clamp = full ? '' : ` onload="this.style.maxWidth=(this.naturalWidth*2)+'px'"`
  return `<img class="${cls}" src="${esc(api.imgUrl(rel))}"${clamp} ` +
    `onerror="this.replaceWith(Object.assign(document.createElement('div'),{className:'pv-note',textContent:'这张图在手机里没找到'}))">`
}

/** 行内：图片 / 粗体 / 行内代码 / 换行 */
export function renderInline(text: string): string {
  let s = esc(text || '')
  // 图片行尾可能挂尺寸标记（{size=full} / {满宽}），一并吃掉，别让它漏成正文
  s = s.replace(/!\[([^\]]*)\]\(([^)]+)\)(?:\{\s*([^}]*)\})?/g, (_m, _alt, src: string, attr: string) =>
    imgTag(src, isFull(attr)),
  )
  s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  s = s.replace(/`([^`]+)`/g, '<code>$1</code>')
  return s.replace(/\n/g, '<br>')
}

// ---------------- 材料：逐行解析 ----------------

type FigureLayout = 'left' | 'right' | 'top' | 'bottom' | 'row'

interface ImageRef {
  src: string
  alt: string
  full: boolean
}

interface FigureSpec {
  layout: FigureLayout
  width: number
  full: boolean
}

const IMAGE_EXT = /\.(png|jpe?g|webp|gif|bmp)(\?.*)?$/i

/** `size=full` / `满宽` / `铺满` → true */
function isFull(attr: string | undefined): boolean {
  return !!attr && /full|fill|满|铺/i.test(attr)
}

/** 图片行：`![说明](路径)`、`[图片](路径)`、整行一个图片地址；行尾可带 `{size=full}` */
function parseImageLine(line: string): ImageRef | null {
  let t = line.trim()
  let full = false
  const suffix = t.match(/\{\s*(?:size\s*[:=]\s*)?([^}]*)\}\s*$/)
  if (suffix && /full|fill|natural|original|满|铺|原尺寸|原图/i.test(suffix[1])) {
    full = isFull(suffix[1])
    t = t.slice(0, suffix.index).trim()
  }
  const md = t.match(/^!\[([^\]]*)\]\(\s*([^)\n]+?)\s*\)$/)
  if (md) return { src: md[2].trim(), alt: md[1].trim(), full }
  const bracket = t.match(/^\[(?:图片|图片地址|图)\]\(\s*([^)\n]+?)\s*\)$/)
  if (bracket) return { src: bracket[1].trim(), alt: '', full }
  const bare = t.replace(/^\[图片]/, '').trim()
  const looksLikePath = bare.includes('://') || bare.startsWith('/') ||
    /^[A-Za-z]:[\\/]/.test(bare) || /^data:/i.test(bare)
  if (looksLikePath && (IMAGE_EXT.test(bare) || /^data:image\//i.test(bare))) {
    return { src: bare, alt: '', full }
  }
  return null
}

function layoutOf(raw: string): FigureLayout | null {
  const v = raw.trim().toLowerCase()
  if (!v) return null
  if (v.includes('左图右文') || v.includes('图在左') || v.startsWith('left') || v === 'l') return 'left'
  if (v.includes('右图左文') || v.includes('图在右') || v.startsWith('right') || v === 'r') return 'right'
  if (v.includes('并排') || v.includes('一行') || v.startsWith('row')) return 'row'
  if (v.includes('在下') || v.includes('图下') || v.startsWith('bottom') || v === 'b') return 'bottom'
  if (v.includes('在上') || v.includes('图上') || v.startsWith('top') || v === 't') return 'top'
  return null
}

/** `38%` / `0.38` / `38` 都当比例，夹在 15%~75%（与 App 一致） */
function widthOf(raw: string): number | null {
  const v = raw.trim().replace(/%$/, '').trim()
  const n = Number(v)
  if (!Number.isFinite(n)) return null
  const ratio = raw.includes('%') || n > 1 ? n / 100 : n
  return Math.min(0.75, Math.max(0.15, ratio))
}

const FIGURE_KEYS = ['layout', '排版', '布局', 'width', '宽度', '图宽', 'size', '尺寸', 'fit', '适配']

function splitDirective(line: string): [string, string] | null {
  const sep = line.search(/[:：]/)
  if (sep <= 0) return null
  const key = line.slice(0, sep).trim().toLowerCase()
  if (!FIGURE_KEYS.includes(key)) return null
  const value = line.slice(sep + 1).trim()
  return value ? [key, value] : null
}

/** 解析 `:::figure` 块（与 App 的 MaterialRenderer.parseFigure 同口径） */
function parseFigure(head: string, body: string[]): { spec: FigureSpec; images: ImageRef[]; text: string[] } {
  const spec: FigureSpec = { layout: layoutOf(head) ?? 'top', width: 0.38, full: false }
  const images: ImageRef[] = []
  const text: string[] = []
  let directives = true
  for (const raw of body) {
    const line = raw.trim()
    if (!line) continue
    if (directives) {
      const d = splitDirective(line)
      if (d) {
        const [key, value] = d
        if (['layout', '排版', '布局'].includes(key)) spec.layout = layoutOf(value) ?? spec.layout
        else if (['width', '宽度', '图宽'].includes(key)) spec.width = widthOf(value) ?? spec.width
        else if (['size', '尺寸', 'fit', '适配'].includes(key)) spec.full = isFull(value)
        continue
      }
      directives = false
    }
    const img = parseImageLine(line)
    if (img) images.push(img)
    else text.push(line)
  }
  return { spec, images, text }
}

function figureHtml(spec: FigureSpec, images: ImageRef[], text: string[]): string {
  if (!images.length && !text.length) return ''
  const figs = images.map((i) => imgTag(i.src, spec.full || i.full)).join('')
  const txt = text.length
    ? `<div class="pv-fig-txt">${text.map((t) => `<p style="margin:0 0 4px">${renderInline(t)}</p>`).join('')}</div>`
    : ''
  if (!figs) return txt
  // 左右并排靠 CSS 的 row-reverse 换边，DOM 顺序固定「图在前、字在后」——
  // 这样右图左文不用另写一套结构，和 App 里 imgLp / txtLp 的加序是一致的
  const body = spec.layout === 'bottom'
    ? `${txt}<div class="pv-fig-imgs">${figs}</div>`
    : `<div class="pv-fig-imgs">${figs}</div>${txt}`
  const style = ` style="--fwr:${Math.round(spec.width * 100)}%"`
  return `<div class="pv-fig pv-fig-${spec.layout}"${style}>${body}</div>`
}

function tableHtml(block: string): string {
  const rows = block
    .split('\n')
    .filter((l) => l.trim().startsWith('|'))
    .filter((l) => !/^\s*\|[\s:|-]+\|\s*$/.test(l))
    .map((l) =>
      l
        .trim()
        .replace(/^\||\|$/g, '')
        .split('|')
        .map((c) => c.trim()),
    )
  if (!rows.length) return ''
  const [head, ...body] = rows
  const th = head.map((c) => `<th>${renderInline(c)}</th>`).join('')
  const tr = body
    .map((r) => `<tr>${r.map((c) => `<td>${renderInline(c)}</td>`).join('')}</tr>`)
    .join('')
  return `<table class="pv-tbl"><thead><tr>${th}</tr></thead><tbody>${tr}</tbody></table>`
}

const CHART_FENCE = /^(:::chart|```chart)/i
const FIGURE_FENCE = /^(:::figure|```figure)/i
const FENCE_CLOSE = (head: string) => (head.startsWith('```') ? '```' : ':::')

/**
 * 材料：段落 / 表格 / `:::chart` 占位 / `:::figure` 图文排版。
 *
 * 逐行扫而不是按空行切块——`:::figure` 块里可能有空行，按空行切会把一个块劈成两半，
 * 预览就和 App 对不上了。
 */
export function renderMaterial(text: string): string {
  const src = (text || '').replace(/\r/g, '').trim()
  if (!src) return ''
  const lines = src.split('\n')
  const out: string[] = []
  let para: string[] = []

  const flush = () => {
    const body = para.join('\n').trim()
    if (body) out.push(`<p style="margin:0 0 6px">${renderInline(body)}</p>`)
    para = []
  }

  let i = 0
  while (i < lines.length) {
    const line = lines[i].trim()

    if (FIGURE_FENCE.test(line)) {
      flush()
      const close = FENCE_CLOSE(line)
      const head = line.replace(/^(:::figure|```figure)/i, '').trim()
      const body: string[] = []
      let j = i + 1
      while (j < lines.length && lines[j].trim() !== close) {
        body.push(lines[j])
        j++
      }
      const fig = parseFigure(head, body)
      out.push(figureHtml(fig.spec, fig.images, fig.text))
      i = j + 1
      continue
    }

    if (CHART_FENCE.test(line)) {
      flush()
      const close = FENCE_CLOSE(line)
      const body: string[] = []
      let j = i + 1
      while (j < lines.length && lines[j].trim() !== close) {
        body.push(lines[j])
        j++
      }
      const title = (body.join('\n').match(/title\s*[:：]\s*(.+)/) || [])[1] || '图表'
      out.push(`<div class="pv-chart">📈 ${esc(title.trim())}（App 自绘图表）</div>`)
      i = j + 1
      continue
    }

    if (line.startsWith('|') && i + 1 < lines.length && /^\s*\|[\s:|-]+\|?\s*$/.test(lines[i + 1])) {
      flush()
      const block: string[] = []
      let j = i
      while (j < lines.length && lines[j].trim().startsWith('|')) {
        block.push(lines[j])
        j++
      }
      out.push(tableHtml(block.join('\n')))
      i = j
      continue
    }

    if (!line) {
      flush()
      i++
      continue
    }

    const img = parseImageLine(line)
    if (img) {
      flush()
      out.push(imgTag(img.src, img.full))
      i++
      continue
    }

    para.push(lines[i])
    i++
  }
  flush()
  return out.join('')
}

/** 摘要用：把 markdown 压成一行纯文字 */
export function plain(text: string, n: number): string {
  const s = (text || '')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, '［图片］')
    .replace(/[#>*`|]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  return s.length > n ? `${s.slice(0, n)}…` : s
}
