/**
 * 图文排版：把材料里选中的那段文字包成 `:::figure` 块。
 * 值与 App 的 MaterialRenderer.FigureLayout 一致。
 */
export const FIGURE_LAYOUTS: [string, string][] = [
  ['left', '左图右文'],
  ['right', '右图左文'],
  ['top', '图在上'],
  ['bottom', '图在下'],
  ['row', '多图并排'],
]

/**
 * 不选中就插一段「图 + 字」的示例——手写围栏容易漏结束行，给个能跑的样板更省事。
 */
export function wrapFigure(text: string, from: number, to: number, layout: string): string {
  const src = text || ''
  const picked = src.slice(from, to).trim()
  const body = picked || '![说明](images/示例.png)\n这里的文字会和图片并排显示'
  const block = `:::figure\nlayout: ${layout}\nwidth: 38%\n${body}\n:::`
  const head = src.slice(0, from)
  const tail = src.slice(to)
  return `${head}${head && !head.endsWith('\n') ? '\n' : ''}${block}${tail}`
}
