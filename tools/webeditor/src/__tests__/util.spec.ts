import { describe, expect, it } from 'vitest'
import { firstImageSrc } from '../util'
import type { Question } from '../types'

const q = (patch: Partial<Question>): Question => ({
  _uid: 1,
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

describe('分组板缩略图取图', () => {
  it('优先取材料里的第一张 markdown 图', () => {
    expect(firstImageSrc(q({
      stem: '看图作答',
      material: '材料\n![折线图](images/chart-01.png)\n更多',
    }))).toBe('images/chart-01.png')
  })

  it('没有 markdown 时回落到裸路径 / 绝对地址', () => {
    expect(firstImageSrc(q({ stem: '见 ./images/a-1.jpg' }))).toBe('./images/a-1.jpg')
    expect(firstImageSrc(q({ options: [{ key: 'A', text: 'https://x.test/b.png' }] })))
      .toBe('https://x.test/b.png')
  })

  it('没有任何图时给空串', () => {
    expect(firstImageSrc(q({ stem: '纯文字' }))).toBe('')
  })
})
