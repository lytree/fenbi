import { defineComponent, ref } from 'vue'
import type { PropType } from 'vue'
import { api } from '../api'
import type { RecordAnswer, RecordDetail, RecordInfo } from '../api'
import { currentSheet, editNote, state, TYPE_LABEL } from '../store'
import { esc, formatTime } from '../util'
import { renderMaterial, renderStem } from '../markdown'
import { openFullPage } from '../fullview'
import { answerText, gridHtml, isBlank, materialCardHtml, normKeys, optionListHtml } from '../qview'
import type { GridCell } from '../qview'

/**
 * 右栏「记录」页：全库的作答记录，按记录逐条成组展示。
 *
 * 两层视图：
 * - 列表：每条记录一张卡（练习名 + 状态 + 统计条），点进详情；
 * - 详情：粉笔解析页的复刻——得分卡（大号蓝分 + 总用时）→「考试情况」按题型分行
 *   → 答题卡色块 → 逐题解析（顶部对错色条、选项三态、灰底宫格条、折叠解析、
 *   考点胶囊、笔记行），资料分析的材料单独成卡放在小题前面。
 *
 * 版式跟「卷面预览 / 答案解析」同一套：页头用 `.pv-head`、题目卡用 `.pv-q`、
 * 选项用 `.pv-opt`、解析块用 `.sol-box`——三页来回切不会觉得换了套皮，
 * 明暗两套主题也跟着令牌一起翻。
 *
 * 记录是只读的：这里只回看，批改仍然在 App / 对话里做。
 */

const STATUS_LABEL: Record<string, string> = {
  done: '已交卷',
  ongoing: '进行中',
  abandoned: '已放弃',
}

const statusLabel = (s: string) => STATUS_LABEL[s] || s

const fmtDuration = (sec: number): string => {
  if (!sec || sec <= 0) return '—'
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  const p = (n: number) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${p(m)}:${p(s)}` : `${p(m)}:${p(s)}`
}

const accuracy = (r: { total: number; correct: number }): number =>
  r.total > 0 ? Math.round((r.correct / r.total) * 100) : 0

const typeLabel = (t: string): string =>
  (TYPE_LABEL as Record<string, string>)[t] || t

/**
 * 「你的答案」那一栏按结果上色：对了绿、错了红、待批 / 部分对用橙、没答灰。
 * 以前这里固定红色，答对了也红着一行，看着像判错了。
 */
const userCls = (result: string): string =>
  result === 'ok' ? 'ok' : result === 'no' ? 'no' : result === 'none' ? 'none' : 'pend'

export default defineComponent({
  name: 'RecordsPane',
  props: {
    /** 单独查看页带过来的记录 id：进来直接落到这条记录的详情 */
    initialRecordId: { type: Number, default: 0 },
    /** 已经在整页里了：就别再给一个「在新页面查看」 */
    standalone: { type: Boolean, default: false },
    /** 整页版用它把「当前看的是哪条记录」写回地址（0 = 在列表） */
    onRecordChange: { type: Function as PropType<(id: number) => void>, default: null },
  },
  setup(props) {
    const records = ref<RecordInfo[]>([])
    const total = ref(0)
    const loading = ref(false)
    const loadError = ref('')

    /** 非空 = 当前在详情视图，值就是那条记录的完整明细 */
    const detail = ref<RecordDetail | null>(null)
    const detailLoading = ref(false)

    const load = async () => {
      loading.value = true
      loadError.value = ''
      try {
        const res = await api.records()
        records.value = res.records
        total.value = res.total
      } catch (e) {
        loadError.value = e instanceof Error ? e.message : '读取记录失败'
      } finally {
        loading.value = false
      }
    }

    const openDetail = async (id: number) => {
      detailLoading.value = true
      try {
        detail.value = await api.record(id)
        props.onRecordChange?.(id)
      } catch (e) {
        loadError.value = e instanceof Error ? e.message : '读取明细失败'
      } finally {
        detailLoading.value = false
      }
    }

    const boot = async () => {
      await load()
      if (props.initialRecordId > 0) await openDetail(props.initialRecordId)
    }
    void boot()

    const back = () => {
      detail.value = null
      loadError.value = ''
      props.onRecordChange?.(0)
    }

    /** 整页看：列表页 / 当前这条记录，地址里带全，刷新也停在原地 */
    const openInPage = (recordId = 0) => {
      openFullPage('records', {
        setId: state.paper?.id ?? 0,
        sheetId: currentSheet()?.id ?? 0,
        recordId,
      })
    }

    /** 答题卡色块：答对绿 / 答错红 / 没答灰 / 待批橙 */
    const cellClass = (a: RecordAnswer): string =>
      a.pending ? 'pend' : !a.userAnswer.trim() ? 'none' : a.correct ? 'ok' : 'no'

    /** 统计条：一共 / 答对 / 答错 / 正确率 / 总用时（+得分，卷面带分值才有意义） */
    const StatsBar = ({ r }: { r: RecordInfo }) => {
      const cells: { k: string; v: string; unit?: string; cls: string }[] = [
        { k: '一共', v: String(r.total), unit: '题', cls: 'c-primary' },
        { k: '答对', v: String(r.correct), unit: '题', cls: 'c-ok' },
        { k: '答错', v: String(Math.max(r.total - r.correct - (r.partial || 0), 0)), unit: '题', cls: 'c-no' },
        { k: '正确率', v: String(accuracy(r)), unit: '%', cls: 'c-primary' },
        { k: '总用时', v: fmtDuration(r.durationSec), cls: 'c-primary' },
      ]
      if (r.fullScore > 0) cells.push({ k: '得分', v: String(r.score), unit: `/${r.fullScore}`, cls: 'c-ok' })
      return (
        <div class="rec-stats">
          {cells.map((c) => (
            <div class="stat" key={c.k}>
              <div class="k">{c.k}</div>
              <div class={['v', c.cls]}>
                {c.v}
                {c.unit ? <i>{c.unit}</i> : null}
              </div>
            </div>
          ))}
        </div>
      )
    }

    /** 折叠解析盒：点标题行收起 / 展开（箭头跟着翻） */
    const toggleSol = (e: MouseEvent) => {
      ;(e.currentTarget as HTMLElement).closest('.sol-box')?.classList.toggle('collapsed')
    }

    /**
     * 逐题解析里的一道题：粉笔解析页的版式，六种题型同一套骨架。
     * `material` 由外面的资料分组渲染（材料卡在小题前面），这里不再重复铺一遍。
     */
    const QuestionBlock = ({ a }: { a: RecordAnswer }) => {
      const keys = normKeys(a.type, a.answer)
      const userKeys = normKeys(a.type, a.userAnswer)
      const blank = isBlank(a.type)
      const result = a.pending
        ? { cls: 'pend', text: '待批改' }
        : !a.userAnswer.trim()
          ? { cls: 'none', text: '未作答' }
          : a.partial
            ? { cls: 'part', text: '部分正确' }
            : a.correct
              ? { cls: 'ok', text: '答对' }
              : { cls: 'no', text: '答错' }

      // 选择题两格：正确答案 / 你的答案（按对错着色）；填空与主观题把两边都整段铺开
      const cells: GridCell[] = []
      const std = answerText(a.type, a.answer)
      if (blank) {
        cells.push({
          k: '你的答案',
          v: a.userAnswer.trim() ? renderStem(a.userAnswer) : '<span class="pv-ph">未作答</span>',
          cls: userCls(result.cls),
          long: true,
        })
        cells.push({
          k: '参考答案',
          v: std ? renderStem(std) : '<span class="pv-pending">待补</span>',
          cls: 'g',
          long: true,
        })
      } else {
        cells.push({ k: '正确答案', v: std ? esc(std) : '—', cls: 'g' })
        cells.push({
          k: '你的答案',
          v: a.userAnswer.trim() ? esc(answerText(a.type, a.userAnswer)) : '未作答',
          cls: userCls(result.cls),
        })
      }

      return (
        <div class={['pv-q', 'static', `r-${result.cls}`]}>
          <div class="pv-tagrow">
            <span class="pv-no">{a.no}.</span>
            <span class="pv-tag">{typeLabel(a.type)}</span>
            {a.full > 0
              ? <span class="rec-score">{a.pending ? `0/${a.full} 分` : `${a.earned}/${a.full} 分`}</span>
              : null}
            <span class={['rec-result', result.cls]}>{result.text}</span>
            {/* 粉笔卡片右上角那支笔：笔记入口，有笔记时染蓝 */}
            <span class="pv-acts">
              <button
                class={['pv-act', !!a.note?.trim() && 'on']}
                title={a.note?.trim() ? '编辑笔记' : '写笔记'}
                aria-label="笔记"
                onClick={(e) => { e.stopPropagation(); void editNote(a, `第 ${a.no} 题`) }}
              >
                <svg viewBox="0 0 16 16" width="13" height="13" aria-hidden="true">
                  <path
                    d="M11.4 2.1a1.4 1.4 0 0 1 2 0l.5.5a1.4 1.4 0 0 1 0 2L5.6 12.9l-3.1.8.8-3.1 8.1-8.5Z"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.3"
                    stroke-linejoin="round"
                  />
                </svg>
              </button>
            </span>
          </div>

          <div class="pv-stem" innerHTML={renderStem(a.stem) || '<span class="pv-ph">（题干为空）</span>'}></div>

          {/* 选项按题型走共用渲染：多选方徽标、判断勾叉；填空 / 主观题没有选项，
              作答内容在下面的宫格条里（你的答案 / 参考答案） */}
          {a.options?.some((o) => o.text.trim())
            ? <div innerHTML={optionListHtml(a, { keys, userKeys })}></div>
            : null}

          <div innerHTML={gridHtml(cells)}></div>

          {a.analysis
            ? (
              <div class="sol-box">
                <div class="sol-h" onClick={toggleSol}>
                  <span class="bar"></span>
                  <span class="t">解析</span>
                  <span class="chev">❯</span>
                </div>
                <div class="sol-body" innerHTML={renderStem(a.analysis)}></div>
              </div>
            )
            : null}
          {a.knowledge
            ? <div class="kp-row"><span class="kp">{a.knowledge}</span></div>
            : null}
          {a.note?.trim()
            ? (
              <div class="note-row" title="点击编辑笔记" onClick={() => void editNote(a, `第 ${a.no} 题`)}>
                <span class="nl">笔记</span>
                <span class="nb">{a.note}</span>
              </div>
            )
            : null}
        </div>
      )
    }

    /**
     * 资料分析的题目形式：连续同材料的小题收进一个 `.pv-group`——材料卡在上、
     * 小题跟在下、左轨连着，头部标出「第 6–7 题」。和卷面 / 解析两页同一个结构，
     * 只是这里的小题带作答结果。
     */
    type DetailBlock =
      | { kind: 'group'; material: string; start: number; count: number; answers: RecordAnswer[] }
      | { kind: 'q'; a: RecordAnswer }

    const detailBlocks = (answers: RecordAnswer[]): DetailBlock[] => {
      const out: DetailBlock[] = []
      let i = 0
      while (i < answers.length) {
        const a = answers[i]
        if (a.material.trim()) {
          let j = i
          while (j < answers.length && answers[j].material === a.material) j++
          const group = answers.slice(i, j)
          out.push({
            kind: 'group',
            material: a.material,
            start: group[0].no,
            count: group.length,
            answers: group,
          })
          i = j
        } else {
          out.push({ kind: 'q', a })
          i++
        }
      }
      return out
    }

    /** 资料头的收起 / 展开：只收材料正文，小题留着 */
    const onGroupClick = (e: MouseEvent) => {
      const fold = (e.target as HTMLElement).closest('.mfold')
      if (!fold) return
      ;(e.currentTarget as HTMLElement).querySelector('.pv-mat')?.classList.toggle('collapsed')
    }

    /** 「考试情况」：粉笔报告里的模块行——本卷按题型聚合，各给一行 总题数 / 答对 / 正确率 */
    const ExamCard = ({ answers }: { answers: RecordAnswer[] }) => {
      const rows = new Map<string, { total: number; correct: number }>()
      for (const a of answers) {
        const t = typeLabel(a.type)
        const r = rows.get(t) ?? { total: 0, correct: 0 }
        r.total++
        if (!a.pending && a.correct) r.correct++
        rows.set(t, r)
      }
      return (
        <div class="exam-card">
          <div class="ec-h">考试情况</div>
          {[...rows.entries()].map(([t, r]) => (
            <div class="ec-row" key={t}>
              <span class="name"><span class="ico">{t.slice(0, 1)}</span>{t}</span>
              <span class="stat">
                总题数 <b>{r.total}</b> 题
                <span>答对 <b class="g">{r.correct}</b> 题</span>
                <span>正确率 <b>{r.total ? Math.round((r.correct / r.total) * 100) : 0}%</b></span>
              </span>
            </div>
          ))}
        </div>
      )
    }

    /** 页头：标题 + 右侧动作，跟卷面预览 / 答案解析同一条 */
    const head = (right: any) => (
      <div class="pv-head">
        <span class="pv-head-t">作答记录</span>
        <div class="pv-head-ops">{right}</div>
      </div>
    )

    /* ---------------- 详情视图 ---------------- */
    const detailView = () => {
      const d = detail.value!
      const answers = d.answers || []
      return (
        <div class="ppane rec-detail">
          {head(
            <>
              <button class="link-btn" onClick={back}>‹ 返回列表</button>
              {props.standalone
                ? null
                : (
                  <button class="link-btn" title="整页看这条记录（只读）" onClick={() => openInPage(d.id)}>
                    新页面查看
                  </button>
                )}
            </>,
          )}

          <div class="rec-hero">
            <div class="rec-hero-head">
              <h3>{d.name || d.paperName}</h3>
              <span class={['rec-chip', d.status]}>{statusLabel(d.status)}</span>
            </div>
            <div class="rec-hero-sub">
              {d.mode || '练习'} · {formatTime(d.submittedAt || d.startedAt)}
              {d.paperId < 0 ? ' · 整组作答' : ''}
            </div>
            {/* 粉笔得分卡：大号蓝分 + 满分 + 总用时 */}
            <div class="rec-score-line">
              {d.fullScore > 0
                ? (
                  <span class="score">
                    <b>{d.score}</b>
                    <i>/{d.fullScore}</i>
                  </span>
                )
                : <span class="score"><b>{accuracy(d)}%</b><i>正确率</i></span>}
              <span class="time"><b>{fmtDuration(d.durationSec)}</b>总用时</span>
            </div>
            <StatsBar r={d} />
          </div>

          <ExamCard answers={answers} />

          <div class="rec-sheet">
            <div class="rec-sheet-head">
              <h4>答题卡</h4>
              <div class="legend">
                <span><i class="dot ok"></i>答对</span>
                <span><i class="dot no"></i>答错</span>
                <span><i class="dot pend"></i>待批改</span>
                <span><i class="dot none"></i>未答</span>
              </div>
            </div>
            <div class="rec-grid">
              {answers.map((a) => (
                <div class={['cell', cellClass(a)]} key={a.no}>{a.no}</div>
              ))}
            </div>
          </div>

          <div class="rec-qlist">
            {detailBlocks(answers).map((b) =>
              b.kind === 'group'
                ? (
                  <div
                    class={['pv-group', props.standalone && 'split']}
                    key={`mat-${b.start}`}
                    onClick={onGroupClick}
                  >
                    <div innerHTML={materialCardHtml(b.start, b.count, renderMaterial(b.material))}></div>
                    <div class="pv-subs">
                      {b.answers.map((a) => (
                        <QuestionBlock a={a} key={`${a.no}-${a.stem.slice(0, 8)}`} />
                      ))}
                    </div>
                  </div>
                )
                : <QuestionBlock a={b.a} key={`${b.a.no}-${b.a.stem.slice(0, 8)}`} />,
            )}
          </div>
        </div>
      )
    }

    /* ---------------- 列表视图 ---------------- */
    const listView = () => (
      <div class="ppane rec-list">
        {head(
          <>
            <span class="pv-head-n">
              {total.value > records.value.length
                ? `最近 ${records.value.length} / 共 ${total.value} 条`
                : `共 ${total.value} 条`}
            </span>
            <button class="link-btn" onClick={() => void load()}>刷新</button>
            {props.standalone
              ? null
              : (
                <button class="link-btn" title="整页看全部记录（只读）" onClick={() => openInPage()}>
                  新页面查看
                </button>
              )}
          </>,
        )}

        {loading.value ? <div class="pv-empty">读取中…</div> : null}

        {!loading.value && loadError.value ? <div class="pv-empty">{loadError.value}</div> : null}

        {!loading.value && !loadError.value && !records.value.length
          ? (
            <div class="pv-empty">
              还没有作答记录
              <br />
              在手机上做过题之后，这里就能看到
            </div>
          )
          : null}

        {records.value.map((r) => (
          <div class="rec-card" key={r.id} onClick={() => void openDetail(r.id)}>
            <div class="rec-card-head">
              <span class="rec-card-name">{r.name || r.paperName}</span>
              <span class={['rec-chip', r.status]}>{statusLabel(r.status)}</span>
            </div>
            <div class="rec-card-meta">
              {r.paperName && r.paperName !== r.name ? <span>{r.paperName}</span> : null}
              {r.mode ? <span>{r.mode}</span> : null}
              <span>{formatTime(r.submittedAt || r.startedAt)}</span>
            </div>
            <StatsBar r={r} />
            <div class="rec-card-go">查看详情 ›</div>
          </div>
        ))}
      </div>
    )

    return () => {
      if (detailLoading.value) return <div class="ppane"><div class="pv-empty">读取明细…</div></div>
      if (detail.value) return detailView()
      return listView()
    }
  },
})
