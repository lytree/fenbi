import { api } from './api'
import { esc } from './util'

/** 图片地址 → <img>；网络图片 App 读不了，只提示不加载 */
function imgTag(src: string): string {
  const s = (src || '').trim()
  if (!s) return ''
  if (s.startsWith('data:')) return `<img class="pv-img" src="${esc(s)}">`
  if (/^(https?:)?\/\//.test(s)) {
    return `<div class="pv-note">⚠ 网络图片 App 不支持（只读手机本地图片），这里只是预览</div>`
  }
  const rel = s.replace(/^\.\//, '')
  return `<img class="pv-img" src="${esc(api.imgUrl(rel))}" ` +
    `onerror="this.replaceWith(Object.assign(document.createElement('div'),{className:'pv-note',textContent:'这张图在手机里没找到'}))">`
}

/** 行内：图片 / 粗体 / 行内代码 / 换行 */
export function renderInline(text: string): string {
  let s = esc(text || '')
  s = s.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, (_m, _alt, src: string) => imgTag(src))
  s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  s = s.replace(/`([^`]+)`/g, '<code>$1</code>')
  return s.replace(/\n/g, '<br>')
}

/** 材料：段落 / Markdown 表格 / :::chart 占位 */
export function renderMaterial(text: string): string {
  const src = (text || '').replace(/\r/g, '').trim()
  if (!src) return ''
  return src
    .split(/\n{2,}/)
    .map((block) => {
      const b = block.trim()
      if (/^(:::chart|```chart)/.test(b)) {
        const title = (b.match(/title\s*[:：]\s*(.+)/) || [])[1] || '图表'
        return `<div class="pv-chart">📈 ${esc(title.trim())}（App 自绘图表）</div>`
      }
      if (/^\s*\|/.test(b)) {
        const rows = b
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
      return `<p style="margin:0 0 6px">${renderInline(b)}</p>`
    })
    .join('')
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
