import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RecitePreview from '../components/RecitePreview'
import LibraryPane from '../components/LibraryPane'
import {
  LIBRARY_PAGE, library, libraryBlankCount, libraryGroups, libraryItemIssue, libraryPreviewItem,
  libraryShown, libraryShowMore, libraryVisibleItems, moveLibraryItemToTag, recite,
  toggleLibraryItem, updateLibraryItemText,
} from '../store'
import type { LibraryCatalog } from '../types'

/**
 * 政治理论大纲（统一库）：分段、筛选、挖空与答案的对齐、右栏预览。
 *
 * 这份库是 App 政治页的全部内容，编辑器改坏了手机上就是一片空分支 / 没有答案的下划线，
 * 所以这里钉的是「分组对不对、改正文答案跟不跟、校验拦不拦得住」。
 */

/** 预览挂在 watch 上：每个用例结束自己卸掉，残留组件会被下一个用例的状态改动带着重渲染
 *  （这里不用 enableAutoUnmount：同一个进程里 recitepreview.spec 已经开过一次，重复开会抛） */
const mounted: { unmount: () => void }[] = []
afterEach(() => {
  while (mounted.length) mounted.pop()!.unmount()
})

const catalog = (): LibraryCatalog => ({
  version: 2,
  title: '政治理论',
  intro: '',
  updated: '2026-11-15',
  hasEdits: false,
  tags: [
    { id: 'wenjian', name: '重要文件', kind: 'topic', count: 2 },
    { id: 'jingji', name: '经济', kind: 'topic', count: 1 },
    { id: 'beisong', name: '重点背诵', kind: 'source', count: 3 },
  ],
  items: [
    {
      id: 'a1',
      kind: 'card',
      text: '到 ____ 年，基本实现社会主义现代化。',
      answers: ['2035'],
      source: '第四节 重要文件',
      tags: ['wenjian', 'beisong'],
    },
    {
      id: 'a2',
      kind: 'card',
      text: '高质量发展是全面建设社会主义现代化国家的____任务。',
      answers: ['首要'],
      source: '第六节 推动高质量发展',
      tags: ['jingji', 'beisong'],
    },
    {
      id: 'a3',
      kind: 'card',
      text: '这一步没有挖空，整句显示。',
      answers: [],
      source: '第四节 重要文件',
      tags: ['wenjian', 'beisong'],
    },
  ],
})

beforeEach(() => {
  recite.subject = 'library'
  library.catalog = catalog()
  library.query = ''
  library.tag = 'all'
  library.source = 'all'
  library.expanded = []
  library.limit = {}
  library.dirty = false
})

describe('大纲分组与筛选', () => {
  it('按内容标签分段，来源标签不单独成段', () => {
    const groups = libraryGroups.value
    expect(groups.map((g) => g.tag?.id)).toEqual(['wenjian', 'jingji'])
    expect(groups[0].items.map((i) => i.id)).toEqual(['a1', 'a3'])
  })

  it('按标签筛只留这一支', () => {
    library.tag = 'jingji'
    expect(libraryVisibleItems.value.map((i) => i.id)).toEqual(['a2'])
    expect(libraryGroups.value).toHaveLength(1)
  })

  it('按来源（节）筛', () => {
    library.source = '第四节 重要文件'
    expect(libraryVisibleItems.value.map((i) => i.id)).toEqual(['a1', 'a3'])
  })

  it('搜索正文 / 节名 / 答案都命中', () => {
    library.query = '2035'
    expect(libraryVisibleItems.value.map((i) => i.id)).toEqual(['a1'])
    library.query = '第四节'
    expect(libraryVisibleItems.value.map((i) => i.id)).toEqual(['a1', 'a3'])
    library.query = '首要'
    expect(libraryVisibleItems.value.map((i) => i.id)).toEqual(['a2'])
  })

  it(`一段先出 ${LIBRARY_PAGE} 条，点「显示更多」再放一页`, () => {
    const many = catalog()
    many.items = Array.from({ length: LIBRARY_PAGE + 5 }, (_, i) => ({
      id: `x${i}`,
      kind: 'card' as const,
      text: `第 ${i} 条`,
      answers: [],
      source: '',
      tags: ['wenjian'],
    }))
    library.catalog = many
    const group = libraryGroups.value[0]
    expect(libraryShown(group)).toHaveLength(LIBRARY_PAGE)
    libraryShowMore('wenjian')
    expect(libraryShown(group)).toHaveLength(LIBRARY_PAGE + 5)
  })
})

describe('大纲编辑', () => {
  it('改正文时答案数组跟着挖空数走', () => {
    const item = library.catalog!.items[0]
    updateLibraryItemText('a1', '到 ____ 年，人均 GDP 达到 ____ 美元。')
    expect(item.answers).toEqual(['2035', ''])
    updateLibraryItemText('a1', '把挖空删掉，改成陈述句。')
    expect(item.answers).toEqual([])
    expect(libraryBlankCount(item.text)).toBe(0)
    expect(library.dirty).toBe(true)
  })

  it('换标签时内容标签放最前，来源标签留着', () => {
    moveLibraryItemToTag('a1', 'jingji')
    expect(library.catalog!.items[0].tags).toEqual(['jingji', 'beisong'])
  })

  it('校验：正文空 / 挖空与答案对不上 / 没挂标签都拦得住', () => {
    expect(libraryItemIssue(library.catalog!.items[0])).toBeNull()
    const item = library.catalog!.items[0]
    item.answers = []
    expect(libraryItemIssue(item)).toContain('____')
    item.text = '   '
    expect(libraryItemIssue(item)).toContain('空')
    item.text = '有正文但没有内容标签'
    item.tags = ['beisong']
    expect(libraryItemIssue(item)).toContain('标签')
  })
})

describe('大纲预览', () => {
  it('取最近展开的那条，没展开就取第一条', () => {
    expect(libraryPreviewItem()?.id).toBe('a1')
    toggleLibraryItem('a2')
    expect(libraryPreviewItem()?.id).toBe('a2')
  })

  it('右栏照 App 的样子排：正面编号空位，翻面出答案', async () => {
    const w = mount(RecitePreview)
    mounted.push(w)
    expect(w.text()).toContain('重要文件')
    expect(w.find('.rcp-blank').text()).toBe('1')
    expect(w.text()).not.toContain('2035')
    await w.find('.rcp-body').trigger('click')
    expect(w.find('.rcp-blank.on').text()).toBe('2035')
  })

  it('没有挖空的条目不给翻面按钮', () => {
    library.expanded = ['a3']
    const w = mount(RecitePreview)
    mounted.push(w)
    expect(w.find('.rcp-flip').exists()).toBe(false)
  })
})

describe('大纲面板', () => {
  it('按标签分段渲染，点一条展开编辑区', async () => {
    const w = mount(LibraryPane)
    mounted.push(w)
    expect(w.findAll('.lib-group')).toHaveLength(2)
    expect(w.find('.lib-group .nm').text()).toBe('重要文件')
    expect(w.findAll('.lib-item')).toHaveLength(3)

    await w.find('.lib-sum').trigger('click')
    expect(w.find('.lib-edit').exists()).toBe(true)
    // 一个挖空 → 一个答案输入框
    const answers = w.findAll('.lib-answer input')
    expect(answers).toHaveLength(1)
    expect((answers[0].element as HTMLInputElement).value).toBe('2035')
  })

  it('改正文后答案输入框跟着挖空数变', async () => {
    const w = mount(LibraryPane)
    mounted.push(w)
    await w.find('.lib-sum').trigger('click')
    const ta = w.find('.lib-edit textarea')
    await ta.setValue('到 ____ 年，人均达到 ____ 美元。')
    expect(w.findAll('.lib-answer input')).toHaveLength(2)
  })
})
