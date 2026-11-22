import { defineComponent, nextTick, ref } from 'vue'
import type { ReciteCard } from '../types'
import { renderRecite } from '../markdown'
import { grow, insertAtCursor, surroundAtCursor } from '../util'
import { pickImage } from '../images'
import {
  addReciteCard, addReciteCategory, recite, reciteCardIssues,
  reciteVisibleCards, removeReciteCard, removeReciteCategory, renameReciteCategory,
  setReciteCardAnswers, toggleReciteCard, updateReciteCard,
} from '../store'

/**
 * 卡片正文预览：`____` 标成编号空位，长文截断（编辑时看全文）。
 *
 * 走的是和右栏预览同一个渲染器（[renderRecite]），所以正文里写的表格在列表里
 * 就已经是真表格，不用等翻到右栏才发现 `|` 露出来了。
 */
const TextPreview = ({ text, full }: { text: string; full: boolean }) => (
  <div class={['rtext', full && 'full']} innerHTML={renderRecite(text, [], false)} />
)

/** 一张背诵卡的编辑态 */
const CardEditor = defineComponent({
  name: 'CardEditor',
  props: { card: { type: Object as () => ReciteCard, required: true } },
  setup(props) {
    /** 正文输入框：排版按钮要拿它的光标 / 选区 */
    let area: HTMLTextAreaElement | null = null
    /** 排版预览是纯查看，不写回数据 */
    const preview = ref(false)

    const withArea = (fn: (el: HTMLTextAreaElement) => void) => {
      if (area) fn(area)
    }

    /**
     * 设为填空：把选中的文字**原地**换成 ____，并把这段文字插到对应序号的答案里。
     *
     * 之前是「不管选了什么，都在正文末尾加一个空」，插在中间的空会和答案对不上；
     * 现在按选区前面已有的空数决定答案位置，顺序和正文一致。
     */
    const makeBlank = () => withArea((el) => {
      const start = el.selectionStart ?? 0
      const end = el.selectionEnd ?? start
      const term = el.value.slice(start, end).trim()
      const idx = (el.value.slice(0, start).match(/_{2,}/g) ?? []).length
      const text = el.value.slice(0, start) + '____' + el.value.slice(end)
      const answers = [...props.card.answers]
      answers.splice(Math.min(idx, answers.length), 0, term)
      updateReciteCard(props.card.id, { text, answers })
      void nextTick(() => {
        el.focus()
        el.setSelectionRange(start + 4, start + 4)
        grow(el)
      })
    })

    const insertTable = () => withArea((el) => insertAtCursor(
      el, '\n| 列1 | 列2 |\n| --- | --- |\n| 内容 | 内容 |\n',
    ))

    const addImage = () => withArea((el) => pickImage((path) => insertAtCursor(el, `![](${path})`)))

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
            <label>正文（支持 Markdown：**粗体**、`代码`、表格、图片；____ 是填空位）</label>
            <textarea
              rows={4}
              value={c.text}
              spellcheck={false}
              placeholder="例如：我国现行宪法是 ____ 年颁布的。"
              ref={(el: any) => {
                area = (el as HTMLTextAreaElement | null)
                if (area) grow(area)
              }}
              onFocus={(e) => grow(e.target as HTMLTextAreaElement)}
              onInput={(e) => {
                updateReciteCard(c.id, { text: (e.target as HTMLTextAreaElement).value })
                grow(e.target as HTMLTextAreaElement)
              }}
            />
            <div class="r-tools">
              <button
                class="ghost sm"
                title="把选中文字加粗（写成 **文字**）"
                onClick={() => withArea((el) => surroundAtCursor(el, '**', '**', '粗体'))}
              >加粗</button>
              <button
                class="ghost sm"
                title="把选中文字排成行内代码"
                onClick={() => withArea((el) => surroundAtCursor(el, '`', '`', '代码'))}
              >代码</button>
              <button class="ghost sm" title="插入一张 Markdown 表格" onClick={insertTable}>表格</button>
              <button class="ghost sm" title="把图片插入光标处" onClick={addImage}>插图</button>
              <button
                class="ghost sm"
                title="把选中文字原地换成填空位 ____"
                onClick={makeBlank}
              >设为填空</button>
              <span class="grow"></span>
              <button
                class="ghost sm"
                onClick={() => { preview.value = !preview.value }}
              >{preview.value ? '收起预览' : '排版预览'}</button>
            </div>
            {preview.value
              ? <div class="r-preview" innerHTML={renderRecite(c.text, c.answers, false)} />
              : null}
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

      const cards = reciteVisibleCards.value
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
