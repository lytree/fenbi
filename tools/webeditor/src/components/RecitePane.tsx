import { defineComponent, ref } from 'vue'
import type { ReciteCard } from '../types'
import {
  addReciteCard, addReciteCategory, blankReciteSelection, recite, reciteCardIssues,
  reciteVisibleCards, removeReciteCard, removeReciteCategory, renameReciteCategory,
  setReciteCardAnswers, toggleReciteCard, updateReciteCard,
} from '../store'

/** 卡片正文预览：____ 高亮成填空样式，长文截断（编辑时看全文） */
const TextPreview = ({ text, full }: { text: string; full: boolean }) => {
  const parts = text.split('____')
  return (
    <div class={['rtext', full && 'full']}>
      {parts.map((p, i) => (
        <span key={i}>
          {i > 0 ? <span class="rblank">{answersTextHint}</span> : null}
          {p}
        </span>
      ))}
    </div>
  )
}

/** ____ 的占位显示；预览里没有答案上下文，只标出「空位」 */
const answersTextHint = '▁▁▁▁'

/** 一张背诵卡的编辑态 */
const CardEditor = defineComponent({
  name: 'CardEditor',
  props: { card: { type: Object as () => ReciteCard, required: true } },
  setup(props) {
    /** 选一段正文变填空：用选区，没选区就手动在末尾加 */
    const onSelectionBlank = (e: Event) => {
      const ta = e.target as HTMLTextAreaElement
      const term = ta.value.slice(ta.selectionStart, ta.selectionEnd).trim()
      if (term) {
        blankReciteSelection(props.card.id, term)
      } else {
        updateReciteCard(props.card.id, {
          text: `${props.card.text} ____ `,
          answers: [...props.card.answers, ''],
        })
      }
    }

    const setAnswer = (i: number, v: string) => {
      const answers = props.card.answers.map((a, j) => (j === i ? v : a))
      setReciteCardAnswers(props.card.id, answers)
    }
    const addAnswer = () => {
      setReciteCardAnswers(props.card.id, [...props.card.answers, ''])
    }
    const removeAnswer = (i: number) => {
      setReciteCardAnswers(props.card.id, props.card.answers.filter((_, j) => j !== i))
    }

    return () => {
      const c = props.card
      const issues = reciteCardIssues(c)
      const cats = recite.catalog?.categories ?? []
      return (
        <div class="rbody">
          <div class="rfield">
            <label>正文（____ 是填空位，选中一段文字点「设为填空」可自动加空）</label>
            <textarea
              rows={4}
              value={c.text}
              spellcheck={false}
              placeholder="例如：我国现行宪法是 ____ 年颁布的。"
              onInput={(e) => updateReciteCard(c.id, { text: (e.target as HTMLTextAreaElement).value })}
              onSelect={onSelectionBlank}
            />
          </div>
          <div class="rfield">
            <label>
              答案（{c.answers.length} 个，与正文里的 ____ 按顺序一一对应）
              <button class="ghost sm" onClick={addAnswer}>＋ 加一个空</button>
            </label>
            {c.answers.length === 0
              ? <div class="rhint">没有填空的卡片也可以：背诵时就是一段纯知识点。</div>
              : c.answers.map((a, i) => (
                <div class="ranswer" key={i}>
                  <span class="ridx">{i + 1}</span>
                  <input
                    value={a}
                    spellcheck={false}
                    placeholder={`第 ${i + 1} 个空的答案`}
                    onInput={(e) => setAnswer(i, (e.target as HTMLInputElement).value)}
                  />
                  <button class="ghost sm danger" title="删掉这个空（正文里的 ____ 要自己对应删）" onClick={() => removeAnswer(i)}>删</button>
                </div>
              ))}
          </div>
          <div class="rfield two">
            <div>
              <label>所属分类（改这里 = 移动卡片）</label>
              <select
                class="select"
                value={c.cat}
                onChange={(e) => updateReciteCard(c.id, { cat: (e.target as HTMLSelectElement).value })}
              >
                {cats.map((x) => (
                  <option key={x.id} value={x.id}>{x.group ? `${x.group} · ${x.name}` : x.name}</option>
                ))}
              </select>
            </div>
            <div>
              <label>来源</label>
              <input
                value={c.source}
                spellcheck={false}
                placeholder="如：超格 · 新思想第一节"
                onInput={(e) => updateReciteCard(c.id, { source: (e.target as HTMLInputElement).value })}
              />
            </div>
          </div>
          <div class="rfield">
            <label>备注（App 里卡片下方的补充说明，可空）</label>
            <textarea
              rows={2}
              value={c.note}
              spellcheck={false}
              onInput={(e) => updateReciteCard(c.id, { note: (e.target as HTMLTextAreaElement).value })}
            />
          </div>
          {issues.length > 0
            ? (
              <div class="rissues">
                {issues.map((m, i) => <div key={i}>⚠ {m}</div>)}
              </div>
            )
            : null}
        </div>
      )
    }
  },
})

/** 政治 / 常识的词库编辑：分类筛选 + 卡片列表 + 逐卡编辑 */
export default defineComponent({
  name: 'RecitePane',
  setup() {
    const queryInput = ref(recite.query)
    let queryTimer: number | undefined
    const onQueryInput = (e: Event) => {
      queryInput.value = (e.target as HTMLInputElement).value
      window.clearTimeout(queryTimer)
      queryTimer = window.setTimeout(() => { recite.query = queryInput.value }, 150)
    }

    /** 分类下拉按分组聚合 optgroup */
    const catOptions = () => {
      const cats = recite.catalog?.categories ?? []
      const groups = new Map<string, typeof cats>()
      for (const c of cats) {
        const g = c.group || '未分组'
        if (!groups.has(g)) groups.set(g, [])
        groups.get(g)!.push(c)
      }
      return [...groups.entries()]
    }

    const catName = (id: string) => {
      const c = recite.catalog?.categories.find((x) => x.id === id)
      return c ? (c.group ? `${c.group} · ${c.name}` : c.name) : id
    }

    return () => {
      const cat = recite.catalog
      if (!cat) {
        return (
          <div class="empty">
            <div class="empty-card">
              <h2>{recite.loading ? '读取中…' : '选一块内容'}</h2>
              <p>左边选「政治」或「常识」，改完整份保存（手机上确认后才生效）。</p>
            </div>
          </div>
        )
      }

      const cards = reciteVisibleCards()
      const issues = recite.catalog ? recite.catalog.cards.reduce((n, c) => n + reciteCardIssues(c).length, 0) : 0
      const grouped = catOptions()

      return (
        <>
          <div class="toolbar">
            <select
              class="select"
              title="只看某个分类"
              value={recite.catFilter}
              onChange={(e) => { recite.catFilter = (e.target as HTMLSelectElement).value }}
            >
              <option value="all">全部分类（{cat.cards.length} 卡）</option>
              {grouped.map(([group, list]) => (
                <optgroup key={group} label={group}>
                  {list.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}（{cat.cards.filter((x) => x.cat === c.id).length}）
                    </option>
                  ))}
                </optgroup>
              ))}
            </select>
            <input
              class="search"
              value={queryInput.value}
              spellcheck={false}
              placeholder="搜正文 / 答案 / 备注 / 来源…"
              onInput={onQueryInput}
            />
            {recite.catFilter !== 'all'
              ? (
                <>
                  <button class="ghost sm" onClick={() => renameReciteCategory(recite.catFilter)}>改分类名</button>
                  <button class="ghost sm danger" onClick={() => removeReciteCategory(recite.catFilter)}>删分类</button>
                </>
              )
              : null}
            {issues > 0 ? <span class="pill hot">校验 {issues}</span> : null}
            <div class="toolbar-actions">
              <button class="ghost sm" onClick={addReciteCategory}>＋ 新分类</button>
              <button class="primary sm" onClick={() => addReciteCard()}>＋ 新卡</button>
            </div>
          </div>

          <div class="qlist rlist">
            {cards.length === 0
              ? (
                <div class="empty-inline">
                  {recite.query || recite.catFilter !== 'all' ? '没有命中的卡片' : '这块内容还没有卡片，点「＋ 新卡」开始'}
                </div>
              )
              : cards.map((c) => {
                const open = recite.expanded.includes(c.id)
                const cardIssues = reciteCardIssues(c)
                return (
                  <div key={c.id} class={['rcard', open && 'open', cardIssues.length && 'bad']}>
                    <div class="qhead" onClick={() => toggleReciteCard(c.id)}>
                      <div class="rgrow">
                        <TextPreview text={c.text} full={open} />
                        <div class="rmeta">
                          <span class="tag">{catName(c.cat)}</span>
                          {c.source ? <span class="tag src">{c.source}</span> : null}
                          {c.answers.length ? <span class="tag">{c.answers.length} 空</span> : <span class="tag">无填空</span>}
                          {cardIssues.length ? <span class="tag hot">⚠ {cardIssues[0]}</span> : null}
                        </div>
                      </div>
                      <div class="ops" onClick={(e) => e.stopPropagation()}>
                        <button
                          title="删除这张卡（保存后才真正落盘）"
                          onClick={() => removeReciteCard(c.id)}
                        >
                          删
                        </button>
                      </div>
                    </div>
                    {open ? <CardEditor card={c} /> : null}
                  </div>
                )
              })}
          </div>
        </>
      )
    }
  },
})
