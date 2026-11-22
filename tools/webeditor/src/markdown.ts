import { api } from './api'
import { esc } from './util'

/**
 * 图片地址 → <img>。网络图片 App 读不了，只提示不加载。
 *
 * `full` = 铺满这一栏（材料里图片行尾写 `{size=full}`）；
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

interface ImageRef {
  src: string
  alt: string
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

/**
 * 材料：段落 / 表格 / 图片。
 */
export function renderMaterial(text: string): string {
  return renderBlocks(text)
}

/**
 * 题干：和材料同一套解析（段落 / 表格 / 图片 / 图表 / 图文块），只有段落挂的类不同。
 *
 * 题干里出现表格（资料分析的续表、判断推理的对照表被写进了题干）时不能只把文字塞进来，
 * 否则 `|` 会原样露出一排竖线——和 App 的 MaterialRenderer.renderStemRich 是同一个判断：
 * 有表格就整块按块渲染，没有就退化成一段文字。
 */
export function renderStem(text: string): string {
  return renderRich(text, 'pv-stem-p')
}

/** 任意一段富文本（段落 / 表格 / 图片 / 图表）：[paraClass] 只影响段落外层类名 */
export function renderRich(text: string, paraClass = ''): string {
  return renderBlocks(text, paraClass)
}

/**
 * 背诵卡正文：段落 / 表格照常排，`____` 换成填空样式。
 *
 * [reveal] 为假是卡片正面——留出编号下划线，看不见答案；
 * 为真时答案填进去（背面），背完点一下翻回来。答案按正文里 `____` 出现的顺序对应，
 * 和 App 背诵页同一套口径。
 */
export function renderRecite(text: string, answers: string[], reveal: boolean): string {
  const html = renderRich(text, 'rcp-p')
  let i = 0
  return html.replace(/_{2,}/g, () => {
    const n = i++
    const answer = (answers[n] ?? '').trim()
    if (!reveal) return `<span class="rcp-blank" data-n="${n + 1}">${n + 1}</span>`
    return answer
      ? `<span class="rcp-blank on" data-n="${n + 1}">${esc(answer)}</span>`
      : `<span class="rcp-blank empty" data-n="${n + 1}">（未填）</span>`
  })
}

/**
 * 逐行扫而不是按空行切块——段落里可能有空行，按空行切会把一个块劈成两半，
 * 预览就和 App 对不上了。[paraClass] 只影响段落的外层类名（题干用它微调段距）。
 */
function renderBlocks(text: string, paraClass = ''): string {
  const src = (text || '').replace(/\r/g, '').trim()
  if (!src) return ''
  const lines = src.split('\n')
  const out: string[] = []
  let para: string[] = []

  const p = paraClass ? `<p class="${paraClass}" style="margin:0 0 6px">` : '<p style="margin:0 0 6px">'

  const flush = () => {
    const body = para.join('\n').trim()
    if (body) out.push(`${p}${renderInline(body)}</p>`)
    para = []
  }

  let i = 0
  while (i < lines.length) {
    const line = lines[i].trim()

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
