import { defineComponent, nextTick, onMounted, ref, watch } from 'vue'
import type { Group, Question } from '../types'
import { currentQuestions, editNote, focusQuestion, groupsOf, state, TYPE_LABEL } from '../store'
import { esc } from '../util'
import { renderMaterial, renderStem } from '../markdown'
import { LEVEL_NONE, level1Of, level2Of } from '../xingce'
import { answerText, gridHtml, isBlank, materialGroupHtml, noteActionHtml, normKeys, optionListHtml } from '../qview'
import type { GridCell } from '../qview'
import PaperDock from './PaperDock'

/**
 * 「答案解析」页：粉笔解析页的版式——每题一张卡，答案铺成灰底宫格条，
 * 解析装进可折叠的「解析」盒（蓝条 + 标题 + 箭头，点标题收起 / 展开），
 * 知识点画成蓝色胶囊，笔记点一下就能改（和 App 同一份 notes 表）。
 * 六种题型各有各的摆法：单选圆徽标、多选/不定项方徽标、判断勾叉、
 * 填空与主观题没有选项、参考答案整段铺开。
 *
 * 两处用：编辑器右栏（窄，资料题上下排）与整页查看（`full`，资料题左右分屏）。
 * 编辑器里点题跳左卡片、左边选中这里滚过来；整页版没有编辑区，只有内容。
 */
export default defineComponent({
  name: 'AnswerPane',
  props: {
    /** 整页查看：资料题改左右分屏（材料左、题目右），窄栏里保持上下排 */
    full: { type: Boolean, default: false },
  },
  setup(props) {
    const pane = ref<HTMLElement | null>(null)
    /** 每组资料当前看的是第几道小题（键 = 组首题的索引） */
    const tabIdx = ref<Record<number, number>>({})
    /** 每组资料左栏宽度（百分比，拖动分屏把手时改；键同上） */
    const matWidth = ref<Record<number, number>>({})

    /** 把当前选中（state.focused）的那题滚到面板中间 */
    const scrollToFocused = (smooth: boolean) => {
      const uid = state.focused
      if (uid === null) return
      const hit = pane.value?.querySelector(`.pv-q[data-uid="${uid}"]`) as HTMLElement | null
      // jsdom（单测）里没有 scrollIntoView，跳过这一步
      if (typeof hit?.scrollIntoView === 'function') {
        hit.scrollIntoView({ block: 'center', behavior: smooth ? 'smooth' : 'auto' })
      }
    }

    /**
     * 整页分屏里一组材料只摆一道小题：从答题卡跳到组内第 3 题时，
     * 得先把这个组的页签切过去，否则滚到的还是原来那道。
     */
    const syncSplitTab = () => {
      if (!props.full) return
      const qs = currentQuestions()
      const i = qs.findIndex((q) => q._uid === state.focused)
      if (i < 0) return
      for (const g of groupsOf(qs)) {
        if (i >= g.start && i <= g.end) tabIdx.value[g.start] = i - g.start
      }
    }

    watch(() => state.focused, () => {
      syncSplitTab()
      void nextTick(() => scrollToFocused(true))
    })
    onMounted(() => { void nextTick(() => scrollToFocused(false)) })

    const blockHtml = (q: Question, index: number): string => {
      const focus = state.focused === q._uid ? ' focus' : ''

      let h = `<div class="pv-q${focus}" data-uid="${q._uid}">`
      // 粉笔口径：题号在前，题型标签跟在后；右端那支笔就是笔记入口（有笔记时染蓝）
      const lv1 = level1Of(q)
      const lv2 = level2Of(q)
      const hasTypeTree = !!lv1 || !!lv2
      h += `<div class="pv-tagrow"><span class="pv-no">${index + 1}.</span><span class="pv-tag">${
        hasTypeTree ? esc(lv1 || LEVEL_NONE) : TYPE_LABEL[q.type]
      }</span>`
      if (hasTypeTree) h += `<span class="pv-tag lv2">${esc(lv2 || LEVEL_NONE)}</span>`
      h += `<span class="pv-acts">${noteActionHtml(!!q.note?.trim())}</span>`
      h += '</div>'

      h += `<div class="pv-stem">${renderStem(q.stem) || '<span class="pv-ph">（题干为空）</span>'}</div>`

      // 选项按题型走共用渲染：多选方徽标、判断勾叉。正确答案用「绿描边」标出来
      // （粉笔解析态里「该选但没选」的那种写法），填空 / 主观题没有选项
      h += optionListHtml(q, { keys: normKeys(q.type, q.answer), userKeys: [] })

      // 答案宫格：选择题给「正确答案 / 分值」；填空与主观题的参考答案常常成段，
      // 走 long 格整段铺开，别塞进 17px 的宫格里挤成一条
      const score = typeof q.score === 'string' ? q.score.trim() : String(q.score ?? '').trim()
      const ans = answerText(q.type, q.answer)
      const cells: GridCell[] = []
      if (isBlank(q.type)) {
        cells.push({
          k: '参考答案',
          v: ans ? renderStem(ans) : '<span class="pv-pending">待补</span>',
          cls: 'g',
          long: true,
        })
        if (score && Number(score) > 0) cells.push({ k: '满分', v: esc(score), cls: 'b' })
      } else {
        cells.push({
          k: '正确答案',
          v: ans ? esc(ans) : '<span class="pv-pending">待补</span>',
          cls: 'g',
        })
        if (score && Number(score) > 0) cells.push({ k: '分值', v: esc(score), cls: 'b' })
      }
      h += gridHtml(cells)

      // 解析盒：点标题行收起 / 展开（箭头跟着翻）；没有解析就不出这个盒
      if (q.analysis.trim()) {
        h += '<div class="sol-box" data-sol>' +
          '<div class="sol-h"><span class="bar"></span><span class="t">解析</span><span class="chev">❯</span></div>' +
          `<div class="sol-body">${renderStem(q.analysis)}</div>` +
          '</div>'
      }
      if (q.knowledge.trim()) {
        h += `<div class="kp-row"><span class="kp">${esc(q.knowledge)}</span></div>`
      }
      // 有笔记才铺这一条；没笔记就靠题号行那支笔，别在整卷上铺一堆空行
      if (q.note?.trim()) {
        h += '<div class="note-row" data-note-row>' +
          '<span class="nl">笔记</span>' +
          `<span class="nb">${esc(q.note)}</span>` +
          '</div>'
      }
      h += '</div>'
      return h
    }

    /** 材料题：材料卡 + 它的小题收进同一个组，头部标出「第 N–M 题」 */
    const html = (): string => {
      const qs = currentQuestions()
      if (!qs.length) return '<div class="pv-empty">这份卷还没有题目</div>'
      const groups = groupsOf(qs)
      const missing = qs.filter((q) => !q.answer).length
      let out = '<div class="pv-head"><span class="pv-head-t">答案解析</span>' +
        `<span class="pv-head-n">${qs.length} 题${
          missing ? ` · ${missing} 题待补答案` : ' · 答案齐全'
        }</span></div>`
      let i = 0
      while (i < qs.length) {
        const g = groups.find((x) => x.start === i)
        if (g) {
          out += props.full
            ? splitHtml(g, qs)
            : materialGroupHtml({
              start: g.start + 1,
              count: g.end - g.start + 1,
              materialHtml: renderMaterial(g.material),
              questionsHtml: qs
                .slice(g.start, g.end + 1)
                .map((q, k) => blockHtml(q, g.start + k))
                .join(''),
            })
          i = g.end + 1
        } else {
          out += blockHtml(qs[i], i)
          i += 1
        }
      }
      return out
    }

    /**
     * 整页里的资料分屏，照粉笔解析页的实测版式：一张 700 高的大卡切成两半——
     * 左边「材料」白卡（自己的滚动条），右边顶上一排题号页签（106题 107题…，
     * 当前题下面一条主色短杠），下面只摆当前这一题（题干 / 选项 / 答案宫格 /
     * 解析 / 考点）。五道小题不再竖着堆，一屏只读一道，材料始终在左手边。
     */
    const splitHtml = (g: Group, qs: Question[]): string => {
      const count = g.end - g.start + 1
      const start = g.start + 1
      const active = Math.min(tabIdx.value[g.start] ?? 0, count - 1)
      const tabs = Array.from({ length: count }, (_, i) =>
        `<button class="pv-tab${i === active ? ' active' : ''}" data-tab="${i}">${start + i}题</button>`,
      ).join('')
      const w = matWidth.value[g.start] ?? 47
      return (
        `<div class="pv-split" data-g="${g.start}" style="--mat-w:${w}%">` +
        '<div class="pv-split-mat">' +
        '<div class="pv-mat-tag">材料</div>' +
        `<div class="pv-mat-body">${renderMaterial(g.material)}</div>` +
        '</div>' +
        `<div class="pv-split-grip" data-grip="${g.start}" title="拖动调整材料宽度"></div>` +
        '<div class="pv-split-qs">' +
        `<div class="pv-tabs">${tabs}</div>` +
        `<div class="pv-tabbody">${blockHtml(qs[g.start + active], g.start + active)}</div>` +
        '</div>' +
        '</div>'
      )
    }

    const onClick = (e: MouseEvent) => {
      const target = e.target as HTMLElement
      // 资料分屏的题号页签：只换右栏摆哪道题，不跳左栏
      const tab = target.closest('[data-tab]') as HTMLElement | null
      if (tab) {
        const split = tab.closest('.pv-split') as HTMLElement | null
        const g = Number(split?.dataset.g)
        if (Number.isFinite(g)) tabIdx.value[g] = Number(tab.dataset.tab)
        return
      }
      // 资料头的「收起 / 展开」：收的是材料正文，小题留着（对着材料做题时更顺手）
      const fold = target.closest('.mfold') as HTMLElement | null
      if (fold) {
        fold.closest('.pv-mat')?.classList.toggle('collapsed')
        return
      }
      // 笔记入口（题号行那支笔）与笔记条：打开笔记弹框，不触发跳题
      const noteHit = target.closest('[data-act="note"], [data-note-row]') as HTMLElement | null
      if (noteHit) {
        const card = noteHit.closest('.pv-q') as HTMLElement | null
        const uid = Number(card?.dataset.uid)
        const qs = currentQuestions()
        const index = qs.findIndex((q) => q._uid === uid)
        if (index >= 0) void editNote(qs[index], `第 ${index + 1} 题`)
        return
      }
      // 解析盒标题行：收起 / 展开，不触发下面的跳题
      const solHead = target.closest('.sol-h') as HTMLElement | null
      if (solHead) {
        solHead.closest('.sol-box')?.classList.toggle('collapsed')
        return
      }
      const hit = target.closest('.pv-q') as HTMLElement | null
      if (!hit) return
      const uid = Number(hit.dataset.uid)
      const index = currentQuestions().findIndex((q) => q._uid === uid)
      if (index >= 0) focusQuestion(index)
    }

    /** 拖动两栏之间的把手：改这一组左栏的百分比宽度（32%–68%，别把另一边挤没） */
    const onPointerDown = (e: PointerEvent) => {
      const grip = (e.target as HTMLElement).closest('[data-grip]') as HTMLElement | null
      if (!grip || e.button !== 0) return
      const split = grip.closest('.pv-split') as HTMLElement | null
      const g = Number(split?.dataset.g)
      if (!split || !Number.isFinite(g)) return
      e.preventDefault()
      const rect = split.getBoundingClientRect()
      const move = (ev: PointerEvent) => {
        const pct = ((ev.clientX - rect.left) / rect.width) * 100
        matWidth.value[g] = Math.min(68, Math.max(32, Math.round(pct)))
      }
      const up = () => {
        window.removeEventListener('pointermove', move)
        window.removeEventListener('pointerup', up)
        window.removeEventListener('pointercancel', up)
        document.body.classList.remove('split-dragging')
      }
      document.body.classList.add('split-dragging')
      window.addEventListener('pointermove', move)
      window.addEventListener('pointerup', up)
      window.addEventListener('pointercancel', up)
    }

    return () => (
      <>
        <div class="ppane" ref={pane} innerHTML={html()} onClick={onClick} onPointerdown={onPointerDown}></div>
        {/* 解析页也有同一条答题卡：整卷滚到哪儿都能一步跳到某一题（分屏组会顺手切页签）。
            这里没有作答进度，收起时只写题数；答案齐没齐页头已经写了。 */}
        {props.full ? <PaperDock progress={false} hint={false} /> : null}
      </>
    )
  },
})
