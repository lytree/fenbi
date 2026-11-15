import { defineComponent, ref, watch } from 'vue'
import type { MindMapNode, MindMapTopic } from '../types'
import { renderRecite, renderRich } from '../markdown'
import {
  library, libraryBlankCount, libraryMainTag, libraryPreviewItem, libraryTopicTags,
  mindMapPreviewTopic, mindMapSectionOf, recite, recitePreviewCard, wordPreviewItem,
  WORD_CATEGORY_LABEL,
} from '../store'
import { esc } from '../util'

/**
 * 背诵模式的右栏预览：按 App 里的样子把**当前这一条**排出来。
 *
 * 三块内容各有一套版式：
 * - 词库：词条 + 拼音 + 分类标签 + 释义（释义里的表格也排成真表格）
 * - 政治大纲：一条内容的正面（挖空）与背面（填答案）可翻，标签 / 节名 / 空数在上方
 * - 常识：卡片正面（挖空）与背面（填答案）可翻，答案清单 / 备注 / 来源在下面
 * - 思维导图：一个主题的树（可折叠）+ 连线配对
 *
 * 「当前这一条」由 store 的 *_preview_* 取值函数决定：点开哪张，右栏就是哪张。
 */
export default defineComponent({
  name: 'RecitePreview',
  setup() {
    /** 卡片是否翻到背面（显示答案）；换卡时自动翻回正面 */
    const reveal = ref(false)
    /** 导图节点的展开覆盖（只活在预览里，不动编辑区的展开状态） */
    const openNodes = ref<Record<string, boolean>>({})

    const currentCardId = () => recitePreviewCard()?.id ?? ''
    watch(currentCardId, () => { reveal.value = false })

    const currentTopicId = () => mindMapPreviewTopic()?.id ?? ''
    watch(currentTopicId, () => { openNodes.value = {} })

    // ---------------- 词库 ----------------

    const WordsView = () => {
      const w = wordPreviewItem()
      if (!w) return <div class="rcp-empty">当前页没有词条</div>
      return (
        <div class="rcp-card">
          <div class="rcp-word">
            <b>{w.text || '（新词条）'}</b>
            {w.pinyin ? <span class="wpinyin">{w.pinyin}</span> : null}
          </div>
          <div class="rcp-tags">
            <span class="tag">{WORD_CATEGORY_LABEL[w.category] ?? w.category}</span>
            {w.tag ? <span class="tag src">{w.tag}</span> : null}
          </div>
          {w.meaning
            ? <div class="rcp-rich" innerHTML={renderRich(w.meaning, 'rcp-p')} />
            : <div class="rcp-hint">这条还没有释义</div>}
          {w.example ? <div class="rcp-line"><span class="k">例句</span>{w.example}</div> : null}
          {w.source ? <div class="rcp-line"><span class="k">出处</span>{w.source}</div> : null}
        </div>
      )
    }

    // ---------------- 政治 / 常识卡片 ----------------

    const CardView = () => {
      const c = recitePreviewCard()
      if (!c) return <div class="rcp-empty">当前筛选下没有卡片</div>
      const cat = recite.catalog?.categories.find((x) => x.id === c.cat)
      const catLabel = cat ? (cat.group ? `${cat.group} · ${cat.name}` : cat.name) : c.cat
      const blankCount = (c.text.match(/_{2,}/g) ?? []).length
      return (
        <div class="rcp-card">
          <div class="rcp-head">
            <span class="tag">{catLabel}</span>
            {c.source ? <span class="tag src">{c.source}</span> : null}
            <span class="tag">{blankCount ? `${blankCount} 空` : '无填空'}</span>
            {blankCount
              ? (
                <button class="ghost sm rcp-flip" onClick={() => { reveal.value = !reveal.value }}>
                  {reveal.value ? '看正面' : '看答案'}
                </button>
              )
              : null}
          </div>
          <div
            class={['rcp-body', reveal.value && 'revealed']}
            title={blankCount ? '点一下翻面' : undefined}
            onClick={() => { if (blankCount) reveal.value = !reveal.value }}
            innerHTML={renderRecite(c.text, c.answers, reveal.value)}
          />
          {/* 答案清单只在背面出现：正面照样是「自己先想」的两个空，和 App 卡片一致 */}
          {reveal.value && c.answers.length
            ? (
              <div class="rcp-answers">
                {c.answers.map((a, i) => (
                  <div class="rcp-answer" key={i}>
                    <span class="ridx">{i + 1}</span>
                    <span>{a || <i class="rcp-hint-inline">（未填）</i>}</span>
                  </div>
                ))}
              </div>
            )
            : null}
          {c.note ? <div class="rcp-note">{c.note}</div> : null}
        </div>
      )
    }

    // ---------------- 政治大纲 ----------------

    const LibraryView = () => {
      const item = libraryPreviewItem()
      if (!item) return <div class="rcp-empty">当前筛选下没有条目</div>
      const tagId = libraryMainTag(item)
      const tag = libraryTopicTags.value.find((t) => t.id === tagId)
      const blanks = libraryBlankCount(item.text)
      const sourceTags = item.tags.filter((t) => t !== tagId)
      return (
        <div class="rcp-card">
          <div class="rcp-section">
            <span class="bar"></span>
            <span class="nm">{tag?.name ?? '未归类'}</span>
          </div>
          <div class="rcp-head">
            <span class="tag">{blanks ? `${blanks} 空` : '无填空'}</span>
            {item.source ? <span class="tag src">{item.source}</span> : null}
            {sourceTags.map((t) => {
              const name = library.catalog?.tags.find((x) => x.id === t)?.name ?? t
              return <span class="tag src">{name}</span>
            })}
            {blanks
              ? (
                <button class="ghost sm rcp-flip" onClick={() => { reveal.value = !reveal.value }}>
                  {reveal.value ? '看正面' : '看答案'}
                </button>
              )
              : null}
          </div>
          <div
            class={['rcp-body', reveal.value && 'revealed']}
            title={blanks ? '点一下翻面' : undefined}
            onClick={() => { if (blanks) reveal.value = !reveal.value }}
            innerHTML={renderRecite(item.text, item.answers, reveal.value)}
          />
          {reveal.value && item.answers.length
            ? (
              <div class="rcp-answers">
                {item.answers.map((a, i) => (
                  <div class="rcp-answer" key={i}>
                    <span class="ridx">{i + 1}</span>
                    <span>{a || <i class="rcp-hint-inline">（未填）</i>}</span>
                  </div>
                ))}
              </div>
            )
            : null}
          <div class="rcp-line"><span class="k">条目</span>{item.id}</div>
        </div>
      )
    }

    // ---------------- 思维导图 ----------------

    const NodeRow = ({ topic, node, path, depth }: {
      topic: MindMapTopic; node: MindMapNode; path: number[]; depth: number
    }) => {
      const key = path.join('.')
      const kids = node.children ?? []
      const open = openNodes.value[key] ?? depth < 2
      return (
        <>
          <div class={['rcp-node', `d${Math.min(depth, 4)}`]}>
            <button
              class={['rcp-twist', !kids.length && 'leaf']}
              onClick={() => { if (kids.length) openNodes.value[key] = !open }}
            >
              {kids.length ? (open ? '▾' : '▸') : '·'}
            </button>
            <span class="rcp-ntext">{node.text || <i class="rcp-hint-inline">（空节点）</i>}</span>
          </div>
          {open
            ? kids.map((child, i) => (
              <NodeRow topic={topic} node={child} path={[...path, i]} depth={depth + 1} />
            ))
            : null}
        </>
      )
    }

    const MindMapView = () => {
      const topic = mindMapPreviewTopic()
      if (!topic) return <div class="rcp-empty">当前板块下没有主题</div>
      const section = mindMapSectionOf(topic.id)
      const nodes = topic.nodes ?? []
      return (
        <div class="rcp-card">
          <div class="rcp-section">
            <span class="bar"></span>
            <span class="nm">{section?.name ?? '未分板块'}</span>
          </div>
          <div class="rcp-mm-title">
            <span class="tag">{topic.label}</span>
            <b>{topic.name}</b>
          </div>
          <div class="rcp-tree">
            {nodes.length
              ? nodes.map((n, i) => <NodeRow topic={topic} node={n} path={[i]} depth={0} />)
              : <div class="rcp-hint">这个主题还没有节点</div>}
          </div>
          {topic.pairs?.length
            ? (
              <div class="rcp-pairs">
                <div class="rcp-pairs-head">连线配对（{topic.pairs.length} 组）</div>
                {topic.pairs.map((p, i) => (
                  <div class="rcp-pair" key={i}>
                    <span class="l">{esc(p.left)}</span>
                    <span class="arrow">↔</span>
                    <span class="r">{esc(p.right)}</span>
                  </div>
                ))}
              </div>
            )
            : null}
        </div>
      )
    }

    return () => (
      <div class="rcp">
        <div class="rcp-tip">这一栏按 App 里的样子排，编辑不影响它；点开左边哪张，这里就是哪张。</div>
        {recite.subject === 'words'
          ? <WordsView />
          : recite.subject === 'library'
          ? <LibraryView />
          : recite.subject === 'mindmap' ? <MindMapView /> : <CardView />}
      </div>
    )
  },
})
