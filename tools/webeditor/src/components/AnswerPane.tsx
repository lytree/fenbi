import { defineComponent, nextTick, onMounted, ref, watch } from 'vue'
import type { Question } from '../types'
import { currentQuestions, editNote, focusQuestion, groupsOf, state, TYPE_LABEL } from '../store'
import { esc } from '../util'
import { renderMaterial, renderStem } from '../markdown'
import { LEVEL_NONE, level1Of, level2Of } from '../xingce'
import { answerText, gridHtml, isBlank, materialGroupHtml, noteActionHtml, normKeys, optionListHtml } from '../qview'
import type { GridCell } from '../qview'

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

    /** 把当前选中（state.focused）的那题滚到面板中间 */
    const scrollToFocused = (smooth: boolean) => {
      const uid = state.focused
      if (uid === null) return
      const hit = pane.value?.querySelector(`.pv-q[data-uid="${uid}"]`) as HTMLElement | null
      hit?.scrollIntoView({ block: 'center', behavior: smooth ? 'smooth' : 'auto' })
    }

    watch(() => state.focused, () => { void nextTick(() => scrollToFocused(true)) })
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
          out += materialGroupHtml({
            start: g.start + 1,
            count: g.end - g.start + 1,
            materialHtml: renderMaterial(g.material),
            questionsHtml: qs
              .slice(g.start, g.end + 1)
              .map((q, k) => blockHtml(q, g.start + k))
              .join(''),
            // 整页查看里这组左右分屏：材料在左、题目在右；窄栏里还是上下排
            split: props.full,
          })
          i = g.end + 1
        } else {
          out += blockHtml(qs[i], i)
          i += 1
        }
      }
      return out
    }

    const onClick = (e: MouseEvent) => {
      const target = e.target as HTMLElement
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

    return () => (
      <div class="ppane" ref={pane} innerHTML={html()} onClick={onClick}></div>
    )
  },
})
