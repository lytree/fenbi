import { describe, expect, it } from 'vitest'
import {
  editorQuestionToLocal, editorTypeToLocal, firstImageSrc, localQuestionToEditor,
  localToQuestions, localTypeToEditor, questionsToLocal,
} from '../local'

describe('本地试卷包字段转换', () => {
  it('中文题型 / 对象选项 → 编辑器结构', () => {
    const q = localQuestionToEditor({
      type: '多选',
      stem: '下列说法正确的有',
      options: { C: '丙', A: '甲', B: '乙' },
      answer: 'A,B',
      subject: '行测·常识判断',
      material: '材料正文',
      difficulty: 2,
      score: 1,
    })
    expect(q.type).toBe('multi')
    expect(q.options.map((o) => o.key)).toEqual(['A', 'B', 'C'])
    expect(q.options[0].text).toBe('甲')
    expect(q.answer).toBe('A,B')
    expect(q.subject).toBe('行测·常识判断')
    expect(q.difficulty).toBe('2')
    expect(q.score).toBe('1')
  })

  it('判断题的 A / B 答案归一成中文', () => {
    expect(localQuestionToEditor({ type: '判断', answer: 'A' }).answer).toBe('正确')
    expect(localQuestionToEditor({ type: '判断题', answer: 'F' }).answer).toBe('错误')
  })

  it('编辑器结构 → 本地 JSON：选项回到对象，数字字段回到数字', () => {
    const q = localQuestionToEditor({
      type: '单选',
      stem: '题干',
      options: { A: '甲', B: '乙' },
      answer: 'B',
      difficulty: 1,
      score: 2,
    })
    const raw = editorQuestionToLocal(q)
    expect(raw.type).toBe('单选')
    expect(raw.options).toEqual({ A: '甲', B: '乙' })
    expect(raw.difficulty).toBe(1)
    expect(raw.score).toBe(2)
  })

  it('题型互转与整包读取', () => {
    expect(localTypeToEditor('不定项选择题')).toBe('uncertain')
    expect(editorTypeToLocal('truefalse')).toBe('判断')
    const qs = localToQuestions({
      id: 'sample/a.json',
      path: 'sample/a.json',
      questions: [{ type: '单选', stem: '一' }, { type: '填空', stem: '二' }],
    })
    expect(qs).toHaveLength(2)
    expect(qs[0]._uid).not.toBe(qs[1]._uid)
    expect(questionsToLocal(qs).map((q) => q.type)).toEqual(['单选', '填空'])
  })

  it('从材料 / 题干里取第一张图', () => {
    const q = localQuestionToEditor({
      type: '单选',
      stem: '看图作答',
      material: '材料\n![折线图](images/chart-01.png)\n更多',
    })
    expect(firstImageSrc(q)).toBe('images/chart-01.png')
  })
})
