import { describe, expect, it, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import App from '../App'
import { state } from '../store'

/**
 * 冒烟测试：不连手机也能验证「打开一组 → 加题 → 发布」这条主链路。
 * 手机端服务由 fetch 打桩代替，断言的是「前端发出去的 JSON 对不对」。
 */

const QUESTION = (stem: string, extra: Record<string, unknown> = {}) => ({
  type: 'single',
  subject: '言语理解',
  material: '',
  images: '',
  stem,
  options: [
    { key: 'A', text: '选项一' },
    { key: 'B', text: '选项二' },
    { key: 'C', text: '选项三' },
    { key: 'D', text: '选项四' },
  ],
  answer: 'B',
  analysis: '',
  knowledge: '',
  difficulty: '',
  region: '',
  source: '',
  year: '',
  score: 1,
  keywords: '',
  ...extra,
})

const SETS = {
  sets: [
    {
      id: 1, title: '2024国考', source: '导入', paperCount: 1, questionCount: 3,
      missingAnswers: 0, editable: true,
      papers: [{ id: 11, name: '行测', questionCount: 3, missingAnswers: 0 }],
    },
  ],
  questionTotal: 3,
  paperTotal: 1,
}

const PAPER = {
  id: 1,
  title: '2024国考',
  questionCount: 3,
  missingAnswers: 0,
  sheets: [
    {
      name: '行测',
      questions: [
        QUESTION('第一题：没有语病的一项'),
        QUESTION('第二题：同比增加多少', { material: '2025年1—2月，总收入5280.0亿元。', subject: '资料分析' }),
        QUESTION('第三题：占比约多少', { material: '2025年1—2月，总收入5280.0亿元。', subject: '资料分析' }),
      ],
    },
  ],
}

const VERSIONS = {
  versions: [
    { id: 'v1', title: '2024国考', reason: 'publish', savedAt: 1762000000000, paperCount: 1, questionCount: 2 },
    { id: 'v2', title: '2024国考', reason: 'restore', savedAt: 1761000000000, paperCount: 1, questionCount: 3 },
  ],
}

const RESOURCES = [
  { path: 'images/1/used.png', name: 'used.png', size: 2048, mtime: 1 },
  { path: 'images/1/unused.png', name: 'unused.png', size: 4096, mtime: 2 },
  { path: 'images/1/other.png', name: 'other.png', size: 512, mtime: 3 },
]

let published: any = null
let deleteRequested: number[] = []
let deleteCancelled: string[] = []
let resourceDeleted: string[] = []
let migrateCalls: number[] = []

function mockFetch() {
  published = null
  deleteRequested = []
  deleteCancelled = []
  resourceDeleted = []
  migrateCalls = []
  const fn = vi.fn(async (input: any, init?: RequestInit) => {
    const url = String(input)
    if (url.startsWith('/api/papers')) return json(SETS)
    if (url.startsWith('/api/paper')) return json(PAPER)
    if (url.startsWith('/api/versions')) return json(VERSIONS)
    if (url.startsWith('/api/images/migrate')) {
      const body = JSON.parse(String(init?.body))
      migrateCalls.push(body.setId)
      return json({
        ok: true, sets: 1, questions: 3, updated: 1, moved: 2, reused: 1, missing: 0, orphans: 0,
      })
    }
    if (url.startsWith('/api/resources')) return json({ setId: 1, dir: 'images/1', files: RESOURCES })
    if (url.startsWith('/api/resource')) {
      resourceDeleted.push(new URL(url, 'http://localhost').searchParams.get('p') || '')
      return json({ ok: true, path: resourceDeleted[resourceDeleted.length - 1] })
    }
    if (url.startsWith('/api/publish')) {
      published = JSON.parse(String(init?.body))
      return json({
        ok: true, setId: 1, title: published.title, questionCount: 99,
        replaced: false, version: null, papers: [],
      })
    }
    if (url.startsWith('/api/delete/request')) {
      const body = JSON.parse(String(init?.body))
      deleteRequested.push(body.setId)
      return json({ requestId: 'r1', title: '2024国考', detail: '这组有 1 份卷、3 道题。', ttlSec: 600 })
    }
    if (url.startsWith('/api/delete/status')) return json({ status: 'pending' })
    if (url.startsWith('/api/delete/cancel')) {
      const body = JSON.parse(String(init?.body))
      deleteCancelled.push(body.requestId)
      return json({ ok: true })
    }
    if (url.startsWith('/api/img')) return new Response('', { status: 404 })
    return json({ error: 'unexpected ' + url }, 404)
  })
  vi.stubGlobal('fetch', fn)
  return fn
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  Element.prototype.scrollIntoView = () => {}
  // jsdom 默认 1024 宽会触发窄屏逻辑（自动收起预览），这里按常见桌面宽度跑
  Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 1440 })
  mockFetch()
})

describe('局域网题库编辑器', () => {
  it('打开第一组，渲染题目列表与卷面预览', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    expect(wrapper.text()).toContain('2024国考')
    expect(wrapper.findAll('.qcard')).toHaveLength(3)
    // 材料组：两道题共用一段材料，预览里只出现一次
    expect(wrapper.findAll('.pv-q')).toHaveLength(3)
    expect(wrapper.findAll('.pv-mat')).toHaveLength(1)
    expect(wrapper.text()).toContain('第一题：没有语病的一项')
    wrapper.unmount()
  })

  it('切到材料模式：同一材料的小题收进一个材料块，独立题单独列出', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    await wrapper.find('[data-view="material"]').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.mat-block')).toHaveLength(1)
    expect(wrapper.findAll('.mat-solo')).toHaveLength(1)
    expect(wrapper.find('.mat-block .mb-meta').text()).toContain('第 2–3 题')
    expect(wrapper.find('.mat-block .mb-subhead').text()).toContain('小题 2 道')
    expect(wrapper.findAll('.mat-block .qcard')).toHaveLength(2)
    // 材料只维护一份，输入框里就是组内共用的那段
    expect((wrapper.find('.mb-text').element as HTMLTextAreaElement).value)
      .toBe('2025年1—2月，总收入5280.0亿元。')
    wrapper.unmount()
  })

  it('材料模式下「＋ 加小题」：新题自动共用材料，发布时逐题写入', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    await wrapper.find('[data-view="material"]').trigger('click')
    await flushPromises()

    const add = wrapper.findAll('.mat-block button').find((b) => b.text().includes('＋ 加小题'))
    expect(add).toBeTruthy()
    await add!.trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.mat-block .qcard')).toHaveLength(3)
    expect(wrapper.find('.mat-block .mb-subhead').text()).toContain('小题 3 道')

    const publishBtn = wrapper.findAll('button').find((b) => b.text().includes('发布到 App'))
    await publishBtn!.trigger('click')
    await flushPromises()

    const questions = published.sheets[0].questions
    expect(questions).toHaveLength(4)
    // 新加的小题落在材料组末尾，材料与组内其他题完全一致
    expect(questions[3].material).toBe('2025年1—2月，总收入5280.0亿元。')
    wrapper.unmount()
  })

  it('材料模式下改材料正文，组内每道小题一起更新', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    await wrapper.find('[data-view="material"]').trigger('click')
    await flushPromises()

    await wrapper.find('.mb-text').setValue('新的材料正文')
    await flushPromises()

    const publishBtn = wrapper.findAll('button').find((b) => b.text().includes('发布到 App'))
    await publishBtn!.trigger('click')
    await flushPromises()

    const questions = published.sheets[0].questions
    expect(questions[0].material).toBe('')
    expect(questions[1].material).toBe('新的材料正文')
    expect(questions[2].material).toBe('新的材料正文')
    wrapper.unmount()
  })

  it('材料模式下清空材料正文时输入框不会当场消失，可以接着重写', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    await wrapper.find('[data-view="material"]').trigger('click')
    await flushPromises()

    await wrapper.find('.mb-text').setValue('')
    await flushPromises()
    // 重写材料时通常先全选删掉：此时组不能立刻解散，否则输入框和焦点一起没了
    expect(wrapper.find('.mb-text').exists()).toBe(true)

    await wrapper.find('.mb-text').setValue('重写后的材料')
    await flushPromises()

    const publishBtn = wrapper.findAll('button').find((b) => b.text().includes('发布到 App'))
    await publishBtn!.trigger('click')
    await flushPromises()

    const questions = published.sheets[0].questions
    expect(questions[1].material).toBe('重写后的材料')
    expect(questions[2].material).toBe('重写后的材料')
    wrapper.unmount()
  })

  it('材料正文留空并失焦后，这一组解散、小题回到独立题', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    await wrapper.find('[data-view="material"]').trigger('click')
    await flushPromises()

    await wrapper.find('.mb-text').setValue('')
    await wrapper.find('.mb-text').trigger('blur')
    await flushPromises()

    expect(wrapper.findAll('.mat-block')).toHaveLength(0)
    expect(wrapper.findAll('.mat-solo')).toHaveLength(3)
    wrapper.unmount()
  })

  it('材料删空后不失焦直接发布，发布后按最新内容重算分组', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    await wrapper.find('[data-view="material"]').trigger('click')
    await flushPromises()

    await wrapper.find('.mb-text').setValue('')
    // 不点别处、不失焦，直接点发布（对应 Ctrl+S 的场景）
    const publishBtn = wrapper.findAll('button').find((b) => b.text().includes('发布到 App'))
    await publishBtn!.trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.mat-block')).toHaveLength(0)
    expect(wrapper.findAll('.mat-solo')).toHaveLength(3)
    const questions = published.sheets[0].questions
    expect(questions[1].material).toBe('')
    expect(questions[2].material).toBe('')
    wrapper.unmount()
  })

  it('材料模式下连续输入不会被旧值回写', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    await wrapper.find('[data-view="material"]').trigger('click')
    await flushPromises()

    const ta = wrapper.find('.mb-text')
    await ta.setValue('甲')
    await wrapper.find('.mb-text').setValue('甲乙')
    await wrapper.find('.mb-text').setValue('甲乙丙')
    await flushPromises()

    expect((wrapper.find('.mb-text').element as HTMLTextAreaElement).value).toBe('甲乙丙')

    const publishBtn = wrapper.findAll('button').find((b) => b.text().includes('发布到 App'))
    await publishBtn!.trigger('click')
    await flushPromises()
    const questions = published.sheets[0].questions
    expect(questions[1].material).toBe('甲乙丙')
    expect(questions[2].material).toBe('甲乙丙')
    wrapper.unmount()
  })

  it('材料模式下改材料，右侧卷面预览同步更新', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    await wrapper.find('[data-view="material"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('.pv-mat').text()).toContain('总收入5280')

    await wrapper.find('.mb-text').setValue('新的材料正文')
    await flushPromises()
    expect(wrapper.find('.pv-mat').text()).toContain('新的材料正文')

    // 删空时右侧按数据实时去掉材料，左侧块保留到失焦
    await wrapper.find('.mb-text').setValue('')
    await flushPromises()
    expect(wrapper.find('.pv-mat').exists()).toBe(false)
    expect(wrapper.find('.mat-block').exists()).toBe(true)
    wrapper.unmount()
  })

  it('材料块的排版预览随材料编辑实时更新', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    await wrapper.find('[data-view="material"]').trigger('click')
    await flushPromises()

    const previewBtn = wrapper.findAll('.mat-block button').find((b) => b.text().includes('排版预览'))
    await previewBtn!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.mb-preview').text()).toContain('总收入5280')

    await wrapper.find('.mb-text').setValue('| 项目 | 金额 |\n| --- | --- |\n| 收入 | 100 |')
    await flushPromises()
    expect(wrapper.find('.mb-preview').find('table').exists()).toBe(true)
    wrapper.unmount()
  })

  it('材料模式下点小题卡片，右侧卷面预览滚到并高亮同一题', async () => {
    const scrolled: Element[] = []
    Element.prototype.scrollIntoView = function (this: Element) { scrolled.push(this) }

    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    await wrapper.find('[data-view="material"]').trigger('click')
    await flushPromises()

    scrolled.length = 0
    // 组内第二道小题（第 3 题）默认折叠，点标题等于选中它
    const cards = wrapper.findAll('.mat-block .qcard .qhead')
    await cards[1].trigger('click')
    await flushPromises()

    expect(wrapper.find('.pv-q.focus').text()).toContain('第三题')
    expect(scrolled.some((el) => el.classList.contains('pv-q'))).toBe(true)
    wrapper.unmount()
  })

  it('材料模式下可以切回题目列表，材料组头仍然只在组首出现一次', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    await wrapper.find('[data-view="material"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-view="list"]').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.group-head')).toHaveLength(1)
    expect(wrapper.findAll('.qcard')).toHaveLength(3)
    wrapper.unmount()
  })

  it('左侧选中题目时，右侧卷面预览跟着滚到同一题', async () => {
    const scrolled: Element[] = []
    Element.prototype.scrollIntoView = function (this: Element) { scrolled.push(this) }

    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    scrolled.length = 0
    // 第 3 题初始是折叠的，点它的标题等于选中这一题
    await wrapper.findAll('.qcard .qhead')[2].trigger('click')
    await flushPromises()

    const hit = scrolled.find((el) => el.classList.contains('pv-q'))
    expect(hit).toBeTruthy()
    expect(hit!.textContent).toContain('第三题')
    // 预览里同一题也要高亮
    expect(wrapper.find('.pv-q.focus').text()).toContain('第三题')
    wrapper.unmount()
  })

  it('按一级 / 二级题型、作答形式筛选，选项里带各分类的题数', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    // 工具栏上的 状态 / 作答形式 / 一级题型 / 二级题型 四个下拉（题目卡里还有自己的 select，别混进来）
    const selects = wrapper.findAll('.toolbar select')
    expect(selects).toHaveLength(4)

    // 一级题型选项从当前这卷的 subject 归一后现算：言语理解 1 题、资料分析 2 题
    const subjects = selects[2].findAll('option').map((o) => o.text())
    expect(subjects).toContain('言语理解（1）')
    expect(subjects).toContain('资料分析（2）')

    // 二级题型取知识点，这卷都没填 → 合成一项「未填二级」
    const topics = selects[3].findAll('option').map((o) => o.text())
    expect(topics).toContain('未填二级（3）')

    // 作答形式下拉也带题数
    const types = selects[1].findAll('option').map((o) => o.text())
    expect(types).toContain('单选（3）')

    await selects[2].setValue('资料分析')
    await flushPromises()
    expect(wrapper.findAll('.qcard')).toHaveLength(2)
    expect(wrapper.text()).toContain('筛出 2 / 3 题')
    // 二级候选跟着一级收窄
    expect(selects[3].findAll('option').map((o) => o.text())).toContain('未填二级（2）')

    const clear = wrapper.findAll('button').find((b) => b.text() === '清除筛选')
    expect(clear).toBeTruthy()
    await clear!.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.qcard')).toHaveLength(3)
    wrapper.unmount()
  })

  it('工作表竖排成列表，不提供「全部」这种合并项', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    const items = wrapper.findAll('.sb-item')
    expect(items).toHaveLength(1)
    expect(items[0].text()).toContain('行测')
    expect(items[0].text()).toContain('3')
    // 一份一份列出来，不给「全部工作表」这种把多卷并在一起的选项
    expect(wrapper.find('.sheetbar').text()).not.toContain('全部')
    wrapper.unmount()
  })

  it('加一道题后发布，JSON 里带上新题', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    const addBtn = wrapper.findAll('button').find((b) => b.text().includes('＋ 新题'))
    expect(addBtn).toBeTruthy()
    await addBtn!.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.qcard')).toHaveLength(4)

    const publishBtn = wrapper.findAll('button').find((b) => b.text().includes('发布到 App'))
    await publishBtn!.trigger('click')
    await flushPromises()

    expect(published).toBeTruthy()
    expect(published.title).toBe('2024国考')
    expect(published.sheets[0].name).toBe('行测')
    expect(published.sheets[0].questions).toHaveLength(4)
    // 前端临时字段不能带到手机端
    expect(published.sheets[0].questions[0]._uid).toBeUndefined()
    wrapper.unmount()
  })

  it('版本面板列出历史版本，标注是发布前还是回滚前的存档', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    const tab = wrapper.findAll('button').find((b) => b.text().includes('版本'))
    expect(tab).toBeTruthy()
    await tab!.trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.ver-item')).toHaveLength(2)
    expect(wrapper.text()).toContain('发布前存档')
    expect(wrapper.text()).toContain('回滚前存档')
    wrapper.unmount()
  })

  it('资源页签：按组编号列出图片，标出未引用与缺失，可删图', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    // 第 1 题引用一张已有的图、一张手机里没有的图
    state.paper!.sheets[0].questions[0].stem +=
      '\n![x](images/1/used.png)\n![y](images/1/gone.png)'

    await wrapper.find('[data-pane="resource"]').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.res-item')).toHaveLength(3)
    expect(wrapper.find('.res-total').text()).toContain('共 3 张')
    expect(wrapper.find('.res-total').text()).toContain('引用 2 次')
    expect(wrapper.text()).toContain('引用 1 次')
    expect(wrapper.text()).toContain('未引用')
    expect(wrapper.find('.res-missing').text()).toContain('images/1/gone.png')

    const unused = wrapper.findAll('.res-item').find((n) => n.text().includes('unused.png'))!
    const del = unused.findAll('button').find((b) => b.text() === '删')!
    await del.trigger('click')
    await flushPromises()

    const confirm = wrapper.findAll('button').find((b) => b.text() === '删图')
    expect(confirm).toBeTruthy()
    await confirm!.trigger('click')
    await flushPromises()

    expect(resourceDeleted).toEqual(['images/1/unused.png'])
    expect(wrapper.findAll('.res-item')).toHaveLength(2)
    wrapper.unmount()
  })

  it('资源页签：可以把老的散图整理进本组目录', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    await wrapper.find('[data-pane="resource"]').trigger('click')
    await flushPromises()

    const btn = wrapper.findAll('button').find((b) => b.text() === '整理图片')
    expect(btn).toBeTruthy()
    await btn!.trigger('click')
    await flushPromises()

    const confirm = wrapper.findAll('button').find((b) => b.text() === '开始整理')
    expect(confirm).toBeTruthy()
    await confirm!.trigger('click')
    await flushPromises()

    expect(migrateCalls).toEqual([1])
    expect(wrapper.text()).toContain('搬了 2 张')
    wrapper.unmount()
  })

  it('删除只发请求给手机，网页上不直接删', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    const del = wrapper.findAll('.side-item .ops button').find((b) => b.text() === '删除')
    expect(del).toBeTruthy()
    await del!.trigger('click')
    await flushPromises()

    const send = wrapper.findAll('button').find((b) => b.text().includes('发到手机确认'))
    expect(send).toBeTruthy()
    await send!.trigger('click')
    await flushPromises()

    expect(deleteRequested).toEqual([1])
    expect(wrapper.text()).toContain('等手机上点「确认删除」')
    wrapper.unmount()
  })

  it('题干为空时发布会被拦下（本地校验给出错误）', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    const addBtn = wrapper.findAll('button').find((b) => b.text().includes('＋ 新题'))
    await addBtn!.trigger('click')
    await flushPromises()

    const checkBtn = wrapper.findAll('button').find((b) => b.text().trim() === '校验')
    await checkBtn!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('题干为空')
    wrapper.unmount()
  })

  it('分组板：材料组、未分组池与统计一览', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    await wrapper.find('[data-view="board"]').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.bgroup')).toHaveLength(1)
    expect(wrapper.findAll('.btile')).toHaveLength(3)
    expect(wrapper.findAll('.board-pool .btile')).toHaveLength(1)
    expect(wrapper.find('.board-stats').text()).toContain('材料组')
    expect(wrapper.find('.board-stats').text()).toContain('未分组')
    expect(wrapper.find('.bgroup .bg-material').text()).toContain('总收入5280')
    wrapper.unmount()
  })

  it('分组板：勾选题目后批量并入材料组，材料逐题写入', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    await wrapper.find('[data-view="board"]').trigger('click')
    await flushPromises()

    await wrapper.find('.board-pool .btile input[type="checkbox"]').setValue(true)
    await flushPromises()
    expect(wrapper.find('.board-batch').text()).toContain('已选 1 题')

    await wrapper.find('.board-batch select').setValue('1')
    await flushPromises()

    expect(wrapper.findAll('.bgroup .btile')).toHaveLength(3)
    expect(wrapper.findAll('.board-pool .btile')).toHaveLength(0)
    expect(wrapper.find('.board-batch').exists()).toBe(false)

    const publishBtn = wrapper.findAll('button').find((b) => b.text().includes('发布到 App'))
    await publishBtn!.trigger('click')
    await flushPromises()

    const questions = published.sheets[0].questions
    // 拖进组尾：第三题带上同一段材料，卷面上仍连续
    expect(questions[2].stem).toBe('第一题：没有语病的一项')
    expect(questions[2].material).toBe('2025年1—2月，总收入5280.0亿元。')
    wrapper.unmount()
  })

  it('分组板：拖题进材料块即并入，拖回未分组池即移出', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    await wrapper.find('[data-view="board"]').trigger('click')
    await flushPromises()

    await wrapper.find('.board-pool .btile').trigger('dragstart')
    await wrapper.find('.bgroup').trigger('drop')
    await flushPromises()
    expect(wrapper.findAll('.bgroup .btile')).toHaveLength(3)
    expect(wrapper.findAll('.board-pool .btile')).toHaveLength(0)

    await wrapper.findAll('.bgroup .btile')[0].trigger('dragstart')
    await wrapper.find('.board-pool').trigger('drop')
    await flushPromises()
    expect(wrapper.findAll('.board-pool .btile')).toHaveLength(1)

    const publishBtn = wrapper.findAll('button').find((b) => b.text().includes('发布到 App'))
    await publishBtn!.trigger('click')
    await flushPromises()

    const questions = published.sheets[0].questions
    const movedOut = questions.find((q: any) => q.stem === '第二题：同比增加多少')
    expect(movedOut.material).toBe('')
    wrapper.unmount()
  })

  it('分组板：点题目选中后右侧预览显示同一题详情', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    await wrapper.find('[data-view="board"]').trigger('click')
    await flushPromises()

    // 前面的用例可能把右栏切到「校验」，先切回卷面预览
    const previewTab = wrapper.findAll('button').find((b) => b.text().includes('卷面预览'))
    await previewTab!.trigger('click')
    await flushPromises()

    await wrapper.find('.board-pool .btile').trigger('click')
    await flushPromises()

    const focused = wrapper.find('.pv-q.focus')
    expect(focused.exists()).toBe(true)
    expect(focused.text()).toContain('第一题：没有语病的一项')
    expect(wrapper.findAll('.bgroup .btile')).toHaveLength(2)
    wrapper.unmount()
  })

  it('拖动预览左侧的分隔条可以调整预览宽度，且有上下限', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    const split = wrapper.find('.pane-split')
    expect(split.exists()).toBe(true)

    // jsdom 没有布局：面板实测宽度是 0，按默认 360 起算；左拖 200 像素 → 560
    split.element.dispatchEvent(new MouseEvent('pointerdown', { clientX: 1000, button: 0, bubbles: true }))
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 800 }))
    await flushPromises()
    expect(wrapper.find('.layout').attributes('style')).toContain('--preview-w: 560px')
    expect(document.body.classList.contains('pane-dragging')).toBe(true)

    // 拖过头也不让预览把编辑区挤没，右边限住
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 2000 }))
    await flushPromises()
    expect(wrapper.find('.layout').attributes('style')).toContain('--preview-w: 280px')

    window.dispatchEvent(new MouseEvent('pointerup'))
    await flushPromises()
    expect(document.body.classList.contains('pane-dragging')).toBe(false)
    wrapper.unmount()
  })

  it('题目列表：拖动把手可以把题插到别的题前 / 后', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()
    // 前面的用例可能把编辑区停在分组板，先切回题目列表
    await wrapper.find('[data-view="list"]').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.qcard .qgrip')).toHaveLength(3)

    // 第 1 题拖到第 3 题下半区 = 插到它后面；落进材料组就跟着共用同一段材料
    await wrapper.findAll('.qcard .qgrip')[0].trigger('dragstart')
    await wrapper.findAll('.qwrap')[2].trigger('dragover', { clientY: 10 })
    expect(wrapper.findAll('.qwrap')[2].classes()).toContain('drop-after')
    await wrapper.findAll('.qwrap')[2].trigger('drop', { clientY: 10 })
    await flushPromises()

    const stems = wrapper.findAll('.qcard .stem-preview').map((n) => n.text())
    expect(stems[0]).toContain('第二题：同比增加多少')
    expect(stems[2]).toContain('第一题：没有语病的一项')
    // 变成材料组最后一题：组从第 1 题起，三题共用
    expect(wrapper.find('.gh-count').text()).toContain('3 题共用')
    wrapper.unmount()
  })

  it('题目列表：把题拖到材料组头上即并入该材料', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()
    await wrapper.find('[data-view="list"]').trigger('click')
    await flushPromises()

    await wrapper.findAll('.qcard .qgrip')[0].trigger('dragstart')
    await wrapper.find('.group-head').trigger('dragover')
    expect(wrapper.find('.group-head').classes()).toContain('drop-on')

    await wrapper.find('.group-head').trigger('drop')
    await flushPromises()

    expect(wrapper.find('.gh-count').text()).toContain('第 1–3 题')
    expect(wrapper.find('.gh-count').text()).toContain('3 题共用')

    const publishBtn = wrapper.findAll('button').find((b) => b.text().includes('发布到 App'))
    await publishBtn!.trigger('click')
    await flushPromises()

    const questions = published.sheets[0].questions
    expect(questions[2].stem).toBe('第一题：没有语病的一项')
    expect(questions[2].material).toBe('2025年1—2月，总收入5280.0亿元。')
    wrapper.unmount()
  })

  it('材料模式不显示排序把手（拖动会和选字冲突）', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    await wrapper.find('[data-view="material"]').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.qcard').length).toBeGreaterThan(0)
    expect(wrapper.findAll('.qgrip')).toHaveLength(0)
    wrapper.unmount()
  })

  it('窄窗口默认收起预览，可手动展开', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 900 })
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    expect(wrapper.find('.layout').classes()).toContain('no-preview')
    expect(wrapper.find('.preview').exists()).toBe(false)
    expect(wrapper.find('.preview-fab').exists()).toBe(true)

    await wrapper.find('.preview-fab').trigger('click')
    await flushPromises()
    expect(wrapper.find('.layout').classes()).not.toContain('no-preview')
    expect(wrapper.find('.preview').exists()).toBe(true)

    wrapper.unmount()
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 1440 })
  })
})
