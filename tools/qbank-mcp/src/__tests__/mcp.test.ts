import test from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js'
import { startMockServer } from './mock-server.js'

/** 真实拉起 dist/index.js 走 stdio，验证「客户端能看到工具、能调通、错误能传回来」 */
async function connect(mockUrl: string) {
  const env = Object.fromEntries(
    Object.entries(process.env).filter(([, v]) => v !== undefined)
  ) as Record<string, string>
  env.QBANK_URL = mockUrl
  env.QBANK_KEY = 'test-key'

  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [fileURLToPath(new URL('../index.js', import.meta.url))],
    env,
    stderr: 'pipe',
  })
  const client = new Client({ name: 'test-client', version: '0.0.0' })
  await client.connect(transport)
  return client
}

const textOf = (result: unknown): string => {
  const content = ((result as { content?: unknown }).content ?? []) as {
    type: string
    text?: string
  }[]
  return content
    .filter((c) => c.type === 'text')
    .map((c) => c.text ?? '')
    .join('\n')
}

test('stdio 下注册了全部工具，且能列出题库', async (t) => {
  const mock = await startMockServer()
  t.after(mock.close)
  const client = await connect(mock.url)
  t.after(() => client.close())

  const { tools } = await client.listTools()
  const names = tools.map((x) => x.name).sort()
  assert.deepEqual(names, [
    'qbank_add_questions',
    'qbank_audit_set',
    'qbank_batch_update_questions',
    'qbank_delete_set',
    'qbank_disable_question',
    'qbank_get_record',
    'qbank_get_set',
    'qbank_list_records',
    'qbank_list_sets',
    'qbank_list_versions',
    'qbank_publish_set',
    'qbank_recite_get_cards',
    'qbank_recite_reset',
    'qbank_recite_save_cards',
    'qbank_recite_subjects',
    'qbank_restore_question',
    'qbank_restore_version',
    'qbank_set_analysis',
    'qbank_update_question',
    'qbank_word_delete',
    'qbank_word_save',
    'qbank_words_search',
  ])

  const listed = await client.callTool({ name: 'qbank_list_sets', arguments: {} })
  assert.match(textOf(listed), /2024国考/)
  assert.match(textOf(listed), /行测/)
})

test('通过 MCP 加题，手机端（mock）真的多了一题', async (t) => {
  const mock = await startMockServer()
  t.after(mock.close)
  const client = await connect(mock.url)
  t.after(() => client.close())

  const result = await client.callTool({
    name: 'qbank_add_questions',
    arguments: {
      title: '2024国考',
      sheet: '行测',
      questions: [
        {
          type: 'single',
          stem: '第三题：MCP 写进来的',
          options: [
            { key: 'A', text: '甲' },
            { key: 'B', text: '乙' },
          ],
          answer: 'B',
        },
      ],
    },
  })

  assert.match(textOf(result), /加 1 题/)
  assert.equal(mock.state.sets[0].sheets[0].questions.length, 3)
  assert.equal(mock.state.sets[0].sheets[0].questions[2].stem, '第三题：MCP 写进来的')
})

test('工具出错时返回 isError，文案能直接给用户看', async (t) => {
  const mock = await startMockServer()
  t.after(mock.close)
  const client = await connect(mock.url)
  t.after(() => client.close())

  const result = await client.callTool({
    name: 'qbank_get_set',
    arguments: { title: '根本不存在的组' },
  })

  assert.equal(result.isError, true)
  assert.match(textOf(result), /qbank_list_sets/)
})

test('没配地址时服务仍能连上，调工具才提示怎么配', async (t) => {
  const mock = await startMockServer()
  t.after(mock.close)

  const env = Object.fromEntries(
    Object.entries(process.env).filter(([, v]) => v !== undefined)
  ) as Record<string, string>
  delete env.QBANK_URL
  delete env.QBANK_HOST
  delete env.QBANK_TOKEN
  delete env.QBANK_KEY

  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [fileURLToPath(new URL('../index.js', import.meta.url))],
    env,
    stderr: 'pipe',
  })
  const client = new Client({ name: 'test-client', version: '0.0.0' })
  await client.connect(transport)
  t.after(() => client.close())

  const result = await client.callTool({ name: 'qbank_list_sets', arguments: {} })
  assert.equal(result.isError, true)
  assert.match(textOf(result), /QBANK_URL/)
})

test('背诵词库：列出 → 加分类加卡 → 读到 → 校验不过整份拒绝', async (t) => {
  const mock = await startMockServer()
  t.after(mock.close)
  const client = await connect(mock.url)
  t.after(() => client.close())

  const listed = await client.callTool({ name: 'qbank_recite_subjects', arguments: {} })
  assert.match(textOf(listed), /politics/)
  assert.match(textOf(listed), /2 卡/)

  const saved = await client.callTool({
    name: 'qbank_recite_save_cards',
    arguments: {
      subject: 'politics',
      categories: { add: [{ name: '新思想', group: '超格' }] },
      additions: [
        { cat: 'p01', text: '宪法共 ____ 章。', answers: ['四'], source: '测试' },
      ],
    },
  })
  assert.match(textOf(saved), /已保存/)

  const cards = await client.callTool({
    name: 'qbank_recite_get_cards',
    arguments: { subject: 'politics', q: '宪法' },
  })
  assert.match(textOf(cards), /宪法共 ____ 章/)
  assert.match(textOf(cards), /四/)

  // ____ 数与答案数不一致：整份不保存，mock 里的卡片数不变
  const bad = await client.callTool({
    name: 'qbank_recite_save_cards',
    arguments: {
      subject: 'politics',
      additions: [{ cat: 'p01', text: '有两个 ____ 和一个 ____。', answers: ['一'] }],
    },
  })
  assert.equal(bad.isError, true)
  assert.match(textOf(bad), /2 个 ____/)
})

test('词库：搜索 → 新增 → 删除', async (t) => {
  const mock = await startMockServer()
  t.after(mock.close)
  const client = await connect(mock.url)
  t.after(() => client.close())

  const found = await client.callTool({
    name: 'qbank_words_search',
    arguments: { q: '画蛇添足' },
  })
  assert.match(textOf(found), /id=1/)

  const added = await client.callTool({
    name: 'qbank_word_save',
    arguments: { text: '守株待兔', meaning: '死守经验。', category: 'idiom' },
  })
  assert.match(textOf(added), /已新增词条「守株待兔」/)

  const again = await client.callTool({
    name: 'qbank_word_save',
    arguments: { text: '守株待兔' },
  })
  assert.equal(again.isError, true)
  assert.match(textOf(again), /已经有/)

  const deleted = await client.callTool({
    name: 'qbank_word_delete',
    arguments: { id: 903 },
  })
  assert.match(textOf(deleted), /已删除/)
})
