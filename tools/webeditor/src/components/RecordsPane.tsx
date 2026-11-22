import { defineComponent, ref } from 'vue'
import type { PropType } from 'vue'
import { api } from '../api'
import type { RecordAnswer, RecordDetail, RecordInfo } from '../api'
import { currentSheet, state, TYPE_LABEL } from '../store'
import { formatTime } from '../util'
import { renderInline, renderMaterial, renderStem } from '../markdown'
import { openFullPage } from '../fullview'

/**
 * 右栏「记录」页：全库的作答记录，按记录逐条成组展示。
 *
 * 两层视图：
 * - 列表：每条记录一张卡（练习名 + 状态 + 统计条），点进详情；
 * - 详情：成绩概要 + 答题卡色块 + 逐题明细（选项着色 / 答案对照 / 解析）。
 *
 * 版式跟「卷面预览 / 答案解析」同一套：页头用 `.pv-head`、题目卡用 `.pv-q`、
 * 选项用 `.pv-opt`、解析块用 `.ap-analysis`——三页来回切不会觉得换了套皮，
 * 明暗两套主题也跟着令牌一起翻，不再有自己的固定色。
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

/** 选项题的答案串按字母拆开（"AC" → ['A','C']），判断题的 正确/错误 原样返回 */
const answerKeys = (ans: string): string[] =>
  ans.trim().toUpperCase().replace(/[^A-H]/g, '').split('').filter(Boolean)

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

    /** 逐题明细里的一道题：跟卷面预览 / 答案解析同一种卡片 */
    const QuestionBlock = ({ a }: { a: RecordAnswer }) => {
      const keys = answerKeys(a.answer)
      const userKeys = answerKeys(a.userAnswer)
      const choice = a.options.some((o) => o.text.trim())
      const result = a.pending
        ? { cls: 'pend', text: '待批改' }
        : !a.userAnswer.trim()
          ? { cls: 'none', text: '未作答' }
          : a.partial
            ? { cls: 'part', text: '部分正确' }
            : a.correct
              ? { cls: 'ok', text: '答对' }
              : { cls: 'no', text: '答错' }

      return (
        <div class="pv-q static">
          <div class="pv-tagrow">
            <span class="pv-tag">{typeLabel(a.type)}</span>
            <span class={['rec-result', result.cls]}>{result.text}</span>
            {a.full > 0
              ? <span class="rec-score">{a.pending ? `0/${a.full} 分` : `${a.earned}/${a.full} 分`}</span>
              : null}
            <span class="pv-no">第 {a.no} 题</span>
          </div>

          {a.material
            ? (
              <div class="pv-mat">
                <div class="mh">材料</div>
                <div innerHTML={renderMaterial(a.material)}></div>
              </div>
            )
            : null}
          <div class="pv-stem" innerHTML={renderStem(a.stem) || '<span class="pv-ph">（题干为空）</span>'}></div>

          {choice
            ? (
              <div class="pv-opts">
                {a.options.filter((o) => o.text.trim()).map((o) => {
                  const inAnswer = keys.includes(o.key.toUpperCase())
                  const picked = userKeys.includes(o.key.toUpperCase())
                  // 标准答案绿、自己的错选红，两个都中以绿为先
                  const cls = inAnswer ? 'right' : picked ? 'wrong' : ''
                  return (
                    <div class={['pv-opt', cls]} key={o.key}>
                      <span class="pv-key">{o.key}</span>
                      <span class="pv-text" innerHTML={renderInline(o.text)}></span>
                    </div>
                  )
                })}
              </div>
            )
            : null}

          <div class="rec-ansbar">
            <div class="ok">正确答案：<b innerHTML={renderInline(a.answer) || '—'}></b></div>
            <div class={[userCls(result.cls)]}>
              你的答案：<b innerHTML={renderInline(a.userAnswer) || '未作答'}></b>
            </div>
          </div>

          {a.analysis
            ? (
              <div class="ap-analysis">
                <div class="mh">解析</div>
                <div innerHTML={renderStem(a.analysis)}></div>
              </div>
            )
            : null}
          {a.knowledge ? <div class="ap-know">知识点：{a.knowledge}</div> : null}
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
            <StatsBar r={d} />
          </div>

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
            {answers.map((a) => <QuestionBlock a={a} key={`${a.no}-${a.stem.slice(0, 8)}`} />)}
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
