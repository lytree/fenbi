import { describe, expect, it } from 'vitest'
import {
  answerText, gridHtml, materialCardHtml, materialGroupHtml, materialHeadHtml,
  normKeys, noteActionHtml, optionListHtml, writeZoneHtml,
} from '../qview'

/**
 * 六种题型的共用渲染：单选圆徽标、多选/不定项方徽标、判断勾叉、
 * 填空与主观题没有选项只有作答区。三页（卷面 / 解析 / 记录）都走这一套，
 * 所以这里测的就是「同一道题在三页长得一样」的地基。
 */

const OPTS = (keys: string[], texts?: string[]) => ({
  type: 'single',
  options: keys.map((key, i) => ({ key, text: texts?.[i] ?? `选项${key}` })),
})

describe('题型渲染 · 选项', () => {
  it('单选：圆徽标、不带 multi / tf 类', () => {
    const html = optionListHtml(OPTS(['A', 'B', 'C', 'D']))
    expect(html).toContain('class="pv-opts"')
    expect(html).not.toContain('multi')
    expect(html).not.toContain('tf')
    expect(html.match(/class="pv-opt"/g)).toHaveLength(4)
  })

  it('多选 / 不定项：方徽标（multi）', () => {
    expect(optionListHtml({ ...OPTS(['A', 'B', 'C']), type: 'multi' })).toContain('pv-opts multi')
    expect(optionListHtml({ ...OPTS(['A', 'B', 'C']), type: 'uncertain' })).toContain('pv-opts multi')
  })

  it('2×2 排布只在选择题上生效，判断题永远单列', () => {
    expect(optionListHtml({ ...OPTS(['A', 'B', 'C', 'D']), optionLayout: '2x2' })).toContain('two')
    const tf = optionListHtml({
      type: 'truefalse', optionLayout: '2x2',
      options: [{ key: 'A', text: '正确' }, { key: 'B', text: '错误' }],
    })
    expect(tf).toContain('tf')
    expect(tf).not.toContain('two')
  })

  it('判断：徽标换勾叉，不再摆 A / B', () => {
    const html = optionListHtml({
      type: 'truefalse',
      options: [{ key: 'A', text: '正确' }, { key: 'B', text: '错误' }],
    })
    expect(html).toContain('>✓<')
    expect(html).toContain('>✗<')
    expect(html).not.toContain('>A<')
  })

  it('解析三态：选对绿实心、错选红、没选到的正确答案绿描边', () => {
    const html = optionListHtml(
      { ...OPTS(['A', 'B', 'C', 'D'], ['一', '二', '三', '四']), answer: 'B' },
      { keys: ['B', 'C'], userKeys: ['A', 'C'] },
    )
    // A 只被自己选：红；B 只在答案里：绿描边；C 两边都有：绿实心；D 素色
    expect(html).toContain('pv-opt wrong')
    expect(html).toContain('pv-opt ans-only')
    expect(html).toContain('pv-opt right')
    expect(html.match(/class="pv-opt"/g)).toHaveLength(1)
  })

  it('填空 / 主观题没有选项：给作答区', () => {
    expect(optionListHtml({ type: 'fill', options: [] })).toBe('')
    expect(writeZoneHtml('fill')).toContain('作答区 · 填空')
    expect(writeZoneHtml('subjective')).toContain('pv-write subjective')
    expect(writeZoneHtml('single')).toBe('')
  })
})

describe('题型渲染 · 答案', () => {
  it('判断题的答案统一翻成 正确 / 错误（A/B、T/F、对错都收）', () => {
    expect(answerText('truefalse', 'A')).toBe('正确')
    expect(answerText('truefalse', '错误')).toBe('错误')
    expect(answerText('truefalse', 'T')).toBe('正确')
    expect(answerText('single', 'B')).toBe('B')
  })

  it('答案串 → 选项字母：判断题折成 A/B，选择题取字母', () => {
    expect(normKeys('truefalse', '正确')).toEqual(['A'])
    expect(normKeys('truefalse', '×')).toEqual(['B'])
    expect(normKeys('multi', 'a c')).toEqual(['A', 'C'])
    expect(normKeys('single', '')).toEqual([])
  })

  it('宫格条：普通两格 vs 长文本（填空的参考答案）', () => {
    const plain = gridHtml([
      { k: '正确答案', v: 'B', cls: 'g' },
      { k: '你的答案', v: 'A', cls: 'no' },
    ])
    expect(plain).toContain('ov-grid c2')
    expect(plain).toContain('v g')
    expect(plain).toContain('v no')

    const long = gridHtml([
      { k: '你的答案', v: '<p>第一空</p>', cls: 'ok', long: true },
      { k: '参考答案', v: '<p>第一空 / 第二空</p>', cls: 'g', long: true },
    ])
    expect(long).toContain('ov-grid c2 long')
    expect(long).toContain('ov long')
  })

  it('笔记笔：有笔记时染蓝（on），标题换成「编辑笔记」', () => {
    expect(noteActionHtml(false)).toContain('写笔记')
    expect(noteActionHtml(false)).not.toContain('pv-act on')
    expect(noteActionHtml(true)).toContain('编辑笔记')
    expect(noteActionHtml(true)).toContain('pv-act on')
  })
})

describe('题型渲染 · 资料带多题', () => {
  it('资料头标出题号范围与小题数', () => {
    const html = materialHeadHtml(6, 2)
    expect(html).toContain('第 6–7 题')
    expect(html).toContain('2 小题共用')
    expect(html).toContain('data-fold="mat"')
    // 单题也成组：范围只写一个题号
    expect(materialHeadHtml(6, 1)).toContain('第 6 题')
  })

  it('材料卡与小题收进同一个 .pv-group，小节容器单独一层', () => {
    const html = materialGroupHtml({
      start: 6,
      count: 2,
      materialHtml: '<p>材料正文</p>',
      questionsHtml: '<div class="pv-q">6</div><div class="pv-q">7</div>',
    })
    expect(html).toContain('class="pv-group"')
    expect(html).toContain('class="pv-mat"')
    expect(html).toContain('第 6–7 题')
    expect(html).toContain('class="mbody"')
    expect(html).toContain('class="pv-subs"')
    expect(html.match(/class="pv-q"/g)).toHaveLength(2)
    // 材料在小题之前：组里先材料、后小题
    expect(html.indexOf('pv-mat')).toBeLessThan(html.indexOf('pv-subs'))
  })

  it('记录页复用材料卡时只出卡片，不老带一层小题容器', () => {
    const card = materialCardHtml(3, 4, '<p>正文</p>')
    expect(card).toContain('第 3–6 题')
    expect(card).not.toContain('pv-subs')
  })
})
