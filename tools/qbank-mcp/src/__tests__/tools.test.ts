import test from 'node:test'
import assert from 'node:assert/strict'
import { QbankClient } from '../client.js'
import { parseQbankUrl } from '../config.js'
import {
  addQuestions,
  auditSet,
  batchUpdateQuestions,
  deleteQuestion,
  readRecord,
  readSet,
  setAnalysis,
  updateQuestion,
} from '../tools.js'
import { startMockServer } from './mock-server.js'

const clientFor = (url: string) => new QbankClient(parseQbankUrl(url))

test('加题：读整组 → 追加到卷末 → 整组重新发布', async (t) => {
  const mock = await startMockServer()
  t.after(mock.close)
  const client = clientFor(mock.url)

  const out = await addQuestions(client, {
    title: '2024国考',
    sheet: '行测',
    questions: [
      {
        type: 'single',
        stem: '第三题：新加的',
        options: [
          { key: 'A', text: '甲' },
          { key: 'B', text: '乙' },
        ],
        answer: 'A',
        material: '',
        subject: '判断推理',
        analysis: '',
        knowledge: '',
        difficulty: '',
        region: '',
        source: '',
        year: '',
        score: '',
        keywords: '',
      },
    ],
  })

  assert.match(out, /加 1 题/)
  const set = mock.state.sets[0]
  assert.equal(set.sheets[0].questions.length, 3)
  assert.equal(set.sheets[0].questions[2].stem, '第三题：新加的')
  // 整组重导：必须带 replace，否则手机端会拒绝
  assert.equal(mock.state.publishes.at(-1)?.replace, true)
  // 原有题不能被读丢了
  assert.equal(set.sheets[0].questions[0].stem, '第一题：没有语病的一项')
})

test('加题到不存在的组：直接新建，不套 replace', async (t) => {
  const mock = await startMockServer()
  t.after(mock.close)
  const client = clientFor(mock.url)

  const out = await addQuestions(client, {
    title: '新组',
    questions: [
      {
        type: 'single',
        stem: '新组的第一题',
        options: [
          { key: 'A', text: '甲' },
          { key: 'B', text: '乙' },
        ],
        answer: 'A',
        material: '',
        subject: '',
        analysis: '',
        knowledge: '',
        difficulty: '',
        region: '',
        source: '',
        year: '',
        score: '',
        keywords: '',
      },
    ],
  })

  assert.match(out, /新建/)
  assert.equal(mock.state.sets.length, 2)
  assert.equal(mock.state.sets[1].title, '新组')
})

test('改题：只传要改的字段，其余保持原样', async (t) => {
  const mock = await startMockServer()
  t.after(mock.close)
  const client = clientFor(mock.url)

  const out = await updateQuestion(client, {
    title: '2024国考',
    sheet: '行测',
    index: 1,
    patch: { answer: 'A' },
  })

  assert.match(out, /第 1 题/)
  const q = mock.state.sets[0].sheets[0].questions[0]
  assert.equal(q.answer, 'A')
  assert.equal(q.stem, '第一题：没有语病的一项')
  assert.equal(q.options.length, 2)
})

test('删题：删掉中间一题，剩下的顺序不乱', async (t) => {
  const mock = await startMockServer()
  t.after(mock.close)
  const client = clientFor(mock.url)

  await deleteQuestion(client, { title: '2024国考', sheet: '行测', index: 1 })

  const questions = mock.state.sets[0].sheets[0].questions
  assert.equal(questions.length, 1)
  assert.equal(questions[0].stem, '第二题：同比增加多少')
})

test('读题：默认 20 题封顶，并给出翻页提示', async (t) => {
  const mock = await startMockServer()
  t.after(mock.close)
  const client = clientFor(mock.url)

  const out = await readSet(client, { title: '2024国考', limit: 1 })
  assert.match(out, /共 2 题/)
  assert.match(out, /offset=1/)
  assert.match(out, /第一题：没有语病的一项/)
  assert.doesNotMatch(out, /第二题/)
})

test('组名找不到时给出可用的下一步', async (t) => {
  const mock = await startMockServer()
  t.after(mock.close)
  const client = clientFor(mock.url)

  await assert.rejects(
    () => readSet(client, { title: '不存在的组' }),
    /qbank_list_sets/
  )
})

test('体检：缺解析、答案不在选项范围都能报出来', async (t) => {
  const mock = await startMockServer()
  t.after(mock.close)
  // 塞一道坏题：答案写了 D，但只有 A/B 两个选项
  mock.state.sets[0].sheets[0].questions.push({
    type: 'single',
    stem: '第三题：坏题',
    options: [
      { key: 'A', text: '甲' },
      { key: 'B', text: '乙' },
    ],
    answer: 'D',
  })
  const client = clientFor(mock.url)

  const out = await auditSet(client, { title: '2024国考' })
  assert.match(out, /缺解析/)
  assert.match(out, /不在选项范围内/)
  assert.match(out, /第 3 题/)
})

test('批量修题：一次发布改多道题，只动传了的字段', async (t) => {
  const mock = await startMockServer()
  t.after(mock.close)
  const client = clientFor(mock.url)

  const out = await batchUpdateQuestions(client, {
    title: '2024国考',
    updates: [
      { sheet: '行测', index: 1, patch: { analysis: 'A 项缺主语。' } },
      { sheet: '行测', index: 2, patch: { answer: 'B' } },
    ],
  })

  assert.match(out, /修改「2024国考」2 道题/)
  const questions = mock.state.sets[0].sheets[0].questions
  assert.equal(questions[0].analysis, 'A 项缺主语。')
  assert.equal(questions[1].answer, 'B')
  // 没传的字段保持原样
  assert.equal(questions[0].answer, 'B')
  assert.equal(questions[0].stem, '第一题：没有语病的一项')
  // 一次发布，不是每条一发
  assert.equal(mock.state.publishes.length, 1)
})

test('批量修题：index 越界时整批不写', async (t) => {
  const mock = await startMockServer()
  t.after(mock.close)
  const client = clientFor(mock.url)

  await assert.rejects(
    () =>
      batchUpdateQuestions(client, {
        title: '2024国考',
        updates: [
          { index: 1, patch: { analysis: '第一条' } },
          { index: 99, patch: { analysis: '不存在' } },
        ],
      }),
    /第 99 题不存在/
  )
  assert.equal(mock.state.publishes.length, 0)
})

test('批改：只看错题时，做对的题不出现', async (t) => {
  const mock = await startMockServer()
  t.after(mock.close)
  const client = clientFor(mock.url)

  const out = await readRecord(client, { recordId: 7, wrongOnly: true })
  assert.match(out, /错题 1 道/)
  assert.match(out, /第一题：没有语病的一项/)
  assert.match(out, /你答 C \/ 正确 B/)
  assert.doesNotMatch(out, /第二题：同比增加多少/)
})

test('批量写解析：只重导一次，并说出几道之前是空的', async (t) => {
  const mock = await startMockServer()
  t.after(mock.close)
  const client = clientFor(mock.url)

  const out = await setAnalysis(client, {
    title: '2024国考',
    updates: [
      { sheet: '行测', index: 1, analysis: '解析一' },
      { sheet: '行测', index: 2, analysis: '解析二' },
    ],
  })

  assert.match(out, /2 道题写入解析/)
  assert.match(out, /2 道之前是空白/)
  const questions = mock.state.sets[0].sheets[0].questions
  assert.equal(questions[0].analysis, '解析一')
  assert.equal(questions[1].analysis, '解析二')
  assert.equal(mock.state.publishes.length, 1)
})
