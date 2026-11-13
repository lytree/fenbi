import { defineComponent, reactive } from 'vue'
import QuestionCard from './QuestionCard'
import type { Group } from '../types'
import {
  addQuestionToGroup, currentQuestions, focusQuestion, groupAt, refreshGroups,
  setGroupMaterial, state, visibleIndexes,
} from '../store'
import { grow, insertAtCursor } from '../util'
import { FIGURE_LAYOUTS, wrapFigure } from '../figure'
import { insertPastedImage, pickImage } from '../images'
import { renderMaterial } from '../markdown'

/** 材料输入框：按组首下标记，排版按钮要用它的光标位置包块 */
const areas = new Map<number, HTMLTextAreaElement>()

/**
 * 材料模式：以「一片材料 → 多道小题」为单位编辑，而不是按题平铺。
 *
 * 数据口径不变：材料仍然逐行复制到组内每道题（App 靠连续且相同的 material 派生材料组），
 * 这里只是把「改材料」和「改小题」放在同一个块里，省得在列表里上下翻找组头。
 * 独立题（没有材料）照常夹在卷面顺序里，不会被藏起来。
 */
export default defineComponent({
  name: 'MaterialList',
  setup() {
    /** 排版预览是纯查看，按组首记展开状态即可，不进草稿 */
    const previewOpen = reactive(new Set<number>())

    const togglePreview = (start: number) => {
      if (previewOpen.has(start)) previewOpen.delete(start)
      else previewOpen.add(start)
    }

    /** 材料正文以题上的实际值为准：连续输入时分组还没重算，组快照里的 material 是旧的 */
    const materialText = (g: Group) => currentQuestions()[g.start]?.material ?? g.material

    const applyFigure = (g: Group, layout: string) => {
      const el = areas.get(g.start)
      const text = materialText(g)
      const from = el ? el.selectionStart : text.length
      const to = el ? el.selectionEnd : text.length
      setGroupMaterial(g.start, wrapFigure(text, from, to, layout))
    }

    const addImage = (start: number) => {
      pickImage((path) => {
        const el = areas.get(start)
        if (el) {
          insertAtCursor(el, `![](${path})`)
          return
        }
        const g = state.groups.find((x) => x.start === start)
        if (g) setGroupMaterial(start, `${materialText(g)}\n![](${path})`)
      })
    }

    /** 加完小题滚到那张卡：组里题一多，新题会落在块底部、看不见 */
    const addSub = (start: number) => {
      const q = addQuestionToGroup(start)
      if (!q) return
      const at = currentQuestions().findIndex((x) => x._uid === q._uid)
      if (at >= 0) focusQuestion(at)
    }

    return () => {
      if (!state.paper) return null
      const qs = currentQuestions()
      if (!qs.length) {
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

      const nodes: any[] = [
        <div class="mat-hint">
          按材料成块编辑：上方改材料（组内小题自动同步），下方加 / 改小题。发布时材料仍写进每道小题。
        </div>,
      ]
      const done = new Set<number>()
      let materialNo = 0

      for (const i of indexes) {
        if (done.has(i)) continue
        const g = groupAt(state.groups, i)

        if (!g) {
          done.add(i)
          nodes.push(
            <section class="mat-solo" key={`solo-${qs[i]._uid}`}>
              <div class="mb-head">
                <span class="ms-no">独立题目</span>
                <span class="mb-meta">第 {i + 1} 题 · 无材料</span>
              </div>
              <QuestionCard question={qs[i]} index={i} group={null} />
            </section>,
          )
          continue
        }

        materialNo += 1
        const members = indexes.filter((x) => x >= g.start && x <= g.end)
        members.forEach((x) => done.add(x))

        nodes.push(
          <section class="mat-block" key={`mat-${g.start}`}>
            <div class="mb-head">
              <span class="mb-no">材料 {materialNo}</span>
              <span class="mb-meta">
                第 {g.start + 1}–{g.end + 1} 题 · {g.end - g.start + 1} 题共用
              </span>
              {members.length < g.end - g.start + 1
                ? <span class="mb-meta sift">筛出 {members.length} 题</span>
                : null}
              <span class="grow"></span>
              <button class="ghost sm" onClick={() => togglePreview(g.start)}>
                {previewOpen.has(g.start) ? '收起预览' : '排版预览'}
              </button>
              <button
                class="primary sm"
                title="在这则材料的末尾加一道小题，材料自动共用"
                onClick={() => addSub(g.start)}
              >
                ＋ 加小题
              </button>
            </div>

            <textarea
              class="mb-text"
              rows={6}
              spellcheck={false}
              value={materialText(g)}
              ref={(el: any) => {
                if (el) areas.set(g.start, el as HTMLTextAreaElement)
                else areas.delete(g.start)
                grow(el as HTMLTextAreaElement)
              }}
              placeholder="材料正文：支持 Markdown 表格、图片、:::chart、:::figure 图文排版"
              onInput={(e) => {
                const el = e.target as HTMLTextAreaElement
                // 先只改题上的文本；分组等失焦再重算，避免删空时整块消失
                setGroupMaterial(g.start, el.value, false)
                grow(el)
              }}
              onBlur={() => refreshGroups()}
              onPaste={(e) => insertPastedImage(e as ClipboardEvent, e.target as HTMLTextAreaElement)}
            />

            <div class="mb-tools">
              <span class="t">图文排版</span>
              {FIGURE_LAYOUTS.map(([key, label]) => (
                <button
                  key={key}
                  title={`把选中的图文包成 :::figure（layout: ${key}）`}
                  onClick={() => applyFigure(g, key)}
                >
                  {label}
                </button>
              ))}
              <button title="把图片插入光标处" onClick={() => addImage(g.start)}>插图</button>
              <span class="grow"></span>
              <span class="mini-hint">选中要对排的文字再点排版；截图可直接 Ctrl+V</span>
            </div>

            {previewOpen.has(g.start)
              ? <div class="mb-preview" innerHTML={renderMaterial(materialText(g))} />
              : null}

            <div class="mb-subhead">
              <span class="t">小题 {members.length} 道</span>
              <span class="mini-hint">材料只在这里维护，发布时写进组内每道小题</span>
            </div>

            {members.map((x) => (
              <QuestionCard key={qs[x]._uid} question={qs[x]} index={x} group={g} />
            ))}
          </section>,
        )
      }

      return nodes
    }
  },
})
