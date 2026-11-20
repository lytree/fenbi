import { defineComponent, onBeforeUnmount, onMounted, ref } from 'vue'
import type { PropType } from 'vue'
import type { ExamRecordInfo, RecordAnswer } from '../types'
import { TYPE_SHORT, loadRecordDetail, loadRecords, records } from '../store'
import { formatTime } from '../util'
import { renderMaterial, renderRich, renderStem } from '../markdown'

/**
 * 练习记录（App 的「记录」页）—— **只读回看**。
 *
 * 这里不筛不翻页：一进来就把手机上的全部记录连同每条的逐题明细铺开，
 * 每题直接给出「正确答案 / 我的选择 / 解析」。网页上做回看不需要先点进详情页
 * 再逐级展开，能一眼扫到当时错在哪更有用。
 */

/** 判断题的「对 / 错」两侧：库里答案存成「正确 / 错误」，选项键却是 A / B / T / F */
const TRUE_WORDS = ['A', 'T', 'TRUE', 'Y', 'YES', '正确', '对', '√', '1']
const FALSE_WORDS = ['B', 'F', 'FALSE', 'N', 'NO', '错误', '错', '×', '0']

/** 与 App 的 `answerForEditor` 同一口径：判断题先归一成 T / F，别的题型原样返回 */
function judgeSide(s: string): 'T' | 'F' | '' {
  const v = (s || '').trim().toUpperCase()
  if (TRUE_WORDS.includes(v)) return 'T'
  if (FALSE_WORDS.includes(v)) return 'F'
  return ''
}

/** 答案串 → 键集合（"A,C" / "A C" / "A、C" 都认，和 App 判题的分隔符一致） */
function keysOf(s: string): string[] {
  return (s || '')
    .split(/[,，、;；|\s]+/)
    .map((x) => x.trim().toUpperCase())
    .filter(Boolean)
}

/** 这个选项是不是正确答案 / 是不是我选的 */
function optionState(q: RecordAnswer, key: string): { right: boolean; mine: boolean } {
  if (q.type === 'truefalse') {
    const side = judgeSide(key)
    if (!side) return { right: false, mine: false }
    return { right: side === judgeSide(q.answer), mine: side === judgeSide(q.userAnswer) }
  }
  const k = key.trim().toUpperCase()
  return { right: keysOf(q.answer).includes(k), mine: keysOf(q.userAnswer).includes(k) }
}

/** 12.0 → "12"，7.5 → "7.5" */
function fmtScore(n: number): string {
  return Number.isFinite(n) ? String(Math.round(n * 100) / 100) : '0'
}

/** 与 App 记录列表同一种写法：1小时20分 / 3分15秒 / 42秒 */
function fmtDuration(sec: number): string {
  if (!sec || sec <= 0) return '0秒'
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  if (h > 0) return `${h}小时${m}分`
  if (m > 0) return `${m}分${s}秒`
  return `${s}秒`
}

/** 记录标题：与 App 的 `ExamRecord.displayTitle` 同一规则（分模块记录用更长的记录名） */
function recordTitle(r: ExamRecordInfo): string {
  const paper = (r.paperName || '').trim()
  const name = (r.name || '').trim()
  if (!paper) return name || '练习记录'
  if (name.length > paper.length && name.startsWith(paper)) return name
  return paper
}

function statusText(r: ExamRecordInfo): string {
  if (r.status === 'done') return '已完成'
  return r.status === 'ongoing' ? '进行中' : '已中断'
}

/** 单题：题干（含材料）+ 选项高亮 + 答案 / 我的选择 / 解析 */
const RecordQuestion = defineComponent({
  name: 'RecordQuestion',
  props: {
    answer: { type: Object as PropType<RecordAnswer>, required: true },
    /** 上一题的材料：同一组材料连着出现的题只渲染一次，后面写「材料同上」 */
    sameAsPrev: { type: Boolean, default: false },
    prevNo: { type: Number, default: 0 },
  },
  setup(props) {
    const verdict = (q: RecordAnswer) => {
      if (q.pending) return { text: '待补答案', cls: 'meta' }
      if (!q.userAnswer.trim()) return { text: '未作答', cls: 'meta' }
      if (q.correct) return { text: '答对', cls: 'ok' }
      if (q.partial) return { text: `部分得分 ${fmtScore(q.earned)}/${fmtScore(q.full)}`, cls: 'part' }
      return { text: '答错', cls: 'bad' }
    }

    return () => {
      const q = props.answer
      const v = verdict(q)
      const typeName = (TYPE_SHORT as Record<string, string>)[q.type] ?? q.type
      const options = (q.options ?? []).filter((o) => o.text.trim() || o.key.trim())
      const mineCls = q.pending || !q.userAnswer.trim()
        ? 'meta'
        : q.correct ? 'ok' : q.partial ? 'part' : 'bad'
      const material = (q.material || '').trim()

      return (
        <article class={['rec-q', v.cls]}>
          <div class="rec-qhead">
            <span class="rec-qno">{q.no}</span>
            <span class="tag">{typeName}</span>
            <span class={['rec-verdict', v.cls]}>{v.text}</span>
            {q.knowledge ? <span class="rec-know">{q.knowledge}</span> : null}
          </div>

          {material && !props.sameAsPrev
            ? <div class="rec-material" innerHTML={renderMaterial(material)} />
            : null}
          {material && props.sameAsPrev
            ? <div class="rec-same">材料同第 {props.prevNo} 题</div>
            : null}

          {q.stem.trim() ? <div class="rec-stem" innerHTML={renderStem(q.stem)} /> : null}

          {options.length
            ? (
              <div class="rec-opts">
                {options.map((o) => {
                  const st = optionState(q, o.key)
                  return (
                    <div
                      key={o.key}
                      class={['rec-opt', st.right && 'right', st.mine && !st.right && 'wrong', st.mine && st.right && 'mine']}
                    >
                      <span class="rec-opt-k">{o.key}</span>
                      <span class="rec-opt-t" innerHTML={renderRich(o.text)} />
                      {st.mine ? <span class="rec-opt-badge">我的选择</span> : null}
                    </div>
                  )
                })}
              </div>
            )
            : null}

          <div class="rec-ans">
            <div class="rec-ans-cell">
              <span class="k">正确答案</span>
              <span class="v ok">{q.answer.trim() || '（待补）'}</span>
            </div>
            <div class="rec-ans-cell">
              <span class="k">我的选择</span>
              <span class={['v', mineCls]}>{q.userAnswer.trim() || '未作答'}</span>
            </div>
          </div>

          {q.analysis.trim()
            ? (
              <div class="rec-ana">
                <div class="rec-ana-head">解析</div>
                <div class="rec-ana-body" innerHTML={renderRich(q.analysis, 'rec-ana-p')} />
              </div>
            )
            : null}
        </article>
      )
    }
  },
})

/** 一条记录：头（卷名 / 时间 / 用时 / 得分）+ 全部逐题明细 */
const RecordItem = defineComponent({
  name: 'RecordItem',
  props: { record: { type: Object as PropType<ExamRecordInfo>, required: true } },
  setup(props) {
    const root = ref<HTMLElement | null>(null)
    let io: IntersectionObserver | null = null

    onMounted(() => {
      const id = props.record.id
      // 记录可能有几百条：一进来就把每条的逐题明细都拉一遍，会把手机的局域网服务占满，
      // 首屏也要等上千道题。卡片快进视野（提前 800px）时才取，滚到哪儿哪儿就是满的。
      if (typeof IntersectionObserver === 'undefined') {
        void loadRecordDetail(id)
        return
      }
      io = new IntersectionObserver(
        (entries) => {
          if (!entries.some((e) => e.isIntersecting)) return
          void loadRecordDetail(id)
          io?.disconnect()
          io = null
        },
        { rootMargin: '800px 0px' },
      )
      if (root.value) io.observe(root.value)
    })

    onBeforeUnmount(() => {
      io?.disconnect()
      io = null
    })

    return () => {
      const r = props.record
      const slot = records.details[r.id]
      const detail = slot?.data
      const answers = detail?.answers ?? []
      const rate = r.answered > 0 ? Math.round((r.correct * 100) / r.answered) : 0

      // 相邻题共用材料时只渲染一次：资料分析一组五六道题挂同一段材料，
      // 每道都铺一遍会把整页撑成几倍长
      let prevMaterial = ''
      let prevNo = 0
      const rows = answers.map((a) => {
        const m = (a.material || '').trim()
        const sameAsPrev = !!m && m === prevMaterial
        if (m) {
          prevMaterial = m
          prevNo = sameAsPrev ? prevNo : a.no
        } else {
          // 中间隔了一道没有材料的题就不再算「同上」，免得读者往前找错地方
          prevMaterial = ''
        }
        return { a, sameAsPrev, prevNo }
      })

      return (
        <article ref={root} class={['rec-item', r.status === 'done' && 'done']}>
          <header class="rec-head">
            <div class="rec-head-main">
              <div class="rec-title">
                <span class="rec-name">{recordTitle(r)}</span>
                <span class={['tag', r.status === 'done' ? 'rec-done' : 'rec-undone']}>{statusText(r)}</span>
              </div>
              <div class="rec-meta">
                {formatTime(r.startedAt)}
                {' · 用时 '}
                {fmtDuration(r.durationSec)}
                {' · 答 '}
                {r.answered}/{r.total}
                {r.status === 'done'
                  ? ` · 正确率 ${rate}%${r.partial > 0 ? ` · 部分得分 ${r.partial} 题` : ''}`
                  : ''}
              </div>
            </div>
            <div class="rec-score">
              {r.status === 'done' ? fmtScore(r.score) : '—'}
              {r.status === 'done' && r.fullScore > 0
                ? <span class="rec-full">/{fmtScore(r.fullScore)}</span>
                : null}
            </div>
          </header>

          {slot?.loading && !detail ? <div class="rec-loading">读取作答明细…</div> : null}
          {slot?.error ? (
            <div class="rec-error">
              {slot.error}
              <button class="link-btn" onClick={() => void loadRecordDetail(r.id, true)}>重试</button>
            </div>
          ) : null}
          {detail && !answers.length ? <div class="rec-loading">这条记录里没有作答明细</div> : null}

          {rows.map(({ a, sameAsPrev, prevNo }) => (
            <RecordQuestion key={`${r.id}-${a.no}`} answer={a} sameAsPrev={sameAsPrev} prevNo={prevNo} />
          ))}
        </article>
      )
    }
  },
})

export default defineComponent({
  name: 'RecordPane',
  setup() {
    // 进入这一栏就补齐明细（switchMode 里已经拉过一次，这里只兜「直接停在记录页」的情况）
    onMounted(() => void loadRecords())

    return () => {
      const list = records.list
      const shown = list.length

      return (
        <div class="records-pane">
          <div class="rec-tip">
            <span>
              手机里的全部练习记录，每题直接给出正确答案、我的选择和解析。
              {records.total > shown ? `（共 ${records.total} 条，这里显示最近 ${shown} 条）` : ''}
            </span>
            <button class="link-btn" disabled={records.loading} onClick={() => void loadRecords(true)}>
              {records.loading ? '刷新中…' : '刷新'}
            </button>
          </div>

          {!list.length && records.loading ? <div class="pv-empty">读取记录中…</div> : null}
          {!list.length && !records.loading ? (
            <div class="pv-empty">
              还没有练习记录
              <br />
              在 App 里做一次题，这里就会出现那一场的选择、答案和解析
            </div>
          ) : null}

          {list.map((r) => <RecordItem key={r.id} record={r} />)}
        </div>
      )
    }
  },
})
