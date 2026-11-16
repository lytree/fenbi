import { defineComponent, nextTick, ref } from 'vue'
import type { MindMapNode, MindMapTopic } from '../types'
import {
  addMindMapChild, addMindMapPair, addMindMapRoot, addMindMapSibling, addMindMapTopic, askConfirm,
  indentMindMapNode, loadMindMap, mindMapCount, mindMapIsOpen, mindmap, mindMapNodeKey,
  mindMapVisibleSections, moveMindMapNode, outdentMindMapNode, removeMindMapNode, removeMindMapPair,
  removeMindMapTopic, renameMindMapLabel, renameMindMapTopic, resetMindMap, saveMindMap,
  toggleMindMapNode, toggleMindMapTopic, updateMindMapNode, updateMindMapPair,
} from '../store'
import { theme } from '../theme'
import { graphNodeCount } from '../mindmap/tree'
import MindMapGraph from './MindMapGraph'

/** 看图时只需要这三个动作，不必把 Markmap 实例整个露给外面 */
type GraphHandle = {
  fit: () => void
  foldToLevel: (level: number) => void
  expandAll: () => void
}

/** 树里命中关键字的次数（含主题名），用于搜索时只留命中的主题 */
function hitsOf(topic: MindMapTopic, kw: string): number {
  if (!kw) return 0
  const walk = (nodes: MindMapNode[]): number =>
    nodes.reduce(
      (n, x) => n + (x.text.toLowerCase().includes(kw) ? 1 : 0) + walk(x.children ?? []),
      0,
    )
  return (topic.name.toLowerCase().includes(kw) ? 1 : 0) + walk(topic.nodes)
}

/** 行内输入框跟着内容长高（导图的行都是一句话，不该出现滚动条） */
function grow(el: HTMLTextAreaElement) {
  el.style.height = 'auto'
  el.style.height = `${el.scrollHeight}px`
}

/**
 * 思维导图编辑器。
 *
 * 内容是「板块 → 主题 → 树」三层：板块下拉筛选，一页书一张卡，卡里按层级缩进。
 * 树可以直接改字、加同/子节点、缩进反缩进、上下移动、删节点——原书导图从坐标还原出来，
 * 连线题被错落成父子关系是常事，这里就是给它收拾的手工位。
 *
 * 两个视图共用同一份数据：**大纲**是唯一的写入口，**图**是 markmap 画的只读画布
 * （折叠 / 缩放 / 拖动）。图上点节点会跳回大纲对应那行——看图时想改字，不用自己找。
 *
 * 整份读、整份写：保存要过手机确认（和背诵词库、发布同一套闸门）。
 */
export default defineComponent({
  name: 'MindMapPane',
  setup() {
    const query = ref('')
    const kw = () => query.value.trim().toLowerCase()

    /** 大纲 / 图；刷新页面回到大纲，看图是临时动作 */
    const view = ref<'outline' | 'graph'>('outline')
    /** 图里当前看的是哪一页书，空串表示「跟着筛选结果的第一条走」 */
    const graphTopicId = ref('')
    const graphRef = ref<GraphHandle | null>(null)

    const beginLoad = () => { void loadMindMap() }
    if (!mindmap.catalog && !mindmap.loading) beginLoad()

    /** 加一个空节点后把光标送进去：新节点就是最后追加的那个 */
    const focusNew = () => {
      // 交给浏览器下一帧处理，等列表把新行渲染出来
      window.setTimeout(() => {
        const all = document.querySelectorAll<HTMLTextAreaElement>('.mm-text')
        const last = all[all.length - 1]
        if (last && !last.value) { last.focus(); grow(last) }
      }, 30)
    }

    const addSibling = (topic: MindMapTopic, path: number[]) => {
      if (addMindMapSibling(topic.id, path)) focusNew()
    }
    const addChild = (topic: MindMapTopic, path: number[]) => {
      if (addMindMapChild(topic.id, path)) focusNew()
    }
    const addRoot = (topic: MindMapTopic) => {
      if (addMindMapRoot(topic.id)) { toggleOpen(topic); focusNew() }
    }
    const toggleOpen = (topic: MindMapTopic) => {
      if (!mindmap.openTopics.includes(topic.id)) toggleMindMapTopic(topic.id)
    }

    const removeNode = async (topic: MindMapTopic, node: MindMapNode, path: number[]) => {
      const extra = mindMapCount(node.children ?? [])
      if (extra > 0) {
        const ok = await askConfirm(
          `删掉这个节点？`,
          `它下面还挂着 ${extra} 条要点，会一起删掉（保存后才真正落盘）。`,
          '一起删',
        )
        if (!ok) return
      }
      removeMindMapNode(topic.id, path)
    }

    /**
     * 图上点了一个节点：切回大纲，把这一支展开，滚到那一行并闪一下。
     * 祖先逐个标展开——deep 节点点进来时，中间任何一层收着都定位不到。
     */
    const jumpTo = (topic: MindMapTopic, path: number[]) => {
      if (!mindmap.openTopics.includes(topic.id)) toggleMindMapTopic(topic.id)
      for (let i = 1; i < path.length; i++) {
        mindmap.openNodes[mindMapNodeKey(topic.id, path.slice(0, i))] = true
      }
      mindmap.openNodes[mindMapNodeKey(topic.id, path)] = true
      view.value = 'outline'
      const sel = `[data-topic="${topic.id}"] [data-path="${path.join('.')}"]`
      void nextTick(() => {
        const row = document.querySelector<HTMLElement>(sel)
        if (!row) return
        row.scrollIntoView({ block: 'center' })
        row.classList.add('flash')
        window.setTimeout(() => row.classList.remove('flash'), 1400)
      })
    }

    /** 递归渲染：折叠的分支不下钻，所以一次只画看得见的行 */
    const renderNode = (
      topic: MindMapTopic,
      node: MindMapNode,
      path: number[],
      depth: number,
    ): any[] => {
      const kids = node.children ?? []
      const open = mindMapIsOpen(topic.id, path, depth)
      const index = path[path.length - 1]
      const rows: any[] = [
        <div key={path.join('.')} class={['mm-row', `d${Math.min(depth, 4)}`]} data-path={path.join('.')}>
          <button
            class={['mm-twist', !kids.length && 'leaf']}
            title={kids.length ? (open ? '收起这一支' : '展开这一支') : '没有子节点'}
            onClick={() => kids.length && toggleMindMapNode(topic.id, path, depth)}
          >
            {kids.length ? (open ? '▾' : '▸') : '·'}
          </button>
          <textarea
            class="mm-text"
            rows={1}
            spellcheck={false}
            value={node.text}
            onInput={(e) => {
              const el = e.target as HTMLTextAreaElement
              updateMindMapNode(topic.id, path, el.value)
              grow(el)
            }}
            onFocus={(e) => grow(e.target as HTMLTextAreaElement)}
          />
          <div class="mm-ops">
            <button title="加一个子节点" onClick={() => addChild(topic, path)}>＋子</button>
            <button title="在后面加同级节点" onClick={() => addSibling(topic, path)}>＋同</button>
            <button
              title="降一级：挂到上一个节点下面"
              disabled={index === 0}
              onClick={() => indentMindMapNode(topic.id, path)}
            >→</button>
            <button
              title="升一级：变成父节点的兄弟"
              disabled={path.length < 2}
              onClick={() => outdentMindMapNode(topic.id, path)}
            >←</button>
            <button title="上移" disabled={index === 0} onClick={() => moveMindMapNode(topic.id, path, -1)}>↑</button>
            <button title="下移" onClick={() => moveMindMapNode(topic.id, path, 1)}>↓</button>
            <button class="danger" title="删掉这个节点（连它的子节点）" onClick={() => void removeNode(topic, node, path)}>删</button>
          </div>
        </div>,
      ]
      if (kids.length && open) {
        kids.forEach((kid, i) => rows.push(...renderNode(topic, kid, [...path, i], depth + 1)))
      }
      return rows
    }

    return () => {
      const cat = mindmap.catalog
      if (!cat) {
        return (
          <div class="empty">
            <div class="empty-card">
              <h2>{mindmap.loading ? '读取中…' : '读不到导图'}</h2>
              <p>确认手机端局域网编辑器还开着、已登录，再点左边「思维导图」。</p>
            </div>
          </div>
        )
      }

      const k = kw()
      const sections = mindMapVisibleSections.value
        .map((s) => ({ ...s, topics: s.topics.filter((t) => !k || hitsOf(t, k) > 0) }))
        .filter((s) => s.topics.length > 0)

      const shownTopics = sections.reduce((n, s) => n + s.topics.length, 0)
      const topics = sections.flatMap((s) => s.topics)
      const selected = topics.find((t) => t.id === graphTopicId.value) ?? topics[0]
      const isGraph = view.value === 'graph'
      // 接口偶尔只给 sections 不给 topicCount（内置资产就是这样），别在标题上写「0 主题」
      const totalTopics = cat.topicCount
        ?? cat.sections.reduce((n, s) => n + s.topics.length, 0)

      return (
        <>
          <div class="toolbar">
            <div class="tb-row">
              <div class="seg" title="大纲可改字；图只看结构，点节点跳回大纲">
                <button
                  class={['seg-btn', !isGraph && 'on']}
                  data-view="outline"
                  onClick={() => { view.value = 'outline' }}
                >大纲</button>
                <button
                  class={['seg-btn', isGraph && 'on']}
                  data-view="graph"
                  title="只看结构：折叠 / 缩放 / 拖动"
                  onClick={() => {
                    // 第一次进图，优先看大纲里正展开的那一页，没有就取筛选后的第一条
                    if (!graphTopicId.value) {
                      const opened = topics.find((t) => mindmap.openTopics.includes(t.id))
                      graphTopicId.value = opened?.id ?? topics[0]?.id ?? ''
                    }
                    view.value = 'graph'
                  }}
                >图</button>
              </div>

              {isGraph
                ? (
                  <select
                    class="select mm-topic-select"
                    title="看哪一页书"
                    value={selected?.id ?? ''}
                    onChange={(e) => { graphTopicId.value = (e.target as HTMLSelectElement).value }}
                  >
                    {sections.map((s) => (
                      <optgroup key={s.id} label={s.name}>
                        {s.topics.map((t) => (
                          <option key={t.id} value={t.id}>
                            {t.label} · {t.name}（{graphNodeCount(t)} 条）
                          </option>
                        ))}
                      </optgroup>
                    ))}
                  </select>
                )
                : (
                  <select
                    class="select"
                    title="只看某个板块"
                    value={mindmap.section}
                    onChange={(e) => { mindmap.section = (e.target as HTMLSelectElement).value }}
                  >
                    <option value="all">全部板块（{cat.sections.length} / {totalTopics} 主题）</option>
                    {cat.sections.map((s) => (
                      <option key={s.id} value={s.id}>{s.name}（{s.topics.length}）</option>
                    ))}
                  </select>
                )}

              {isGraph
                ? null
                : (
                  <input
                    class="search"
                    spellcheck={false}
                    value={query.value}
                    placeholder="搜主题 / 节点文字…"
                    onInput={(e) => { query.value = (e.target as HTMLInputElement).value }}
                  />
                )}
              {!isGraph && k ? <span class="pill">{shownTopics} 个主题命中</span> : null}
              {isGraph && selected
                ? <span class="pill">{selected.nodes.length} 个根 · {graphNodeCount(selected)} 节点</span>
                : null}
              {mindmap.dirty ? <span class="pill hot">有未保存改动</span> : null}
              <div class="toolbar-actions">
                {isGraph
                  ? (
                    <>
                      <button class="ghost sm" title="只留主题与第一层分支" onClick={() => graphRef.value?.foldToLevel(2)}>折到 2 层</button>
                      <button class="ghost sm" onClick={() => graphRef.value?.expandAll()}>全部展开</button>
                      <button class="ghost sm" onClick={() => graphRef.value?.fit()}>适应窗口</button>
                    </>
                  )
                  : (
                    <button
                      class="ghost sm"
                      title="把展开的主题都收起来"
                      onClick={() => { mindmap.openTopics = []; mindmap.openNodes = {} }}
                    >全部收起</button>
                  )}
                <button class="ghost sm" onClick={() => void resetMindMap()}>重置为内置</button>
                {isGraph
                  ? null
                  : (
                    <button
                      class="ghost sm"
                      title="在当前板块下加一个新主题"
                      onClick={() => {
                        const target = mindmap.section !== 'all' ? mindmap.section : cat.sections[0]?.id
                        if (target) addMindMapTopic(target)
                      }}
                    >＋ 主题</button>
                  )}
                <button class="primary sm" onClick={() => void saveMindMap()}>保存导图</button>
              </div>
            </div>

            {isGraph
              ? (
                <div class="tb-row mm-graphbar">
                  <span class="hint">
                    拖动画布 / 滚轮缩放 / 点圆点折叠；点节点跳回大纲那一行——改字仍在大纲里做。
                  </span>
                </div>
              )
              : null}
          </div>

          {isGraph
            ? (selected
                ? (
                  <div class="mm-graph">
                    <MindMapGraph
                      key={selected.id}
                      ref={graphRef}
                      topic={selected}
                      mode={theme.mode}
                      onPick={(path: number[]) => jumpTo(selected, path)}
                    />
                  </div>
                )
                : <div class="empty-inline">这个筛选下没有主题可看</div>)
            : (
              <div class="qlist rlist">
            {sections.length === 0
              ? <div class="empty-inline">{k ? '没有命中的主题' : '这份导图还没有内容'}</div>
              : sections.map((section) => (
                <div key={section.id} class="mm-section">
                  <div class="mm-section-head">
                    <span class="bar"></span>
                    <span class="nm">{section.name}</span>
                    <span class="mt">{section.topics.length} 主题</span>
                  </div>
                  {section.topics.map((topic) => {
                    const open = mindmap.openTopics.includes(topic.id)
                    const total = topic.nodes.reduce((n, x) => n + 1 + mindMapCount(x.children ?? []), 0)
                    const hits = hitsOf(topic, k)
                    return (
                      <div key={topic.id} class={['rcard', 'mm-card', open && 'open']} data-topic={topic.id}>
                        <div class="qhead" onClick={() => toggleMindMapTopic(topic.id)}>
                          <div class="rgrow">
                            <div class="mm-title">
                              <span class="tag">{topic.label}</span>
                              <span>{topic.name}</span>
                            </div>
                            <div class="rmeta">
                              <span class="tag">{total} 条</span>
                              {topic.nodes.length > 1 ? <span class="tag">{topic.nodes.length} 个中心主题</span> : null}
                              {topic.pairs.length ? <span class="tag">{topic.pairs.length} 组配对</span> : null}
                              {hits ? <span class="tag hot">命中 {hits}</span> : null}
                            </div>
                          </div>
                          <div class="ops" onClick={(e) => e.stopPropagation()}>
                            <button title="删掉这个主题（整棵树）" onClick={() => void removeMindMapTopic(section.id, topic.id)}>删</button>
                          </div>
                        </div>

                        {open
                          ? (
                            <div class="rbody mm-body">
                              <div class="rfield two">
                                <div>
                                  <label>主题名（App 列表上显示的就是这个）</label>
                                  <input
                                    value={topic.name}
                                    spellcheck={false}
                                    onInput={(e) => renameMindMapTopic(topic.id, (e.target as HTMLInputElement).value)}
                                  />
                                </div>
                                <div>
                                  <label>书页标签</label>
                                  <input
                                    value={topic.label}
                                    spellcheck={false}
                                    onInput={(e) => renameMindMapLabel(topic.id, (e.target as HTMLInputElement).value)}
                                  />
                                </div>
                              </div>

                              <div class="mm-tree">
                                {topic.nodes.length === 0
                                  ? (
                                    <div class="empty-inline">
                                      这一页没有节点
                                      <button class="ghost sm" onClick={() => addRoot(topic)}>＋ 中心主题</button>
                                    </div>
                                  )
                                  : topic.nodes.map((node, i) => renderNode(topic, node, [i], 0))}
                              </div>

                              {topic.nodes.length > 0
                                ? (
                                  <div class="mm-foot">
                                    <button class="ghost sm" onClick={() => addRoot(topic)}>＋ 中心主题</button>
                                    <span class="hint">缩进 = 挂到上一个节点下面；书里一层层往右的要点靠这个还原</span>
                                  </div>
                                )
                                : null}

                              <div class="mm-pairs">
                                <div class="mm-pairs-head">
                                  <span>内涵连线</span>
                                  <span class="hint">左提法 / 右内涵，App 里点「开始配对」做自测</span>
                                  <button class="ghost sm" onClick={() => addMindMapPair(topic.id)}>＋ 一组</button>
                                </div>
                                {topic.pairs.map((pair, i) => (
                                  <div class="mm-pair" key={i}>
                                    <span class="idx">{i + 1}</span>
                                    <input
                                      value={pair.left}
                                      spellcheck={false}
                                      placeholder="提法"
                                      onInput={(e) => updateMindMapPair(topic.id, i, 'left', (e.target as HTMLInputElement).value)}
                                    />
                                    <span class="arrow">↔</span>
                                    <input
                                      value={pair.right}
                                      spellcheck={false}
                                      placeholder="内涵"
                                      onInput={(e) => updateMindMapPair(topic.id, i, 'right', (e.target as HTMLInputElement).value)}
                                    />
                                    <button class="ghost sm danger" onClick={() => removeMindMapPair(topic.id, i)}>删</button>
                                  </div>
                                ))}
                              </div>
                            </div>
                          )
                          : null}
                      </div>
                    )
                  })}
                </div>
              ))}
              </div>
            )}
        </>
      )
    }
  },
})
