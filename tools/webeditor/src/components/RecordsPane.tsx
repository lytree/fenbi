import { defineComponent, ref } from 'vue'
import { api } from '../api'
import type { RecordAnswer, RecordDetail, RecordInfo } from '../api'
import { TYPE_LABEL } from '../store'
import { formatTime } from '../util'
import { renderInline, renderMaterial, renderStem } from '../markdown'

/**
 * 右栏「记录」页：全库的作答记录，按记录逐条成组展示（粉笔风格）。
 *
 * 两层视图：
 * - 列表：每条记录一张卡片，头部是练习名 + 状态，下面是粉笔式的统计条
 *   （一共 / 答对 / 答错 / 正确率 / 用时），点卡片跳到详情；
 * - 详情：成绩统计 + 答题卡色块 + 逐题明细（用户答案 / 标准答案 / 对错 / 得分）。
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

export default defineComponent({
  name: 'RecordsPane',
  setup() {
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
    void load()

    const openDetail = async (id: number) => {
      detailLoading.value = true
      try {
        detail.value = await api.record(id)
      } catch (e) {
        loadError.value = e instanceof Error ? e.message : '读取明细失败'
      } finally {
        detailLoading.value = false
      }
    }

    const back = () => { detail.value = null; loadError.value = '' }

    /** 答题卡色块：答对绿 / 答错红 / 没答灰 / 待批橙 */
    const cellClass = (a: RecordAnswer): string =>
      a.pending ? 'pend' : !a.userAnswer.trim() ? 'none' : a.correct ? 'ok' : 'no'

    /** 统计条：一共 / 答对 / 答错 / 正确率 / 总用时（+得分，卷面带分值才有意义） */
    const StatsBar = ({ r }: { r: RecordInfo }) => {
      const cells: { k: string; v: string; unit?: string; cls: string }[] = [
        { k: '一共', v: String(r.total), unit: '题', cls: 'c-blue' },
        { k: '答对', v: String(r.correct), unit: '题', cls: 'c-green' },
        { k: '答错', v: String(Math.max(r.total - r.correct - (r.partial || 0), 0)), unit: '题', cls: 'c-red' },
        { k: '正确率', v: String(accuracy(r)), unit: '%', cls: 'c-blue' },
        { k: '总用时', v: fmtDuration(r.durationSec), cls: 'c-blue' },
      ]
      if (r.fullScore > 0) cells.push({ k: '得分', v: String(r.score), unit: `/${r.fullScore}`, cls: 'c-green' })
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

    /** 逐题明细里的一道题 */
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
        <div class="rec-q">
          <div class="pv-tagrow">
            <span class="pv-tag">{typeLabel(a.type)}</span>
            <span class="pv-no">第 {a.no} 题</span>
            <span class={['rec-result', result.cls]}>{result.text}</span>
            {a.full > 0 ? (
              <span class="rec-score">{a.pending ? `0/${a.full} 分` : `${a.earned}/${a.full} 分`}</span>
            ) : null}
          </div>

          {a.material ? <div class="pv-mat" innerHTML={renderMaterial(a.material)}></div> : null}
          <div class="pv-stem" innerHTML={renderStem(a.stem) || '<span class="pv-ph">（题干为空）</span>'}></div>

          {choice ? (
            <div class="rec-opts">
              {a.options.filter((o) => o.text.trim()).map((o) => {
                const inAnswer = keys.includes(o.key.toUpperCase())
                const picked = userKeys.includes(o.key.toUpperCase())
                // 标准答案绿、自己的错选红，两个都中以绿为先
                const cls = inAnswer ? 'right' : picked ? 'wrong' : ''
                return (
                  <div class={['rec-opt', cls]} key={o.key}>
                    <span class="lt">{o.key}</span>
                    <span class="text" innerHTML={renderInline(o.text)}></span>
                  </div>
                )
              })}
            </div>
          ) : null}

          <div class="rec-ansbar">
            <div class="ok">正确答案：<b innerHTML={renderInline(a.answer) || '—'}></b></div>
            <div class={['no', a.pending && 'pend']}>
              你的答案：<b innerHTML={renderInline(a.userAnswer) || '未作答'}></b>
            </div>
          </div>

          {a.analysis ? (
            <div class="rec-analysis">
              <div class="mh">解析</div>
              <div innerHTML={renderStem(a.analysis)}></div>
            </div>
          ) : null}
        </div>
      )
    }

    /* ---------------- 详情视图 ---------------- */
    const detailView = () => {
      const d = detail.value!
      const answers = d.answers || []
      return (
        <div class="ppane rec-detail">
          <div class="rec-topbar">
            <button class="rec-back" onClick={back}>‹ 返回记录列表</button>
            <span class="rec-topbar-t">{d.name || d.paperName}</span>
          </div>

          <div class="rec-hero">
            <div class="rec-hero-head">
              <span class="rec-mode">{d.mode || '练习'}</span>
              <h3>{d.name || d.paperName}</h3>
              <span class={['rec-chip', d.status]}>{statusLabel(d.status)}</span>
            </div>
            <div class="rec-hero-sub">
              {formatTime(d.submittedAt || d.startedAt)}
              {d.paperId < 0 ? ' · 整组作答' : ''}
            </div>
            <StatsBar r={d} />
          </div>

          <div class="rec-sheet">
            <div class="rec-sheet-head">
              <h4>答题卡</h4>
              <div class="legend">
                <span><i class="dot" style="background:#2BC8A0"></i>答对</span>
                <span><i class="dot" style="background:#FF5A5A"></i>答错</span>
                <span><i class="dot" style="background:#FFB43A"></i>待批改</span>
                <span><i class="dot" style="background:#EDEFF3"></i>未答</span>
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
        <div class="ver-tip">
          <span>作答记录（只读）{total.value > records.value.length ? ` · 显示最近 ${records.value.length} / 共 ${total.value} 条` : ` · 共 ${total.value} 条`}</span>
          <button class="link-btn" onClick={() => void load()}>刷新</button>
        </div>

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
