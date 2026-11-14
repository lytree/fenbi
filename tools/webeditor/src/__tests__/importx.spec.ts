import { describe, expect, it } from 'vitest'
import * as XLSX from 'xlsx'
import { matchBackfill, normHeader, normStem, parseWorkbook } from '../importx'
import type { Question } from '../types'
import { nextUid } from '../util'

const q = (patch: Partial<Question>): Question => ({
  _uid: nextUid(),
  type: 'single',
  subject: '',
  material: '',
  images: '',
  stem: '',
  options: [],
  answer: '',
  analysis: '',
  knowledge: '',
  difficulty: '',
  region: '',
  source: '',
  year: '',
  score: '',
  keywords: '',
  ...patch,
})

/** 二维表 → xlsx 字节，走真实的工作簿解析路径 */
function toXlsx(rows: (string | number)[][]): ArrayBuffer {
  const ws = XLSX.utils.aoa_to_sheet(rows)
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, 'Sheet1')
  const out = XLSX.write(wb, { bookType: 'xlsx', type: 'array' })
  return out as ArrayBuffer
}

describe('表头归一', () => {
  it('全半角 / 空格 / 括号冒号都抹平', () => {
    expect(normHeader('题 干')).toBe('题干')
    expect(normHeader('QUESTION_TYPE')).toBe('questiontype')
    expect(normHeader('（答案）')).toBe('答案')
    expect(normHeader('option_A')).toBe('optiona')
  })
})

describe('题目导入（parseWorkbook）', () => {
  const HEADER = ['题型', '题干', '选项A', '选项B', '选项C', '选项D', '答案', '解析', '科目', '材料']

  it('单选 / 多选 / 判断按行解析，答案归一', () => {
    const res = parseWorkbook(toXlsx([
      HEADER,
      ['单选', '下列正确的一项是哪个？', '甲', '乙', '丙', '丁', 'B', '乙对', '行测·判断推理', ''],
      ['多选', '下面哪些说法是对的？', '甲', '乙', '丙', '丁', 'C,A', '', '', ''],
      ['判断', '行政复议不收费。', '', '', '', '', '对', '', '', ''],
    ]), '测试')

    expect(res.kind).toBe('questions')
    if (res.kind !== 'questions') return
    expect(res.sheets).toHaveLength(1)
    const qs = res.sheets[0].questions
    expect(qs).toHaveLength(3)
    expect(qs[0].type).toBe('single')
    expect(qs[0].answer).toBe('B')
    expect(qs[0].subject).toBe('行测·判断推理')
    expect(qs[1].type).toBe('multi')
    expect(qs[1].answer).toBe('A,C')
    expect(qs[2].type).toBe('truefalse')
    // 判断题答案统一成中文，选项沿用 A/B（正确 / 错误）
    expect(qs[2].answer).toBe('正确')
    expect(qs[2].options.map((o) => o.text)).toEqual(['正确', '错误'])
  })

  it('缺答案的行按待补计数，不报错', () => {
    const res = parseWorkbook(toXlsx([
      HEADER,
      ['单选', '第一题的题干在这里。', '甲', '乙', '', '', '', '', '', ''],
      ['单选', '第二题的题干在这里。', '甲', '乙', '', '', 'A', '', '', ''],
    ]), '测试')
    expect(res.kind).toBe('questions')
    if (res.kind !== 'questions') return
    expect(res.sheets[0].missing).toBe(1)
    expect(res.sheets[0].questions).toHaveLength(2)
  })

  it('题干重复的只保留第一道', () => {
    const stem = '这道题的题干是完全一样的写法。'
    const res = parseWorkbook(toXlsx([
      HEADER,
      ['单选', stem, '甲', '乙', '', '', 'A', '', '', ''],
      ['单选', stem, '甲', '乙', '', '', 'B', '', '', ''],
    ]), '测试')
    expect(res.kind).toBe('questions')
    if (res.kind !== 'questions') return
    expect(res.sheets[0].questions).toHaveLength(1)
    expect(res.sheets[0].questions[0].answer).toBe('A')
  })

  it('填空 / 主观不带选项整题跳过（App 只答选择题）', () => {
    const res = parseWorkbook(toXlsx([
      HEADER,
      ['填空', '请填写这道填空的答案。', '', '', '', '', '', '', '', ''],
      ['主观', '请论述这道主观的要点。', '', '', '', '', '', '', '', ''],
    ]), '测试')
    // 一题都没解析出来 → 走报错路径，错误清单里说明原因
    expect(res.kind).toBe('empty')
    if (res.kind !== 'empty') return
    expect(res.error).toContain('仅支持选择题')
  })

  it('「试卷」列按行拆卷，空单元格沿用上一行', () => {
    const rows = [
      ['试卷', ...HEADER.slice(1)],
      ['2023卷', '2023年第一题的题干。', '甲', '乙', '', '', 'A', '', '', ''],
      ['', '2023年第二题的题干。', '甲', '乙', '', '', 'B', '', '', ''],
      ['2024卷', '2024年第一题的题干。', '甲', '乙', '', '', 'C', '', '', ''],
    ]
    const res = parseWorkbook(toXlsx(rows), '测试')
    expect(res.kind).toBe('questions')
    if (res.kind !== 'questions') return
    expect(res.sheets.map((s) => s.name)).toEqual(['2023卷', '2024卷'])
    expect(res.sheets[0].questions).toHaveLength(2)
    expect(res.sheets[1].questions).toHaveLength(1)
  })

  it('多个工作表各成一份卷', () => {
    const ws = XLSX.utils.aoa_to_sheet([
      HEADER,
      ['单选', '甲表的题目在这里。', '甲', '乙', '', '', 'A', '', '', ''],
    ])
    const ws2 = XLSX.utils.aoa_to_sheet([
      HEADER,
      ['单选', '乙表的题目在这里。', '甲', '乙', '', '', 'B', '', '', ''],
    ])
    const wb = XLSX.utils.book_new()
    XLSX.utils.book_append_sheet(wb, ws, '2023国考')
    XLSX.utils.book_append_sheet(wb, ws2, '2024国考')
    const res = parseWorkbook(XLSX.write(wb, { bookType: 'xlsx', type: 'array' }) as ArrayBuffer, '测试')
    expect(res.kind).toBe('questions')
    if (res.kind !== 'questions') return
    expect(res.sheets.map((s) => s.name)).toEqual(['2023国考', '2024国考'])
  })

  it('没有表头时按内容形状推断', () => {
    const res = parseWorkbook(toXlsx([
      ['这道题的题干写得很长。', '甲', '乙', '丙', 'A'],
      ['另一道题的题干也不短。', '丁', '戊', '己', 'B'],
    ]), '测试')
    expect(res.kind).toBe('questions')
    if (res.kind !== 'questions') return
    expect(res.sheets[0].questions).toHaveLength(2)
    expect(res.sheets[0].questions[0].answer).toBe('A')
    expect(res.notes.some((n) => n.message.includes('没有表头'))).toBe(true)
  })

  it('认不出题干列直接报错', () => {
    const res = parseWorkbook(toXlsx([['foo', 'bar'], ['1', '2']]), '测试')
    expect(res.kind).toBe('empty')
  })
})

describe('答案后补（backfill）', () => {
  it('只有题号/答案/解析的文件识别为后补模式', () => {
    const res = parseWorkbook(toXlsx([
      ['题号', '答案', '解析'],
      [1, 'B', '乙项符合题意'],
      [2, '正确', ''],
    ]), '答案')
    expect(res.kind).toBe('backfill')
    if (res.kind !== 'backfill') return
    expect(res.entries).toHaveLength(2)
    expect(res.entries[0]).toMatchObject({ no: 1, answer: 'B', analysis: '乙项符合题意' })
  })

  it('匹配顺序：题干优先，题号其次，顺序兜底', () => {
    const qs = [
      q({ stem: '行政复议在一些情形下是不收费的。', type: 'truefalse', answer: '' }),
      q({ stem: '同比增长量的计算公式是哪一项呢。', answer: '' }),
      q({ stem: '比重变化方向的判断依据是什么呀。', answer: '' }),
    ]
    const plan = matchBackfill([
      // 题干打乱也能按归一化后的题干对上（标点差异不影响）
      { no: null, stem: '行政复议，在一些情形下，是不收费的！', answer: '对', analysis: '', line: 2 },
      // 没题干 → 按题号 2
      { no: 2, stem: '', answer: 'A', analysis: '写了解析', line: 3 },
      // 什么都没有 → 按顺序填给还没匹配的题
      { no: null, stem: '', answer: 'C', analysis: '', line: 4 },
    ], qs)

    expect(plan.matches).toHaveLength(3)
    expect(plan.unmatched).toHaveLength(0)
    const [m1, m2, m3] = plan.matches
    expect(m1.how).toBe('stem')
    expect(m1.uid).toBe(qs[0]._uid)
    expect(m1.answer).toBe('正确')
    expect(m2.how).toBe('no')
    expect(m2.uid).toBe(qs[1]._uid)
    expect(m3.how).toBe('order')
    expect(m3.uid).toBe(qs[2]._uid)
  })

  it('覆盖已有答案 / 解析时会标记', () => {
    const qs = [q({ stem: '这道题的题干足够长可以匹配。', answer: 'A', analysis: '原有解析' })]
    const plan = matchBackfill(
      [{ no: null, stem: '这道题的题干足够长可以匹配。', answer: 'B', analysis: '新解析', line: 2 }],
      qs,
    )
    expect(plan.matches).toHaveLength(1)
    expect(plan.matches[0].overwriteAnswer).toBe(true)
    expect(plan.matches[0].overwriteAnalysis).toBe(true)
  })

  it('答案条数多于题目时多出的忽略', () => {
    const plan = matchBackfill([
      { no: 1, stem: '', answer: 'A', analysis: '', line: 2 },
      { no: 2, stem: '', answer: 'B', analysis: '', line: 3 },
    ], [q({ stem: '唯一的题目就这一道。', answer: '' })])
    expect(plan.matches).toHaveLength(1)
    expect(plan.unmatched).toHaveLength(1)
  })

  it('短题干不参与题干匹配（防误撞）', () => {
    const qs = [q({ stem: '短题干', answer: '' })]
    const plan = matchBackfill([{ no: null, stem: '短题干', answer: 'A', analysis: '', line: 2 }], qs)
    // 题干对不上（<8 字），也没有题号 → 按顺序兜底仍能填上
    expect(plan.matches).toHaveLength(1)
    expect(plan.matches[0].how).toBe('order')
  })
})

describe('normStem', () => {
  it('标点 / 空白 / 全半角差异不影响全等判断', () => {
    expect(normStem('行政复议，在一些情形下，是不收费的！'))
      .toBe(normStem('行政复议在一些情形下是不收费的'))
    expect(normStem('ＡＢＣ ｄｅｆ')).toBe(normStem('abc def'))
  })
})
