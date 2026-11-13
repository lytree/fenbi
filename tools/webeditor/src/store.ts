import { computed, reactive } from 'vue'
import { api } from './api'
import type {
  EditorPaper, Filter, Group, Issue, PaperSetInfo, QIssue,
  Question, QuestionType, Sheet, SubjectFilter, TypeFilter, VersionInfo, WireSheet,
} from './types'
import { formatTime, nextUid, sleep } from './util'
import { parsePasted } from './paste'

/* ------------------------------------------------------------------ 常量 */

export const TYPE_LABEL: Record<QuestionType, string> = {
  single: '单选题',
  multi: '多选题',
  uncertain: '不定项',
  truefalse: '判断题',
  fill: '填空题',
  subjective: '主观题',
}

export const TYPE_SHORT: Record<QuestionType, string> = {
  single: '单选',
  multi: '多选',
  uncertain: '不定项',
  truefalse: '判断',
  fill: '填空',
  subjective: '主观',
}

export const CHOICE: QuestionType[] = ['single', 'multi', 'uncertain', 'truefalse']
export const LETTERS = 'ABCDEFGH'.split('')

/* ------------------------------------------------------------------ 状态 */

export interface ModalState {
  /** waiting = 等手机确认（删除请求），没有输入框，只有状态文案 */
  kind: 'text' | 'confirm' | 'errors' | 'paste' | 'waiting'
  title: string
  label?: string
  message?: string
  value?: string
  rows?: number
  okText?: string
  resolve: (v: any) => void
}

export const state = reactive({
  sets: [] as PaperSetInfo[],
  questionTotal: 0,
  paper: null as EditorPaper | null,
  sheet: 0,
  dirty: false,
  expanded: [] as number[],
  query: '',
  filter: 'all' as Filter,
  typeFilter: 'all' as TypeFilter,
  subjectFilter: 'all' as SubjectFilter,
  pane: 'preview' as 'preview' | 'issue' | 'version',
  versions: [] as VersionInfo[],
  versionLoading: false,
  focused: null as number | null,
  groups: [] as Group[],
})

export const ui = reactive({
  toastText: '',
  toastKind: '' as '' | 'ok' | 'err',
  toastShow: false,
  modal: null as ModalState | null,
  busy: false,
  /** 正在等手机确认的删除请求 id（空 = 没在等） */
  waitingId: null as string | null,
  /** 最近一次草稿落盘时间（0 = 当前没有草稿），顶栏据此显示「已自动保存」 */
  draftSavedAt: 0,
})

let toastTimer: number | undefined
export function toast(text: string, kind: '' | 'ok' | 'err' = '') {
  ui.toastText = text
  ui.toastKind = kind
  ui.toastShow = true
  window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => { ui.toastShow = false }, 3200)
}

/* ------------------------------------------------------------------ 弹层 */

export function askText(opts: {
  title: string
  label?: string
  value?: string
  rows?: number
  okText?: string
}): Promise<string | null> {
  return new Promise((resolve) => {
    ui.modal = {
      kind: 'text',
      title: opts.title,
      label: opts.label,
      value: opts.value ?? '',
      rows: opts.rows ?? 1,
      okText: opts.okText ?? '确定',
      resolve,
    }
  })
}

export function askConfirm(title: string, message: string, okText = '确定'): Promise<boolean> {
  return new Promise((resolve) => {
    ui.modal = { kind: 'confirm', title, message, okText, resolve }
  })
}

export function showErrors(title: string, message: string): Promise<void> {
  return new Promise((resolve) => {
    ui.modal = { kind: 'errors', title, message, resolve }
  })
}

export function closeModal(value?: unknown) {
  const m = ui.modal
  ui.modal = null
  m?.resolve(value)
}

/* ------------------------------------------------------------------ 基础工具 */

export function currentSheet(): Sheet | null {
  return state.paper ? state.paper.sheets[state.sheet] ?? null : null
}

export function currentQuestions(): Question[] {
  return currentSheet()?.questions ?? []
}

export function blankQuestion(type: QuestionType = 'single'): Question {
  const tf = type === 'truefalse'
  const keys = tf ? ['A', 'B'] : ['A', 'B', 'C', 'D']
  return {
    _uid: nextUid(),
    type,
    subject: '',
    material: '',
    images: '',
    stem: '',
    options: keys.map((key) => ({ key, text: tf ? (key === 'A' ? '正确' : '错误') : '' })),
    answer: '',
    analysis: '',
    knowledge: '',
    difficulty: '',
    region: '',
    source: '',
    year: '',
    score: '',
    keywords: '',
    _issues: null,
  }
}

export function groupsOf(qs: Question[]): Group[] {
  const out: Group[] = []
  let i = 0
  while (i < qs.length) {
    const material = (qs[i].material || '').trim()
    if (!material) { i++; continue }
    let j = i
    while (j + 1 < qs.length && (qs[j + 1].material || '').trim() === material) j++
    out.push({ start: i, end: j, material: qs[i].material })
    i = j + 1
  }
  return out
}

export function groupAt(groups: Group[], index: number): Group | null {
  return groups.find((g) => index >= g.start && index <= g.end) ?? null
}

export function refreshGroups() {
  state.groups = groupsOf(currentQuestions())
}

export function ansSet(q: Question): string[] {
  if (q.type === 'truefalse') return q.answer ? [q.answer] : []
  return (q.answer || '').toUpperCase().split(',').map((s) => s.trim()).filter(Boolean)
}

export function ansValues(q: Question): string[] {
  if (q.type === 'truefalse') return ['正确', '错误']
  return q.options.map((o) => o.key)
}

export function toggleAnswer(q: Question, val: string) {
  const current = ansSet(q)
  const multi = q.type === 'multi' || q.type === 'uncertain'
  let next: string[]
  if (multi) next = current.includes(val) ? current.filter((x) => x !== val) : [...current, val]
  else next = current.includes(val) ? [] : [val]
  q.answer = q.type === 'truefalse' ? next[0] ?? '' : next.sort().join(',')
}

/** 单题的即时检查：纯函数，不缓存（渲染期写缓存会和响应式打架） */
export function localIssues(q: Question): QIssue[] {
  const out: QIssue[] = []
  if (!q.stem.trim()) out.push({ level: 'error', message: '题干为空，App 导入时会被跳过' })
  const opts = q.options.filter((o) => o.text.trim())
  if (CHOICE.includes(q.type) && opts.length < 2) {
    out.push({ level: 'error', message: '选择题至少要 2 个有内容的选项' })
  }
  if (!q.answer.trim()) {
    out.push({ level: 'warn', message: '还没填答案（App 里按「待补答案」处理）' })
  } else if (['single', 'multi', 'uncertain'].includes(q.type)) {
    const letters = ansSet(q)
    const keys = opts.map((o) => o.key)
    if (letters.some((l) => !keys.includes(l))) out.push({ level: 'warn', message: '答案不在选项范围内' })
    if (q.type === 'single' && letters.length > 1) out.push({ level: 'warn', message: '单选题给了多个答案，App 会按多选判' })
  }
  return out
}

export function collectIssues(): Issue[] {
  const qs = currentQuestions()
  const out: Issue[] = []
  const seen = new Map<string, number>()

  qs.forEach((q, i) => {
    localIssues(q).forEach((x) => {
      out.push({ level: x.level, sheet: state.sheet, index: i, message: `第 ${i + 1} 题：${x.message}` })
    })
    const stem = q.stem.trim()
    if (stem) {
      const prev = seen.get(stem)
      if (prev !== undefined) {
        out.push({
          level: 'warn', sheet: state.sheet, index: i,
          message: `第 ${i + 1} 题：题干与第 ${prev + 1} 题完全相同，App 会把后一道当重复题去掉`,
        })
      } else {
        seen.set(stem, i)
      }
    }
  })

  // 材料组被拆：同一段材料在卷面上出现成多段
  const runs = new Map<string, number[]>()
  let prevMaterial = ''
  qs.forEach((q, i) => {
    const m = (q.material || '').trim()
    if (m && m !== prevMaterial) {
      if (!runs.has(m)) runs.set(m, [])
      runs.get(m)!.push(i)
    }
    prevMaterial = m
  })
  runs.forEach((starts) => {
    if (starts.length > 1) {
      out.push({
        level: 'warn', sheet: state.sheet, index: starts[1],
        message: `同一则材料分别从第 ${starts.map((s) => s + 1).join('、')} 题开始，中间隔了别的题，会拆成多个材料组`,
      })
    }
  })

  return out
}

export function issueCounts(): { errors: number; warns: number } {
  return issueStats.value
}

/**
 * 校验计数按依赖缓存。
 *
 * 以前每次渲染都全量扫一遍（App 里两处各调一次 counts()），千题规模下
 * 光是打字就一直在重算 —— 搜索框每敲一个字都会触发整页渲染。
 */
export const issueStats = computed(() => {
  const all = collectIssues()
  return {
    errors: all.filter((i) => i.level === 'error').length,
    warns: all.filter((i) => i.level === 'warn').length,
  }
})

export function isExpanded(uid: number) {
  return state.expanded.includes(uid)
}

export function toggleExpand(uid: number) {
  state.expanded = isExpanded(uid)
    ? state.expanded.filter((x) => x !== uid)
    : [...state.expanded, uid]
  state.focused = isExpanded(uid) ? uid : null
}

export function markDirty() {
  state.dirty = true
  scheduleDraftSave()
}

/* ------------------------------------------------------------------ 载入 / 发布 */

export async function loadSets() {
  const r = await api.sets()
  state.sets = r.sets ?? []
  state.questionTotal = r.questionTotal ?? 0
}

export async function openSet(id: number, opts: { force?: boolean } = {}): Promise<boolean> {
  if (!opts.force && state.dirty) {
    const ok = await askConfirm('这一组还没发布', '切换会丢掉刚才的改动，确定吗？', '丢掉改动')
    if (!ok) return false
  }
  const p = await api.paper(id)
  state.paper = {
    id: p.id,
    name: p.title,
    sheets: (p.sheets ?? []).map((s) => ({
      name: s.name,
      questions: (s.questions ?? []).map((q) => ({ ...q, _uid: nextUid(), _issues: null })),
    })),
  }
  if (!state.paper.sheets.length) state.paper.sheets = [{ name: '试卷', questions: [] }]
  state.sheet = 0
  state.expanded = []
  state.focused = null
  state.dirty = false
  resetFilters()
  clearDraft()
  refreshGroups()
  const first = currentQuestions()[0]
  if (first) {
    state.expanded = [first._uid]
    state.focused = first._uid
  }
  void loadVersions()
  return true
}

export function newPaper(name: string) {
  state.paper = { id: 0, name, sheets: [{ name: '试卷', questions: [] }] }
  state.sheet = 0
  state.expanded = []
  state.focused = null
  state.dirty = true
  state.versions = []
  resetFilters()
  clearDraft()
  refreshGroups()
}

export function serialize(): WireSheet[] {
  return (state.paper?.sheets ?? []).map((s) => ({
    name: s.name,
    questions: s.questions.map((q) => {
      const { _uid, _issues, ...rest } = q
      return rest
    }),
  }))
}

export async function publish(opts: { replace?: boolean } = {}): Promise<boolean> {
  const paper = state.paper
  if (!paper) return false
  const title = (paper.name || '').trim()
  if (!title) { toast('先给这一组起个名字', 'err'); return false }
  const sheets = serialize()
  if (!sheets.length || sheets.every((s) => s.questions.length === 0)) {
    toast('还一道题都没有，先加题', 'err')
    return false
  }

  ui.busy = true
  try {
    const r = await api.publish(title, !!opts.replace, sheets)
    paper.id = r.setId
    state.dirty = false
    clearDraft()
    await loadSets()
    await loadVersions()
    const archived = r.version ? ' · 旧内容已存为版本' : ''
    toast(
      `已发布「${title}」· ${r.questionCount} 题${r.replaced ? ' · 覆盖了同名组' : ''}${archived}，回手机就能看到`,
      'ok',
    )
    return true
  } catch (e) {
    const msg = e instanceof Error ? e.message : '发布失败'
    if (msg.includes('确认覆盖')) {
      const ok = await askConfirm('已经有同名的一组', `${msg}\n\n覆盖会把那一组旧卷连同作答记录一起清掉，确定吗？`, '覆盖发布')
      if (ok) {
        ui.busy = false
        return publish({ replace: true })
      }
      return false
    }
    await showErrors('发布没成功', msg)
    return false
  } finally {
    ui.busy = false
  }
}

/* ------------------------------------------------------------------ 题目操作 */

export function addQuestion(afterIndex?: number, type: QuestionType = 'single') {
  const qs = currentQuestions()
  const q = blankQuestion(type)
  const at = afterIndex === undefined ? qs.length : afterIndex + 1
  qs.splice(at, 0, q)
  state.expanded = [q._uid]
  state.focused = q._uid
  markDirty()
  refreshGroups()
  return q
}

export function removeQuestion(uid: number) {
  const qs = currentQuestions()
  const i = qs.findIndex((q) => q._uid === uid)
  if (i < 0) return
  qs.splice(i, 1)
  state.expanded = state.expanded.filter((x) => x !== uid)
  if (state.focused === uid) state.focused = null
  markDirty()
  refreshGroups()
}

export function duplicateQuestion(uid: number) {
  const qs = currentQuestions()
  const i = qs.findIndex((q) => q._uid === uid)
  if (i < 0) return
  const copy: Question = JSON.parse(JSON.stringify(qs[i]))
  copy._uid = nextUid()
  copy._issues = null
  qs.splice(i + 1, 0, copy)
  state.expanded = [copy._uid]
  state.focused = copy._uid
  markDirty()
  refreshGroups()
}

export function moveQuestion(uid: number, delta: number) {
  const qs = currentQuestions()
  const i = qs.findIndex((q) => q._uid === uid)
  const j = i + delta
  if (i < 0 || j < 0 || j >= qs.length) return
  const [q] = qs.splice(i, 1)
  qs.splice(j, 0, q)
  markDirty()
  refreshGroups()
}

export function updateQuestion(q: Question, field: keyof Question, value: string) {
  ;(q as any)[field] = value
  q._issues = null
  markDirty()
  if (field === 'material') refreshGroups()
}

export function setQuestionType(q: Question, type: QuestionType) {
  if (type === 'truefalse') {
    const prev = q.answer
    q.options = [
      { key: 'A', text: '正确' },
      { key: 'B', text: '错误' },
    ]
    q.answer = prev === 'A' || prev === '正确' ? '正确' : prev === 'B' || prev === '错误' ? '错误' : ''
  }
  q.type = type
  q._issues = null
  markDirty()
  refreshGroups()
}

export function addOption(q: Question) {
  const used = new Set(q.options.map((o) => o.key))
  const key = LETTERS.find((l) => !used.has(l))
  if (!key) { toast('最多 8 个选项'); return }
  q.options.push({ key, text: '' })
  q._issues = null
  markDirty()
}

export function removeOption(q: Question, key: string) {
  if (q.options.length <= 2) return
  q.options = q.options.filter((o) => o.key !== key)
  q.answer = ansSet(q).filter((x) => x !== key).join(',')
  q._issues = null
  markDirty()
}

export function setGroupMaterial(startIndex: number, text: string) {
  const qs = currentQuestions()
  const g = state.groups.find((x) => x.start === startIndex)
  if (!g) return
  for (let i = g.start; i <= g.end; i++) {
    qs[i].material = text
    qs[i]._issues = null
  }
  markDirty()
  refreshGroups()
}

/** 把上一题的材料沿用过来（这两题就并成一个材料组了） */
export function adoptPreviousMaterial(q: Question) {
  const qs = currentQuestions()
  const i = qs.findIndex((x) => x._uid === q._uid)
  if (i <= 0) return
  q.material = qs[i - 1].material
  q._issues = null
  markDirty()
  refreshGroups()
}

export function ungroupMaterial(q: Question) {
  q.material = ''
  q._issues = null
  markDirty()
  refreshGroups()
}

export async function uploadImage(file: File): Promise<string | null> {
  try {
    const path = await api.upload(file)
    toast(`图片已存进手机：${path}`, 'ok')
    return path
  } catch (e) {
    toast(e instanceof Error ? e.message : '图片上传失败', 'err')
    return null
  }
}

/* ------------------------------------------------------------------ 工作表 */

export function addSheet(name: string) {
  if (!state.paper) return
  state.paper.sheets.push({ name: name.trim() || `卷${state.paper.sheets.length + 1}`, questions: [] })
  state.sheet = state.paper.sheets.length - 1
  state.expanded = []
  state.focused = null
  markDirty()
  refreshGroups()
}

export function renameSheet(index: number, name: string) {
  if (!state.paper) return
  const s = state.paper.sheets[index]
  if (!s) return
  s.name = name.trim() || s.name
  markDirty()
}

export function removeSheet(index: number) {
  if (!state.paper || state.paper.sheets.length <= 1) return
  state.paper.sheets.splice(index, 1)
  if (state.sheet >= state.paper.sheets.length) state.sheet = state.paper.sheets.length - 1
  state.expanded = []
  state.focused = null
  markDirty()
  refreshGroups()
}

export function switchSheet(index: number) {
  state.sheet = index
  state.expanded = []
  state.focused = null
  resetFilters()
  refreshGroups()
}

/* ------------------------------------------------------------------ 筛选 */

/** 「没填科目」在下拉里单独列一项：这些题恰恰最该被筛出来补 */
export const SUBJECT_NONE = '__none__'

/** 当前这卷各题型的题数，下拉里直接带出来，省得盲选 */
export function typeCounts(): Partial<Record<QuestionType, number>> {
  const out: Partial<Record<QuestionType, number>> = {}
  for (const q of currentQuestions()) out[q.type] = (out[q.type] ?? 0) + 1
  return out
}

/** 科目下拉：科目是导入时的自由文本（「行测·资料分析」…），只能从当前这卷现算 */
export function subjectOptions(): { value: string; label: string; count: number }[] {
  const map = new Map<string, number>()
  let missing = 0
  for (const q of currentQuestions()) {
    const s = (q.subject || '').trim()
    if (!s) { missing++; continue }
    map.set(s, (map.get(s) ?? 0) + 1)
  }
  const out = [...map.entries()]
    .map(([value, count]) => ({ value, label: value, count }))
    .sort((a, b) => b.count - a.count || a.label.localeCompare(b.label, 'zh'))
  if (missing) out.push({ value: SUBJECT_NONE, label: '未填科目', count: missing })
  // 选中的科目在这卷里已经不存在时（改过名 / 换过卷）补一条空计数，
  // 否则 select 找不到对应 option 会显示成空白，看着像筛选坏了
  const cur = state.subjectFilter
  if (cur !== 'all' && !out.some((o) => o.value === cur)) {
    out.push({ value: cur, label: cur === SUBJECT_NONE ? '未填科目' : cur, count: 0 })
  }
  return out
}

/** 除搜索外还有筛选条件生效（工具栏据此显示筛出题数和「清除筛选」） */
export function filtersActive(): boolean {
  return state.filter !== 'all' || state.typeFilter !== 'all' || state.subjectFilter !== 'all'
}

/** 切卷 / 换组时清掉筛选项：科目每卷各算各的，留着上一卷的科目只会筛出空列表 */
export function resetFilters() {
  state.filter = 'all'
  state.typeFilter = 'all'
  state.subjectFilter = 'all'
}

/** 筛选结果按依赖缓存：列表每次渲染都要这份索引，直接算等于每帧扫全卷 */
const visibleCache = computed(() => {
  const qs = currentQuestions()
  const groups = state.groups
  const kw = state.query.trim().toLowerCase()
  const type = state.typeFilter
  const subject = state.subjectFilter
  const out: number[] = []
  qs.forEach((q, i) => {
    if (type !== 'all' && q.type !== type) return
    if (subject !== 'all') {
      const s = (q.subject || '').trim()
      if (subject === SUBJECT_NONE ? s !== '' : s !== subject) return
    }
    if (state.filter !== 'all') {
      const hasIssue = localIssues(q).length > 0
      if (state.filter === 'issue' && !hasIssue) return
      if (state.filter === 'noanswer' && q.answer.trim()) return
      if (state.filter === 'group' && !groupAt(groups, i)) return
      if (state.filter === 'image') {
        const blob = `${q.material}${q.stem}${q.options.map((o) => o.text).join('')}`
        if (!/!\[.*?\]\(/.test(blob)) return
      }
    }
    if (kw) {
      const hay = [q.stem, q.material, q.answer, q.analysis, q.subject, q.knowledge, q.source,
        q.options.map((o) => o.text).join(' ')].join(' ').toLowerCase()
      if (!hay.includes(kw)) return
    }
    out.push(i)
  })
  return out
})

export function visibleIndexes(): number[] {
  return visibleCache.value
}


/* ------------------------------------------------------------------ 定位 / 批量插入 */

export function focusQuestion(index: number) {
  const q = currentQuestions()[index]
  if (!q) return
  if (!state.expanded.includes(q._uid)) state.expanded = [...state.expanded, q._uid]
  state.focused = q._uid
  requestAnimationFrame(() => {
    document
      .querySelector(`.qcard[data-uid="${q._uid}"]`)
      ?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  })
}

export function insertQuestions(list: Question[]) {
  if (!list.length) return
  const qs = currentQuestions()
  qs.push(...list)
  state.expanded = list.map((q) => q._uid)
  state.focused = list[0]._uid
  markDirty()
  refreshGroups()
}

export function openPasteDialog() {
  ui.modal = {
    kind: 'paste',
    title: '批量粘贴题目',
    value: '',
    rows: 14,
    okText: '解析并插入',
    resolve: (v: unknown) => {
      if (typeof v !== 'string' || !v.trim()) return
      const list = parsePasted(v)
      if (!list.length) {
        toast('没解析出题目，看看提示里的写法：题号或空行分题、A. 开头写选项、答案：/解析：做前缀', 'err')
        return
      }
      insertQuestions(list)
      toast(`插入 ${list.length} 题${list.some((q) => q.type !== 'single') ? '（含非单选）' : ''}，记得发布`, 'ok')
    },
  }
}


/* ------------------------------------------------------------------ 版本存档 */

/**
 * 拉当前这一组的历史版本。
 *
 * 新组（还没发布过）没有存档，直接清空；接口挂了也不该让整个页面报错，
 * 所以这里吞掉异常——版本面板空着比弹一堆红字友好。
 */
export async function loadVersions() {
  const title = (state.paper?.name ?? '').trim()
  if (!state.paper || state.paper.id <= 0 || !title) {
    state.versions = []
    return
  }
  state.versionLoading = true
  try {
    const r = await api.versions(title)
    state.versions = r.versions ?? []
  } catch {
    state.versions = []
  } finally {
    state.versionLoading = false
  }
}

export const versionReason = (reason: string) =>
  reason === 'restore' ? '回滚前存档' : '发布前存档'

/** 回滚到某一版：手机端会先把「现在」再存一版，所以滚错了还能滚回来 */
export async function restoreVersion(v: VersionInfo): Promise<void> {
  const ok = await askConfirm(
    '回滚到这一版？',
    `${formatTime(v.savedAt)} · ${v.paperCount} 卷 ${v.questionCount} 题` + '\n\n' +
      '回滚会覆盖现在这一组；现在的内容也会先存一版，滚错了还能再滚回来。旧卷的作答记录不会跟着回来。',
    '回滚到这一版',
  )
  if (!ok) return
  ui.busy = true
  try {
    const r = await api.restoreVersion(v.id, state.paper?.id ?? 0)
    await loadSets()
    if (state.paper) await openSet(r.setId, { force: true })
    toast(`已回滚到 ${formatTime(v.savedAt)} 那一版 · ${r.questionCount} 题`, 'ok')
  } catch (e) {
    toast(e instanceof Error ? e.message : '回滚失败', 'err')
  } finally {
    ui.busy = false
  }
}

/** 删存档不动题库，网页上确认一下就够 */
export async function deleteVersion(v: VersionInfo): Promise<void> {
  const ok = await askConfirm(
    '删掉这份版本存档？',
    `${formatTime(v.savedAt)} · ${v.paperCount} 卷 ${v.questionCount} 题` + '\n\n' +
      '只删存档，App 里的试卷和作答记录都不受影响。',
    '删存档',
  )
  if (!ok) return
  try {
    await api.deleteVersion(v.id)
    state.versions = state.versions.filter((x) => x.id !== v.id)
    toast('存档已删', 'ok')
  } catch (e) {
    toast(e instanceof Error ? e.message : '删存档失败', 'err')
  }
}

/* ------------------------------------------------------------------ 删除：手机上是最后一道闸 */

/**
 * 删一组：电脑这边只发请求，手机上确认了才真删。
 *
 * 为什么不在网页上直接删：浏览器开着的那台电脑未必是机主，误点一下就是整组题没了。
 * 手机上那一下确认，等于把「破坏性操作」的许可留在人手里。
 */
export async function requestDeleteSet(setId: number, title: string): Promise<void> {
  if (setId <= 0) return
  if (ui.waitingId) {
    toast('还有一条删除请求在等手机确认，先处理完再发', 'err')
    return
  }
  const go = await askConfirm(
    `删除「${title}」这一组？`,
    '点「发到手机确认」只是发个请求——手机的「局域网编辑器」会弹确认框，在手机上点「确认删除」才真的删。' + '\n\n' +
      '10 分钟内没人确认，请求自动作废。',
    '发到手机确认',
  )
  if (!go) return
  try {
    const r = await api.requestDelete(setId)
    ui.waitingId = r.requestId
    ui.modal = {
      kind: 'waiting',
      title: '等手机上点「确认删除」',
      message: `删除「${r.title}」` + '\n' + r.detail,
      label: '手机上会收到一条通知；不想删了可以在这里撤回。',
      resolve: () => {},
    }
    void pollDelete(r.requestId, setId, r.title, r.ttlSec)
  } catch (e) {
    toast(e instanceof Error ? e.message : '发不出删除请求', 'err')
  }
}

function finishWaiting() {
  ui.waitingId = null
  if (ui.modal?.kind === 'waiting') closeModal()
}

/** 网页上撤回：手机还没点的话，那边就不弹了 */
export async function cancelWaiting(): Promise<void> {
  const id = ui.waitingId
  if (!id) return
  finishWaiting()
  try {
    const r = await api.cancelDelete(id)
    // 手机上刚点过「确认删除」的话，服务端会明确回 ok=false——这时候别说「已撤回」
    if (r.ok === false) toast('手机上已经点了确认，删不回来了', 'err')
    else toast('已撤回，手机上不会再问', 'ok')
  } catch {
    toast('请求可能已经被手机处理过了', '')
  }
}

async function pollDelete(id: string, setId: number, title: string, ttlSec: number) {
  const deadline = Date.now() + Math.max(30, ttlSec) * 1000
  while (ui.waitingId === id) {
    if (Date.now() > deadline) {
      finishWaiting()
      toast('等太久没确认，请求已作废；要删再发一次', 'err')
      return
    }
    await sleep(1200)
    if (ui.waitingId !== id) return

    let s
    try {
      s = await api.deleteStatus(id)
    } catch {
      continue
    }
    if (s.status === 'pending') {
      if (ui.modal?.kind === 'waiting') {
        const left = Math.max(0, Math.ceil((deadline - Date.now()) / 1000))
        ui.modal.label =
          `还剩 ${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}，手机上点「确认删除」才会执行。`
      }
      continue
    }

    finishWaiting()
    if (s.status === 'approved') {
      if (state.paper && state.paper.id === setId) {
        state.paper = null
        state.dirty = false
        state.versions = []
        state.expanded = []
        state.focused = null
      }
      await loadSets()
      refreshGroups()
      toast(`手机上已确认，删掉了「${title}」`, 'ok')
    } else if (s.status === 'rejected') {
      toast(
        s.error ? `删除没成功：${s.error}` : '手机上点了「不删」，题库没动',
        s.error ? 'err' : '',
      )
    } else {
      toast('请求已作废，要删就再发一次', 'err')
    }
    return
  }
}

/* ------------------------------------------------------------------ 草稿（防丢） */

const DRAFT_KEY = 'fenbi.lan-editor.draft.v2'

/** 停手 700ms 落一份，另外每 30 秒兜底存一次（有的改动不经过 markDirty） */
const DRAFT_DEBOUNCE_MS = 700
export const DRAFT_INTERVAL_MS = 30_000

interface Draft {
  setId: number
  name: string
  sheets: WireSheet[]
  savedAt: number
}

let draftTimer: number | undefined
let draftInterval: number | undefined

/**
 * 把当前内容写进 localStorage。
 *
 * 以前只有 beforeunload 一句「确定离开吗」：关掉标签页、电脑休眠、浏览器崩了，
 * 一下午的改动就没了。这里落一份草稿，下次打开还能捡回来。
 *
 * **草稿不设过期时间**：只要还没发布、也没主动丢弃，它就一直在，
 * 隔几天回来打开照样能接着改（旧版本是 3 天过期，删掉了）。
 */
function saveDraftNow() {
  const paper = state.paper
  if (!paper || !state.dirty) return
  const savedAt = Date.now()
  try {
    localStorage.setItem(DRAFT_KEY, JSON.stringify({
      setId: paper.id,
      name: paper.name,
      sheets: serialize(),
      savedAt,
    } satisfies Draft))
    ui.draftSavedAt = savedAt
  } catch {
    // 隐私模式 / 配额满：存不下就算了，别打断编辑
    ui.draftSavedAt = 0
  }
}

export function scheduleDraftSave() {
  window.clearTimeout(draftTimer)
  draftTimer = window.setTimeout(saveDraftNow, DRAFT_DEBOUNCE_MS)
}

/** 立刻落盘（关页面 / 切到后台时用，不留最后一次改动在内存里） */
export function flushDraft() {
  window.clearTimeout(draftTimer)
  saveDraftNow()
}

/** 开始定期兜底保存；重复调用只保留一个定时器 */
export function startDraftAutoSave() {
  if (draftInterval !== undefined) return
  draftInterval = window.setInterval(saveDraftNow, DRAFT_INTERVAL_MS)
}

export function stopDraftAutoSave() {
  window.clearInterval(draftInterval)
  draftInterval = undefined
  window.clearTimeout(draftTimer)
}

/** 发布成功、或用户明确选择丢弃时才清；草稿本身不会因为时间久了被删 */
export function clearDraft() {
  window.clearTimeout(draftTimer)
  ui.draftSavedAt = 0
  try { localStorage.removeItem(DRAFT_KEY) } catch { /* 忽略 */ }
}

function readDraft(): Draft | null {
  try {
    const raw = localStorage.getItem(DRAFT_KEY)
    if (!raw) return null
    const d = JSON.parse(raw) as Draft
    if (!d?.name || !Array.isArray(d.sheets)) return null
    return d
  } catch {
    return null
  }
}

/** 草稿是否存在（给「本地草稿」入口用，不弹任何东西） */
export function hasDraft(): boolean {
  return readDraft() !== null
}

/**
 * 启动时问一句要不要接着上次改。
 *
 * 只在「上次退出时还有没发布的改动」时才有草稿；恢复出来的内容照旧按未发布处理，
 * 点「发布到 App」才算落库。草稿没有过期时间，隔几天回来一样能捡。
 */
export async function maybeRestoreDraft(): Promise<boolean> {
  const d = readDraft()
  if (!d) return false
  const age = Date.now() - (d.savedAt ?? 0)
  const when = age > 24 * 3600_000
    ? `${formatTime(d.savedAt)}（${Math.floor(age / 86_400_000)} 天前）`
    : formatTime(d.savedAt)
  const ok = await askConfirm(
    '接着上次改？',
    `本地存着一份 ${when} 的未发布改动：「${d.name}」共 ${d.sheets.length} 卷。\n\n` +
      '恢复后再点「发布到 App」才会写进手机；不要的话就从手机上的当前内容打开（选「不要」会删掉这份草稿）。',
    '接着改',
  )
  if (!ok) { clearDraft(); return false }
  state.paper = {
    id: d.setId ?? 0,
    name: d.name,
    sheets: d.sheets.map((s) => ({
      name: s.name,
      questions: (s.questions ?? []).map((q) => ({ ...q, _uid: nextUid(), _issues: null })),
    })),
  }
  if (!state.paper.sheets.length) state.paper.sheets = [{ name: '试卷', questions: [] }]
  state.sheet = 0
  state.expanded = []
  state.focused = null
  state.dirty = true
  ui.draftSavedAt = d.savedAt ?? 0
  refreshGroups()
  return true
}
