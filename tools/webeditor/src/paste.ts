import type { Question, QuestionType } from './types'
import { nextUid } from './util'

const TYPE_BY_NAME: Record<string, QuestionType> = {
  单选: 'single', 单选题: 'single', 选择: 'single',
  多选: 'multi', 多选题: 'multi', 多项选择: 'multi',
  不定项: 'uncertain', 不定项选择题: 'uncertain',
  判断: 'truefalse', 判断题: 'truefalse',
  填空: 'fill', 填空题: 'fill',
  主观: 'subjective', 主观题: 'subjective', 简答: 'subjective',
}

/**
 * 一段文本 → 题目。认得这些写法：
 *
 * ```
 * 1. 题干（可多行）
 * A. 选项 / A、选项 / A) 选项
 * 答案：B 或 答案：AC
 * 解析：……
 * 材料：……
 * ```
 *
 * 分块：有「1.」这种编号就按编号切；没有就按空行切（所以题与题之间空一行是保底写法）。
 * 没有选项的块直接丢掉——当前 App 只答选择题，导进去也是白导。
 */
export function parsePasted(text: string): Question[] {
  const lines = text.replace(/\r/g, '').split('\n')
  const isNumbered = (line: string) => /^\s*\d{1,3}\s*[.、)）]\s*\S/.test(line)
  const hasNumber = lines.some(isNumbered)

  const chunks: string[][] = []
  if (hasNumber) {
    let cur: string[] | null = null
    for (const line of lines) {
      if (isNumbered(line)) {
        if (cur) chunks.push(cur)
        cur = [line.replace(/^\s*\d{1,3}\s*[.、)）]\s*/, '')]
      } else if (cur) {
        cur.push(line)
      }
    }
    if (cur) chunks.push(cur)
  } else {
    let buf: string[] = []
    for (const line of lines) {
      if (!line.trim()) {
        if (buf.length) { chunks.push(buf); buf = [] }
      } else {
        buf.push(line)
      }
    }
    if (buf.length) chunks.push(buf)
  }

  return chunks.map(parseChunk).filter((q): q is Question => q !== null)
}

function parseChunk(lines: string[]): Question | null {
  const stem: string[] = []
  const material: string[] = []
  const analysis: string[] = []
  const options: { key: string; text: string }[] = []
  let mode: 'stem' | 'material' | 'analysis' | 'option' | 'answer' = 'stem'
  let answerRaw = ''
  let typeHint = ''

  for (const raw of lines) {
    const line = raw.trim()
    if (!line) {
      if (mode === 'stem') stem.push('')
      continue
    }
    let m: RegExpMatchArray | null

    if ((m = line.match(/^[【\[]?\s*材料\s*[】\]]?\s*[:：]\s*(.*)$/))) {
      mode = 'material'
      if (m[1]) material.push(m[1])
      continue
    }
    if ((m = line.match(/^[【\[]?\s*题型\s*[】\]]?\s*[:：]\s*(.*)$/))) {
      typeHint = m[1].trim()
      continue
    }
    if ((m = line.match(/^[（(]?\s*([A-Ha-h])\s*[.、．)）]\s*(.*)$/))) {
      mode = 'option'
      options.push({ key: m[1].toUpperCase(), text: m[2] })
      continue
    }
    if ((m = line.match(/^[【\[]?\s*(?:正确答案|参考答案|标准答案|答案)\s*[】\]]?\s*[:：]\s*(.*)$/))) {
      mode = 'answer'
      answerRaw = m[1].trim()
      continue
    }
    if ((m = line.match(/^[【\[]?\s*(?:答案解析|试题解析|解析)\s*[】\]]?\s*[:：]?\s*(.*)$/))) {
      mode = 'analysis'
      if (m[1]) analysis.push(m[1])
      continue
    }

    if (mode === 'option' && options.length) options[options.length - 1].text += `\n${line}`
    else if (mode === 'analysis') analysis.push(line)
    else if (mode === 'material') material.push(line)
    else stem.push(line)
  }

  const stemText = stem.join('\n').trim()
  if (!stemText) return null
  if (!options.length) return null

  const letters = Array.from(new Set((answerRaw.toUpperCase().match(/[A-H]/g) || []))).sort()
  const hinted = TYPE_BY_NAME[typeHint]
  let type: QuestionType = hinted ?? (letters.length > 1 ? 'multi' : 'single')
  if ((type === 'single') && letters.length > 1) type = 'multi'

  options.sort((a, b) => a.key.localeCompare(b.key))

  return {
    _uid: nextUid(),
    type,
    subject: '',
    material: material.join('\n').trim(),
    images: '',
    stem: stemText,
    options,
    answer: letters.join(','),
    analysis: analysis.join('\n').trim(),
    knowledge: '',
    difficulty: '',
    region: '',
    source: '',
    year: '',
    score: '',
    keywords: '',
    _issues: null,
  }
}
