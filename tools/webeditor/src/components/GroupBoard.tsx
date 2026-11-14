import { defineComponent, ref } from 'vue'
import type { Group, Question } from '../types'
import { api } from '../api'
import { firstImageSrc } from '../util'
import { plain, renderMaterial } from '../markdown'
import {
  TYPE_SHORT, addQuestionToGroup, assignSelectedToGroup, clearSelection, currentQuestions,
  focusQuestion, isSelected, moveGroupTo, moveQuestionsTo, removeSelected, selectMany,
  selectedUids, state, toggleSelected, ungroupSelected, visibleIndexes,
} from '../store'

/** 拖动中的题目：多选时整批带走；jsdom 里没有 dataTransfer，靠它兜底 */
let dragUids: number[] = []
let dragGroup: number | null = null

/**
 * 分组板：一屏里完成「材料 → 题目」的分配。
 *
 * - 拖题进材料块 = 追加到组尾并共用材料；拖到池子 = 移出材料组；
 * - 拖到某道题上 = 插到它前 / 后（落在组内就自动共用该组材料）；
 * - 勾选多道题后可批量并入材料组 / 移出分组 / 删除；
 * - 点题目卡选中该题，右侧卷面预览同步高亮并显示题干、选项、解析和图片。
 */
export default defineComponent({
  name: 'GroupBoard',
  setup() {
    /** 当前拖到哪个区域上，用来画高亮 */
    const hover = ref<string>('')

    const qs = () => currentQuestions()

    const startQuestionDrag = (e: DragEvent, q: Question) => {
      dragGroup = null
      dragUids = isSelected(q._uid) && state.selected.length > 1 ? selectedUids() : [q._uid]
      e.dataTransfer?.setData('text/plain', `q:${dragUids.join(',')}`)
      if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move'
      hover.value = ''
    }

    const startGroupDrag = (e: DragEvent, g: Group) => {
      dragGroup = g.start
      dragUids = []
      e.dataTransfer?.setData('text/plain', `g:${g.start}`)
      if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move'
      hover.value = ''
    }

    const readDrag = (e: DragEvent): { uids: number[]; group: number | null } => {
      const raw = e.dataTransfer?.getData('text/plain') || ''
      if (raw.startsWith('g:')) return { uids: [], group: Number(raw.slice(2)) }
      if (raw.startsWith('q:')) {
        const parsed = raw.slice(2).split(',').map(Number).filter((n) => Number.isFinite(n))
        return { uids: dragUids.length ? dragUids : parsed, group: null }
      }
      return { uids: dragUids, group: dragGroup }
    }

    const endDrag = () => {
      dragUids = []
      dragGroup = null
      hover.value = ''
    }

    const dropOnGroup = (e: DragEvent, g: Group) => {
      e.preventDefault()
      e.stopPropagation()
      const drag = readDrag(e)
      if (drag.group !== null) moveGroupTo(drag.group, g.start, 'before')
      else if (drag.uids.length) moveQuestionsTo(drag.uids, { kind: 'group', start: g.start })
      endDrag()
    }

    const dropOnPool = (e: DragEvent) => {
      e.preventDefault()
      const drag = readDrag(e)
      if (drag.group !== null) return
      if (drag.uids.length) moveQuestionsTo(drag.uids, { kind: 'pool' })
      endDrag()
    }

    const dropOnTile = (e: DragEvent, target: Question) => {
      e.preventDefault()
      e.stopPropagation()
      const drag = readDrag(e)
      if (!drag.group && drag.uids.length && !drag.uids.includes(target._uid)) {
        const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
        const before = e.clientY < rect.top + rect.height / 2
        moveQuestionsTo(drag.uids, {
          kind: 'index',
          uid: target._uid,
          position: before ? 'before' : 'after',
        })
      }
      endDrag()
    }

    const tile = (q: Question, index: number) => {
      const img = firstImageSrc(q)
      return (
        <div
          key={q._uid}
          class={[
            'btile',
            isSelected(q._uid) && 'sel',
            state.focused === q._uid && 'focused',
            hover.value === `q-${q._uid}` && 'drop-line',
          ]}
          data-uid={q._uid}
          draggable={true}
          onClick={() => focusQuestion(index)}
          onDragstart={(e: DragEvent) => startQuestionDrag(e, q)}
          onDragend={endDrag}
          onDragover={(e: DragEvent) => {
            e.preventDefault()
            e.stopPropagation()
            hover.value = `q-${q._uid}`
          }}
          onDragleave={() => { if (hover.value === `q-${q._uid}`) hover.value = '' }}
          onDrop={(e: DragEvent) => dropOnTile(e, q)}
        >
          <label class="bt-check" title="勾选后可批量操作" onClick={(e) => e.stopPropagation()}>
            <input
              type="checkbox"
              checked={isSelected(q._uid)}
              onChange={() => toggleSelected(q._uid)}
            />
          </label>
          <span class="bt-no">{index + 1}</span>
          <span class={['bt-type', q.type]}>{TYPE_SHORT[q.type]}</span>
          <div class="bt-main">
            <div class="bt-stem">{q.stem.trim() ? plain(q.stem, 86) : '（题干为空）'}</div>
            <div class="bt-meta">
              {q.answer.trim()
                ? <span class="ans">答案 {q.answer}</span>
                : <span class="warn">待补答案</span>}
              {q.subject ? <span>{q.subject}</span> : null}
              {q.material ? <span class="chip">材料</span> : null}
              {img ? <span class="chip">图</span> : null}
            </div>
          </div>
          {img ? <img class="bt-img" src={api.imgUrl(img)} alt="" loading="lazy" draggable={false} /> : null}
        </div>
      )
    }

    return () => {
      if (!state.paper) return null
      const all = qs()
      if (!all.length) {
        return (
          <div class="pv-empty" style="padding:48px 10px">
            这份卷还没有题目。<br />
            点右上「＋ 新题」，或者用「批量粘贴」把一段题目贴进来。
          </div>
        )
      }

      const indexes = visibleIndexes()
      if (!indexes.length) {
        return <div class="pv-empty" style="padding:40px 10px">没有符合筛选条件的题目</div>
      }

      const groups = state.groups
      const inGroup = new Set<number>()
      groups.forEach((g) => { for (let i = g.start; i <= g.end; i++) inGroup.add(i) })
      const pool = indexes.filter((i) => !inGroup.has(i))
      const ungrouped = all.filter((_q, i) => !inGroup.has(i)).length
      const score = all.reduce((n, q) => n + (Number.parseFloat(String(q.score)) || 0), 0)
      const missing = all.filter((q) => !q.answer.trim()).length
      const selected = selectedUids()

      return (
        <div class="board">
          <div class="board-hint">
            拖拽分组：把题目拖进材料块 = 并入该材料；拖到「未分组」= 移出材料；
            拖到题目上可插到它前 / 后。勾选可批量操作，点题目看右侧详情。
          </div>

          <div class="board-stats">
            <span class="bs-title">成绩</span>
            <span class="bs-item"><b>{all.length}</b> 题</span>
            <span class="bs-item"><b>{groups.length}</b> 材料组</span>
            <span class="bs-item"><b>{ungrouped}</b> 未分组</span>
            <span class="bs-item">分值合计 <b>{Number.isInteger(score) ? score : score.toFixed(1)}</b></span>
            <span class="bs-item">缺答案 <b class={missing ? 'warn' : ''}>{missing}</b></span>
            <span class="grow"></span>
            <button class="ghost sm" onClick={() => selectMany(indexes.map((i) => all[i]._uid))}>
              全选当前 {indexes.length} 题
            </button>
          </div>

          <div class="board-cols">
            <section
              class={['board-pool', hover.value === 'pool' && 'drop-on']}
              onDragover={(e: DragEvent) => { e.preventDefault(); hover.value = 'pool' }}
              onDragleave={() => { if (hover.value === 'pool') hover.value = '' }}
              onDrop={dropOnPool}
            >
              <header class="bp-head">
                <span class="bp-title">未分组</span>
                <span class="bp-count">{pool.length} 题</span>
                <span class="grow"></span>
                <span class="bp-tip">拖到这里 = 移出材料</span>
              </header>
              {pool.length
                ? <div class="btiles">{pool.map((i) => tile(all[i], i))}</div>
                : <div class="bp-empty">没有未分组的题</div>}
            </section>

            <section class="board-groups">
              {groups.length === 0
                ? <div class="bp-empty" style="padding:40px">还没有材料组：先把题目拖到一起，或到材料模式里加一则材料。</div>
                : null}
              {groups.map((g, gi) => {
                const members = indexes.filter((i) => i >= g.start && i <= g.end)
                if (!members.length) return null
                const material = all[g.start]?.material ?? g.material
                return (
                  <section
                    key={`g-${g.start}`}
                    class={['bgroup', hover.value === `g-${g.start}` && 'drop-on']}
                    onDragover={(e: DragEvent) => { e.preventDefault(); hover.value = `g-${g.start}` }}
                    onDragleave={() => { if (hover.value === `g-${g.start}`) hover.value = '' }}
                    onDrop={(e: DragEvent) => dropOnGroup(e, g)}
                  >
                    <header class="bg-head">
                      <span
                        class="bg-grip"
                        title="拖动整组调整顺序"
                        draggable={true}
                        onDragstart={(e: DragEvent) => startGroupDrag(e, g)}
                        onDragend={endDrag}
                      >
                        ⠿
                      </span>
                      <span class="bg-no">材料 {gi + 1}</span>
                      <span class="bg-meta">
                        第 {g.start + 1}–{g.end + 1} 题 · {g.end - g.start + 1} 题共用
                      </span>
                      {members.length < g.end - g.start + 1
                        ? <span class="bg-meta sift">筛出 {members.length} 题</span>
                        : null}
                      <span class="grow"></span>
                      <button class="ghost sm" onClick={() => selectMany(members.map((i) => all[i]._uid))}>
                        选中本组
                      </button>
                      <button class="ghost sm" onClick={() => { state.view = 'material' }}>
                        改材料
                      </button>
                      <button class="primary sm" onClick={() => addQuestionToGroup(g.start)}>
                        ＋ 小题
                      </button>
                    </header>

                    <div class="bg-material" innerHTML={renderMaterial(material)} />

                    <div class="btiles">
                      {members.map((i) => tile(all[i], i))}
                      <div class="bdrop">拖到这里 = 并入本组</div>
                    </div>
                  </section>
                )
              })}
            </section>
          </div>

          {selected.length
            ? (
              <div class="board-batch">
                <b>已选 {selected.length} 题</b>
                <select
                  class="select"
                  value=""
                  onChange={(e) => {
                    const el = e.target as HTMLSelectElement
                    const raw = el.value
                    el.value = ''
                    if (!raw) return
                    const v = Number(raw)
                    if (Number.isFinite(v) && v >= 0) assignSelectedToGroup(v)
                  }}
                >
                  <option value="">并入材料组…</option>
                  {groups.map((g) => (
                    <option key={g.start} value={g.start}>
                      材料 {groups.findIndex((x) => x.start === g.start) + 1}（第 {g.start + 1}–{g.end + 1} 题）
                    </option>
                  ))}
                </select>
                <button class="ghost sm" onClick={ungroupSelected}>移出分组</button>
                <button class="ghost sm danger-text" onClick={removeSelected}>删除</button>
                <span class="grow"></span>
                <button class="ghost sm" onClick={clearSelection}>清空选择</button>
              </div>
            )
            : null}
        </div>
      )
    }
  },
})
