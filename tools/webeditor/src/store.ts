import { computed, reactive } from 'vue'
import { api } from './api'
import type { ConfirmRequired, McpKeyResult, PublishResponse } from './api'
import type {
  EditorPaper, EditorView, Filter, Group, Issue, LibraryCatalog, LibraryItem, LibrarySaveResult,
  LibraryTag, MindMapCatalog, MindMapNode, MindMapSaveResult,
  MindMapSection, MindMapTopic, PaperSetInfo,
  QIssue, Question, QuestionType, ReciteCard, ReciteCatalog, ReciteCategory,
  ReciteSaveResult, ReciteSubjectInfo, ResourceFile, Sheet, SubjectFilter,
  TypeFilter, VersionInfo, WireSheet, WordItem,
} from './types'
import { formatTime, nextUid, sleep } from './util'
import { parsePasted } from './paste'
import { parseWorkbook, matchBackfill } from './importx'
import type { BackfillEntry, BackfillPlan, ImportNote, ParsedSheet } from './importx'
import { typeName } from './xingce'

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

/** 等手机确认的动作；发布 / 回滚共用一套轮询，背诵词库的保存 / 重置也走同一条路 */
type WaitingKind =
  | 'publish' | 'restore' | 'delete'
  | 'recite-save' | 'recite-reset'
  | 'mindmap-save' | 'mindmap-reset'
  | 'library-save' | 'library-reset'
  | 'mcp'

/* ------------------------------------------------------------------ 状态 */

export interface ModalState {
  /** waiting = 等手机确认（发布 / 回滚 / 删除），没有输入框，只有状态文案 */
  kind: 'text' | 'confirm' | 'errors' | 'paste' | 'waiting' | 'import'
  title: string
  label?: string
  message?: string
  value?: string
  rows?: number
  okText?: string
  resolve: (v: any) => void
}

/** 「导入文件」弹层的预览数据：文件解析完在这里等用户确认 */
export interface ImportPreview {
  fileName: string
  mode: 'questions' | 'backfill'
  /** questions 模式：解析出的各份卷 */
  sheets: ParsedSheet[]
  /** backfill 模式：文件条目与匹配结果 */
  entries: BackfillEntry[]
  plan: BackfillPlan | null
  notes: ImportNote[]
  /** questions 模式：落到新工作表，还是追加进当前工作表（只有一份卷时可选） */
  target: 'new' | 'current'
}

export const state = reactive({
  /** 顶层工作模式：题库编辑 / 背诵词库编辑（两边的状态互不覆盖） */
  mode: 'paper' as 'paper' | 'recite',
  sets: [] as PaperSetInfo[],
  questionTotal: 0,
  paper: null as EditorPaper | null,
  sheet: 0,
  /** 题目列表 / 材料模式 / 分组板；只影响编辑区呈现，不改数据 */
  view: 'list' as EditorView,
  /** 分组板里多选的题目（_uid） */
  selected: [] as number[],
  dirty: false,
  expanded: [] as number[],
  query: '',
  filter: 'all' as Filter,
  typeFilter: 'all' as TypeFilter,
  /** 一级题型筛（值取归一后的科目名，不取科目原文） */
  subjectFilter: 'all' as SubjectFilter,
  /** 二级题型筛（值取归一后的知识点名） */
  subTopicFilter: 'all' as SubjectFilter,
  pane: 'preview' as 'preview' | 'answer' | 'issue' | 'version' | 'resource' | 'records',
  versions: [] as VersionInfo[],
  versionLoading: false,
  /** 当前这组的图片资源（右栏「资源」页签） */
  resources: [] as ResourceFile[],
  resourceLoading: false,
  /** 资源列表对应的组；换组后旧列表不能拿来当新组的计数 */
  resourceSetId: 0,
  focused: null as number | null,
  groups: [] as Group[],
})

export const ui = reactive({
  toastText: '',
  toastKind: '' as '' | 'ok' | 'err',
  toastShow: false,
  modal: null as ModalState | null,
  busy: false,
  /** 正在等手机确认的请求 id（空 = 没在等） */
  waitingId: null as string | null,
  /** 等的是哪一类：撤回时调不同接口，文案也不同 */
  waitingKind: null as WaitingKind | null,
  /** 最近一次草稿落盘时间（0 = 当前没有草稿），顶栏据此显示「已自动保存」 */
  draftSavedAt: 0,
  /** 「导入文件」的解析结果，弹层开着期间持有 */
  importPreview: null as ImportPreview | null,
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
    /** 选项排布：'' = 单列 1×4（默认），'2x2' = 两列 */
    optionLayout: '',
    disabled: false,
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
  // 禁用题不参与卷面，也就不该报「题干为空 / 缺答案」——它本来就没打算发布
  if (q.disabled) return out
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

/* ------------------------------------------------------------------ 多选 */

export function isSelected(uid: number): boolean {
  return state.selected.includes(uid)
}

export function toggleSelected(uid: number) {
  state.selected = isSelected(uid)
    ? state.selected.filter((x) => x !== uid)
    : [...state.selected, uid]
}

export function selectOnly(uid: number) {
  state.selected = [uid]
}

export function selectMany(uids: number[]) {
  state.selected = [...new Set(uids)]
}

export function clearSelection() {
  state.selected = []
}

/** 按卷面顺序返回选中的 uid（拖动 / 批量操作都按卷面顺序处理） */
export function selectedUids(): number[] {
  return currentQuestions()
    .filter((q) => state.selected.includes(q._uid))
    .map((q) => q._uid)
}

/* ------------------------------------------------------------------ 载入 / 发布 */

export async function loadSets() {
  const r = await api.sets()
  state.sets = r.sets ?? []
  state.questionTotal = r.questionTotal ?? 0
}

/**
 * 归档 / 恢复一组。
 *
 * 归档不删任何东西：题目、作答记录都留着，App 首页与网页侧栏默认不再显示，
 * 随时能恢复——所以这一步不走手机确认。归档的正好是当前打开的那组时，
 * 顺手把编辑器关掉，免得对着一个已经收起的组继续改。
 */
export async function setSetArchived(id: number, archived: boolean): Promise<boolean> {
  const title = state.sets.find((s) => s.id === id)?.title ?? state.paper?.name ?? ''
  try {
    await api.archiveSet(id, archived)
  } catch (e) {
    toast(e instanceof Error ? e.message : '归档失败', 'err')
    return false
  }
  if (archived && state.paper?.id === id) {
    state.paper = null
    state.dirty = false
    state.sheet = 0
    state.expanded = []
    state.focused = null
  }
  await loadSets()
  toast(
    archived ? `已归档「${title}」，点「归档」里的「恢复」可以拿回来` : `「${title}」已恢复`,
    'ok',
  )
  return true
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
      // 卷 id 要一路带到发布：卷名改了也能认出是同一卷，卷上的记录不会变成孤儿
      id: s.id,
      name: s.name,
      // 老数据里没有 optionLayout：补成 ''（单列），后面每题切换时才有初值可读
      questions: (s.questions ?? []).map((q) => ({
        ...q,
        optionLayout: q.optionLayout ?? '',
        disabled: q.disabled ?? false,
        _uid: nextUid(),
        _issues: null,
      })),
    })),
  }
  state.resources = []
  state.resourceSetId = 0
  if (!state.paper.sheets.length) state.paper.sheets = [{ name: '试卷', questions: [] }]
  state.sheet = 0
  state.expanded = []
  state.focused = null
  state.dirty = false
  clearSelection()
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
  clearSelection()
  resetFilters()
  clearDraft()
  refreshGroups()
}

export function serialize(): WireSheet[] {
  return (state.paper?.sheets ?? []).map((s) => ({
    id: s.id,
    name: s.name,
    questions: s.questions.map((q) => {
      const { _uid, _issues, ...rest } = q
      return rest
    }),
  }))
}

/**
 * 发布到手机题库。
 *
 * 网页没有写权限：点「发布到 App」只是把内容送过去登记，手机那边弹「确认发布」，
 * 点了才真正写库。这里一直等到手机给结论——成功 / 拒绝 / 超时。
 */
export async function publish(opts: { replace?: boolean } = {}): Promise<boolean> {
  const paper = state.paper
  if (!paper) return false
  const title = (paper.name || '').trim()
  if (!title) { toast('先给这一组起个名字', 'err'); return false }
  const sheets = serialize()
  if (!sheets.length || sheets.every((s) => s.questions.filter(isEnabled).length === 0)) {
    toast('还一道可用的题都没有，先加题（或启用已禁用的题）', 'err')
    return false
  }
  if (ui.waitingId) {
    toast('还有一条请求在等手机处理，等它结束再发', 'err')
    return false
  }

  ui.busy = true
  try {
    const r = await api.publish(title, !!opts.replace, sheets)
    // 不带 needConfirm 就是服务端直发了（只有带 X-Lan-Key 的 AI 客户端会走到这里）
    const result = 'needConfirm' in r
      ? await waitPhone(
        'publish',
        r,
        `发布「${r.title}」`,
        r.replaced
          ? '这次是覆盖发布：题目原地更新，练习记录与未交卷草稿全部保留，去掉的题只停用不删除；旧内容会先存一版。'
          : '确认后写进手机题库，App 首页就能看到。',
      )
      : r
    if (!result) return false
    await afterPublished(paper, title, result as PublishResponse)
    return true
  } catch (e) {
    const msg = e instanceof Error ? e.message : '发布失败'
    if (msg.includes('确认覆盖')) {
      const ok = await askConfirm(
        '已经有同名的一组',
        `${msg}\n\n覆盖发布是原地合并：题目按 id 更新，练习记录与未交卷草稿全部保留；从卷面上去掉的题只停用、不删除。确定覆盖吗？`,
        '覆盖发布',
      )
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

/** 发布成功后统一收尾：记住组 id、清掉未发布标记和草稿、刷新列表与版本 */
async function afterPublished(paper: EditorPaper, title: string, r: PublishResponse): Promise<void> {
  paper.id = r.setId
  state.dirty = false
  // 材料编辑区可能还没失焦就按了 Ctrl+S，发布后按最新内容重算一次分组
  refreshGroups()
  clearDraft()
  await loadSets()
  await loadVersions()
  const archived = r.version ? ' · 旧内容已存为版本' : ''
  const off = r.disabledQuestions ? ` · ${r.disabledQuestions} 题已禁用` : ''
  toast(
    `已发布「${title}」· ${r.questionCount} 题${off}${r.replaced ? ' · 覆盖了同名组' : ''}${archived}，回手机就能看到`,
    'ok',
  )
}

/**
 * 等手机点确认：弹一层「等待中」，轮询到结论为止。
 *
 * 先查一次再睡，手机上已经点过的话不用白等一轮；撤回时会清掉 ui.waitingId，
 * 循环自己就退出了。
 */
async function waitPhone(
  kind: WaitingKind,
  req: ConfirmRequired,
  message: string,
  extra: string,
  failText: { rejected: string; expired: string } = {
    rejected: '手机上点了「不」，题库没动',
    expired: '请求已作废，要发就再点一次',
  },
): Promise<PublishResponse | ReciteSaveResult | LibrarySaveResult | McpKeyResult | null> {
  ui.busy = false
  ui.waitingId = req.requestId
  ui.waitingKind = kind
  ui.modal = {
    kind: 'waiting',
    title: '等手机上点「确认」',
    message,
    label: '手机「局域网编辑器」里会弹确认框（不在那个页面时会收到通知）；不想发了可以在这里撤回。'
      + (extra ? '\n' + extra : ''),
    resolve: () => {},
  }

  const deadline = Date.now() + Math.max(30, req.ttlSec) * 1000
  while (ui.waitingId === req.requestId) {
    let s
    try {
      s = await api.publishStatus(req.requestId)
    } catch {
      await sleep(1200)
      continue
    }
    if (s.status === 'pending') {
      if (Date.now() > deadline) {
        finishWaiting()
        toast('等太久没确认，请求已作废；要发就再点一次', 'err')
        return null
      }
      if (ui.modal?.kind === 'waiting') {
        const left = Math.max(0, Math.ceil((deadline - Date.now()) / 1000))
        ui.modal.label =
          `还剩 ${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}，手机上点「确认」才会执行。`
      }
      await sleep(1200)
      continue
    }

    finishWaiting()
    if (s.status === 'approved' && s.result) return s.result
    if (s.status === 'rejected') {
      toast(s.error ? `手机上确认了，但没成功：${s.error}` : failText.rejected, s.error ? 'err' : '')
    } else {
      toast(failText.expired, 'err')
    }
    return null
  }
  return null
}

/**
 * 取 MCP 配置（含写入口令）。
 *
 * 网页没有写权限：服务端只登记一条请求，手机上点「确认提供」才把 key 交出来，
 * 拒绝或超时就当没拿到。返回值是可直接粘贴的整段 JSON。
 */
export async function requestMcpConfig(): Promise<string | null> {
  if (ui.waitingId) {
    toast('还有一条请求在等手机处理，等它结束再取', 'err')
    return null
  }
  try {
    const r = await api.mcpInfo()
    const result = await waitPhone(
      'mcp',
      { needConfirm: true, requestId: r.requestId, title: 'MCP 配置', detail: '', ttlSec: r.ttlSec },
      '获取 AI（MCP）写入口令',
      '确认之后网页就能拿到写入口令，配到 AI 客户端里可直接发布 / 回滚 / 保存；不想给就点「不给」。',
      { rejected: '手机上没同意，没拿到 MCP 配置', expired: '等太久没确认，重新点一次「复制 MCP」' },
    )
    return (result as McpKeyResult | null)?.config ?? null
  } catch (e) {
    await showErrors('拿不到 MCP 配置', e instanceof Error ? e.message : '请求失败')
    return null
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

/**
 * 在材料组末尾加一道小题：材料文本直接复制，落进去就是同一组，
 * 不用再点「沿用上一题材料」。
 */
export function addQuestionToGroup(startIndex: number) {
  const g = state.groups.find((x) => x.start === startIndex)
  if (!g) return
  const q = addQuestion(g.end)
  // 取题上的当前文本而不是组快照：材料编辑区可能刚改过、还没重新分组
  q.material = currentQuestions()[g.start]?.material ?? g.material
  refreshGroups()
  return q
}

/**
 * 「删除」一道题 = **禁用**，不是真删。
 *
 * 手机上题行和作答记录都留着：删掉的话，历史记录里那一次的作答就成了找不到题的孤儿，
 * 收藏 / 笔记也跟着没。禁用的题在编辑器里置灰、留在原位，随时点「启用」回来。
 * 例外：这次新加、还没发布过的题（没有 id）库里根本没有它，直接从列表拿走。
 */
export function disableQuestion(uid: number) {
  const qs = currentQuestions()
  const i = qs.findIndex((q) => q._uid === uid)
  if (i < 0) return
  const q = qs[i]
  if (!q.id) {
    qs.splice(i, 1)
    state.expanded = state.expanded.filter((x) => x !== uid)
    state.selected = state.selected.filter((x) => x !== uid)
    if (state.focused === uid) state.focused = null
  } else {
    q.disabled = true
    q._issues = null
    state.expanded = state.expanded.filter((x) => x !== uid)
    if (state.focused === uid) state.focused = null
  }
  markDirty()
  refreshGroups()
}

/** 把一道禁用题重新启用（发布后才会回到 App 的卷面里） */
export function enableQuestion(uid: number) {
  const qs = currentQuestions()
  const q = qs.find((x) => x._uid === uid)
  if (!q || !q.disabled) return
  q.disabled = false
  q._issues = null
  markDirty()
  refreshGroups()
}

/** 一道题是不是启用的（老草稿里没有这个字段 = 启用） */
export function isEnabled(q: { disabled?: boolean }): boolean {
  return !q.disabled
}

/** 当前卷里启用的题数（侧栏、发布前的计数都用它） */
export function enabledCount(sheet: Sheet | null | undefined): number {
  return (sheet?.questions ?? []).filter(isEnabled).length
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

/* ------------------------------------------------------------------ 分组板：拖拽与批量操作 */

export type DropTarget =
  | { kind: 'pool' }
  | { kind: 'group'; start: number }
  | { kind: 'index'; uid: number; position: 'before' | 'after' }

/**
 * 把题目移动到池子或材料组。
 *
 * 数据口径不变：材料仍然「连续 + 全文相等」才算一组，所以拖进材料组时不能只改
 * `material`——必须把题挪到组内（拖到组尾 / 某道小题前），否则卷面上会裂成两组。
 * 拖进池子则清空材料、挪到卷尾。
 */
export function moveQuestionsTo(uids: number[], target: DropTarget) {
  const qs = currentQuestions()
  if (!qs.length) return
  const moving = qs.filter((q) => uids.includes(q._uid))
  if (!moving.length) return
  const movingSet = new Set(moving.map((q) => q._uid))

  let material = ''
  let anchorUid: number | null = null
  let beforeAnchor = false

  if (target.kind === 'group') {
    const g = state.groups.find((x) => x.start === target.start)
    if (!g) return
    material = qs[g.start]?.material ?? g.material
    // 组尾若也在拖动集合里，退到组内最后一道没被拖走的题，别把整批甩到卷末
    for (let i = g.end; i >= g.start; i--) {
      if (!movingSet.has(qs[i]._uid)) { anchorUid = qs[i]._uid; break }
    }
    // 整组都在拖：没有可依附的锚点，当作原地不动
    if (anchorUid === null) return
  } else if (target.kind === 'index') {
    const idx = qs.findIndex((q) => q._uid === target.uid)
    if (idx < 0) return
    const g = groupAt(state.groups, idx)
    material = g ? (qs[g.start]?.material ?? g.material) : ''
    anchorUid = target.uid
    beforeAnchor = target.position === 'before'
    if (movingSet.has(target.uid)) {
      // 目标本身也在拖动集合里：插到它前面最近一道没被拖走的题后面
      anchorUid = null
      beforeAnchor = false
      for (let i = idx - 1; i >= 0; i--) {
        if (!movingSet.has(qs[i]._uid)) { anchorUid = qs[i]._uid; break }
      }
    }
  }

  // 拖到自己身上：什么都不做，避免题在数组里「原地转一圈」
  if (anchorUid !== null && movingSet.has(anchorUid)) {
    if (moving.length === 1) return
  }

  const block = moving.slice()
  // 只有「独立题」才在数组里单独占位；按卷面顺序取出后整体插入
  const rest = qs.filter((q) => !movingSet.has(q._uid))
  for (const q of block) {
    q.material = material
    q._issues = null
  }

  let at = rest.length
  if (anchorUid !== null && !movingSet.has(anchorUid)) {
    const anchor = rest.findIndex((q) => q._uid === anchorUid)
    if (anchor >= 0) at = beforeAnchor ? anchor : anchor + 1
  }
  rest.splice(at, 0, ...block)
  qs.splice(0, qs.length, ...rest)

  state.focused = block[block.length - 1]._uid
  markDirty()
  refreshGroups()
}

/** 整组拖动：把一则材料连同它的小题一起移动到另一组前 / 后 */
export function moveGroupTo(start: number, targetStart: number, position: 'before' | 'after' = 'before') {
  if (start === targetStart) return
  const qs = currentQuestions()
  const from = state.groups.find((g) => g.start === start)
  const to = state.groups.find((g) => g.start === targetStart)
  if (!from || !to) return

  const block = qs.slice(from.start, from.end + 1)
  const rest = [...qs.slice(0, from.start), ...qs.slice(from.end + 1)]
  const anchorUid = position === 'before' ? qs[to.start]?._uid : qs[to.end]?._uid
  const anchor = rest.findIndex((q) => q._uid === anchorUid)
  const at = anchor < 0 ? rest.length : position === 'before' ? anchor : anchor + 1
  rest.splice(at, 0, ...block)
  qs.splice(0, qs.length, ...rest)

  markDirty()
  refreshGroups()
}

/** 把选中的题并进某个材料组（追加到组尾） */
export function assignSelectedToGroup(start: number) {
  const uids = selectedUids()
  if (!uids.length) return
  moveQuestionsTo(uids, { kind: 'group', start })
  clearSelection()
}

/** 把选中的题移出材料组，回到「未分组」 */
export function ungroupSelected() {
  const uids = selectedUids()
  if (!uids.length) return
  moveQuestionsTo(uids, { kind: 'pool' })
  clearSelection()
}

/** 批量「删除」选中的题 = 批量禁用；还没发布过的新题直接移除 */
export function disableSelected() {
  const uids = new Set(state.selected)
  if (!uids.size) return
  const qs = currentQuestions()
  for (let i = qs.length - 1; i >= 0; i--) {
    if (!uids.has(qs[i]._uid)) continue
    if (qs[i].id) {
      qs[i].disabled = true
      qs[i]._issues = null
    } else {
      qs.splice(i, 1)
    }
  }
  state.expanded = state.expanded.filter((x) => !uids.has(x))
  if (state.focused !== null && uids.has(state.focused)) state.focused = null
  clearSelection()
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

/**
 * 改材料组正文：写回组内每一题。
 *
 * `refresh = false` 供材料编辑区连续输入时用——分组要等失焦再重算，
 * 否则材料被删空的瞬间这一组就没了，输入框和焦点会一起消失。
 */
export function setGroupMaterial(startIndex: number, text: string, refresh = true) {
  const qs = currentQuestions()
  const g = state.groups.find((x) => x.start === startIndex)
  if (!g) return
  for (let i = g.start; i <= g.end; i++) {
    qs[i].material = text
    qs[i]._issues = null
  }
  markDirty()
  if (refresh) refreshGroups()
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

/** 当前打开的组编号；新建还没发布的组是 0（图片先落手机端的 draft 目录） */
export function currentSetId(): number {
  const id = state.paper?.id ?? 0
  return id > 0 ? id : 0
}

export async function uploadImage(file: File, replacePath?: string): Promise<string | null> {
  try {
    const path = await api.upload(file, currentSetId(), replacePath)
    toast(replacePath ? `已替换这张图：${path}` : `图片已存进手机：${path}`, 'ok')
    // 资源面板开着时顺手刷新，别让新图要手动点「刷新」才出现
    if (state.resourceSetId === currentSetId()) void loadResources()
    return path
  } catch (e) {
    toast(e instanceof Error ? e.message : '图片上传失败', 'err')
    return null
  }
}

/* ------------------------------------------------------------------ 图片资源（按组编号分目录） */

/** 拉当前这组的图片列表；新组（id=0）读的是手机端的 draft 目录 */
export async function loadResources(): Promise<void> {
  const setId = currentSetId()
  if (!state.paper) {
    state.resources = []
    state.resourceSetId = setId
    return
  }
  state.resourceLoading = true
  try {
    const r = await api.resources(setId)
    state.resources = r.files ?? []
    state.resourceSetId = setId
  } catch {
    state.resources = []
    state.resourceSetId = setId
  } finally {
    state.resourceLoading = false
  }
}

export async function deleteResource(file: ResourceFile): Promise<void> {
  const ok = await askConfirm(
    '删掉这张图？',
    `${file.path}\n\n题目里引用它的地方会显示成掉的图；要换图可以重新上传。`,
    '删图',
  )
  if (!ok) return
  try {
    await api.deleteResource(file.path)
    state.resources = state.resources.filter((x) => x.path !== file.path)
    toast('图片已删', 'ok')
  } catch (e) {
    toast(e instanceof Error ? e.message : '删图失败', 'err')
  }
}

/**
 * 整理本组图片：把老的平铺图、导入试卷包留下的绝对路径复制进 `images/<组编号>/`，
 * 并让手机端把题库里的图片路径改过去。
 *
 * 有未发布改动时不做：迁移会重写题目行，库里改完而网页上还留着草稿，
 * 两边的图片路径就对不上了。先发布再整理。
 */
export async function migrateImages(): Promise<boolean> {
  const setId = currentSetId()
  if (setId <= 0) {
    toast('这一组还没发布：先把图片存进手机、发布之后再整理', 'err')
    return false
  }
  if (state.dirty) {
    toast('先把当前改动发布到 App，再整理图片', 'err')
    return false
  }
  const ok = await askConfirm(
    '把图片整理进本组目录？',
    '会把题目引用到的散图复制进 images/<组编号>/，并把题库里的路径改成新位置。\n\n' +
      '题目内容不变；源文件不删，没人引用的散图也不会动。',
    '开始整理',
  )
  if (!ok) return false

  ui.busy = true
  try {
    const r = await api.migrateImages(setId)
    await openSet(setId, { force: true })
    const tail = r.missing ? ` · ${r.missing} 处引用找不到文件` : ''
    toast(`整理完成：搬了 ${r.moved} 张，已在组目录 ${r.reused} 张${tail}`, r.missing ? '' : 'ok')
    return true
  } catch (e) {
    toast(e instanceof Error ? e.message : '整理图片失败', 'err')
    return false
  } finally {
    ui.busy = false
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

/** 停用一张工作表（不是删）：发布后后端只把卷标成停用，题与记录都留着 */
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
  clearSelection()
  resetFilters()
  refreshGroups()
}

/* ------------------------------------------------------------------ 筛选 */

/** 「没填一级 / 二级题型」在下拉里单独列一项：这些题恰恰最该被筛出来补 */
export const TYPE_NONE_VALUE = '__none__'

/** 当前这卷各作答形式的题数，下拉里直接带出来，省得盲选 */
export function typeCounts(): Partial<Record<QuestionType, number>> {
  const out: Partial<Record<QuestionType, number>> = {}
  for (const q of currentQuestions()) out[q.type] = (out[q.type] ?? 0) + 1
  return out
}

/**
 * 一级题型下拉：值取归一后的科目名（`行测·资料分析` → `资料分析`），
 * 所以同一大类的不同写法会并成一项；认不出的自由文本原样列出来。
 */
export function level1Options(): { value: string; label: string; count: number }[] {
  const map = new Map<string, number>()
  let missing = 0
  for (const q of currentQuestions()) {
    const s = typeName(q.subject)
    if (!s) { missing++; continue }
    map.set(s, (map.get(s) ?? 0) + 1)
  }
  const out = [...map.entries()]
    .map(([value, count]) => ({ value, label: value, count }))
    .sort((a, b) => b.count - a.count || a.label.localeCompare(b.label, 'zh'))
  if (missing) out.push({ value: TYPE_NONE_VALUE, label: '未填一级', count: missing })
  // 选中的一级在这卷里已经不存在时（改过名 / 换过卷）补一条空计数，
  // 否则 select 找不到对应 option 会显示成空白，看着像筛选坏了
  const cur = state.subjectFilter
  if (cur !== 'all' && !out.some((o) => o.value === cur)) {
    out.push({ value: cur, label: cur === TYPE_NONE_VALUE ? '未填一级' : cur, count: 0 })
  }
  return out
}

/** 二级题型下拉：一级筛选生效时只列这一级下的二级，否则列全卷的二级 */
export function level2Options(): { value: string; label: string; count: number }[] {
  const level1 = state.subjectFilter
  const map = new Map<string, number>()
  let missing = 0
  for (const q of currentQuestions()) {
    if (level1 !== 'all') {
      const s = typeName(q.subject)
      if (level1 === TYPE_NONE_VALUE ? s !== '' : s !== level1) continue
    }
    const t = typeName(q.knowledge)
    if (!t) { missing++; continue }
    map.set(t, (map.get(t) ?? 0) + 1)
  }
  const out = [...map.entries()]
    .map(([value, count]) => ({ value, label: value, count }))
    .sort((a, b) => b.count - a.count || a.label.localeCompare(b.label, 'zh'))
  if (missing) out.push({ value: TYPE_NONE_VALUE, label: '未填二级', count: missing })
  const cur = state.subTopicFilter
  if (cur !== 'all' && !out.some((o) => o.value === cur)) {
    out.push({ value: cur, label: cur === TYPE_NONE_VALUE ? '未填二级' : cur, count: 0 })
  }
  return out
}

/** 除搜索外还有筛选条件生效（工具栏据此显示筛出题数和「清除筛选」） */
export function filtersActive(): boolean {
  return state.filter !== 'all' || state.typeFilter !== 'all' ||
    state.subjectFilter !== 'all' || state.subTopicFilter !== 'all'
}

/** 切卷 / 换组时清掉筛选项：题型每一卷各算各的，留着上一卷的选项只会筛出空列表 */
export function resetFilters() {
  state.filter = 'all'
  state.typeFilter = 'all'
  state.subjectFilter = 'all'
  state.subTopicFilter = 'all'
}

/** 筛选结果按依赖缓存：列表每次渲染都要这份索引，直接算等于每帧扫全卷 */
const visibleCache = computed(() => {
  const qs = currentQuestions()
  const groups = state.groups
  const kw = state.query.trim().toLowerCase()
  const type = state.typeFilter
  const subject = state.subjectFilter
  const topic = state.subTopicFilter
  const out: number[] = []
  qs.forEach((q, i) => {
    if (type !== 'all' && q.type !== type) return
    if (subject !== 'all') {
      const s = typeName(q.subject)
      if (subject === TYPE_NONE_VALUE ? s !== '' : s !== subject) return
    }
    if (topic !== 'all') {
      const t = typeName(q.knowledge)
      if (topic === TYPE_NONE_VALUE ? t !== '' : t !== topic) return
    }
    if (state.filter !== 'all') {
      // 看「有问题的题 / 缺答案」时跳过禁用题：它不参与发布，列出来只是噪音
      if (q.disabled) return
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

/* ------------------------------------------------------------------ 导入文件（Excel / 答案后补） */

/**
 * 工具条「导入文件」的入口：文件选择完在这里解析并弹预览。
 *
 * 解析全在前端做（importx.ts），发到手机的仍是普通 JSON——手机端不需要认识 Excel。
 * 文件里认得出题干/选项列 → 题目导入；只有题号/答案/解析 → 按当前卷做答案后补。
 */
export async function handleImportFiles(files: FileList | null): Promise<void> {
  const file = files?.[0]
  // 同一个文件选两次不触发 change，用完清掉
  const input = document.getElementById('file-import') as HTMLInputElement | null
  if (input) input.value = ''
  if (!file) return
  if (!state.paper) { toast('先在左边打开一组，再导入', 'err'); return }

  const fileName = file.name
  if (!/\.xlsx$/i.test(fileName)) {
    toast('只支持 Excel（.xlsx）文件，WPS 另存为 .xlsx 即可', 'err')
    return
  }

  ui.busy = true
  try {
    const data = await file.arrayBuffer()
    const result = parseWorkbook(data, fileName.replace(/\.xlsx$/i, ''))
    if (result.kind === 'empty') {
      await showErrors('导入没成功', result.error)
      return
    }
    if (result.kind === 'questions') {
      const total = result.sheets.reduce((s, x) => s + x.questions.length, 0)
      if (!total) {
        await showErrors('导入没成功', result.notes.map((n) => n.message).join('\n') || '没解析出题目')
        return
      }
      ui.importPreview = {
        fileName,
        mode: 'questions',
        sheets: result.sheets,
        entries: [],
        plan: null,
        notes: result.notes,
        target: result.sheets.length === 1 ? 'current' : 'new',
      }
    } else {
      const qs = currentQuestions()
      if (!qs.length) {
        toast('当前工作表一道题都没有，答案没处可补', 'err')
        return
      }
      ui.importPreview = {
        fileName,
        mode: 'backfill',
        sheets: [],
        entries: result.entries,
        plan: matchBackfill(result.entries, qs),
        notes: result.notes,
        target: 'current',
      }
    }
    ui.modal = {
      kind: 'import',
      title: '导入文件',
      okText: ui.importPreview.mode === 'backfill' ? '补进当前卷' : '导入',
      resolve: () => {},
    }
  } catch (e) {
    toast(e instanceof Error ? e.message : '读取文件失败', 'err')
  } finally {
    ui.busy = false
  }
}

/** 工作表重名时补个序号：2023国考 → 2023国考 2 */
function uniqueSheetName(name: string): string {
  const used = new Set((state.paper?.sheets ?? []).map((s) => s.name))
  if (!used.has(name)) return name
  let i = 2
  while (used.has(`${name} ${i}`)) i++
  return `${name} ${i}`
}

/** 预览弹层里点「导入 / 补进当前卷」：真正把解析结果写进当前内容 */
export function confirmImport(): void {
  const p = ui.importPreview
  ui.importPreview = null
  ui.modal = null
  if (!p) return

  if (p.mode === 'questions') {
    const all = p.sheets.flatMap((s) => s.questions)
    if (!all.length) return
    if (p.target === 'current' && p.sheets.length === 1) {
      insertQuestions(all)
      const miss = all.filter((q) => !q.answer).length
      toast(`已追加 ${all.length} 题${miss ? `（缺答案 ${miss} 题）` : ''}，记得发布`, 'ok')
    } else {
      for (const s of p.sheets) {
        if (!s.questions.length) continue
        state.paper!.sheets.push({ name: uniqueSheetName(s.name), questions: s.questions })
      }
      const added = p.sheets.filter((s) => s.questions.length).length
      // 指到新加的第一张表，让用户立刻看到导进来的东西
      state.sheet = state.paper!.sheets.length - added
      state.expanded = []
      state.focused = null
      markDirty()
      refreshGroups()
      const total = p.sheets.reduce((sum, s) => sum + s.questions.length, 0)
      toast(`已加 ${added} 张工作表 · 共 ${total} 题，记得发布`, 'ok')
    }
    return
  }

  // 答案后补：只动答案和解析，题目内容一个字不改
  const plan = p.plan
  if (!plan || !plan.matches.length) {
    toast('一条都没匹配上：文件里的题号 / 题干和当前卷对不上', 'err')
    return
  }
  const qs = currentQuestions()
  let wroteAnswer = 0
  let wroteAnalysis = 0
  for (const m of plan.matches) {
    const q = qs.find((x) => x._uid === m.uid)
    if (!q) continue
    if (m.answer) { q.answer = m.answer; wroteAnswer++ }
    if (m.analysis) { q.analysis = m.analysis; wroteAnalysis++ }
    q._issues = null
  }
  markDirty()
  toast(
    `补了 ${wroteAnswer} 题答案${wroteAnalysis ? `、${wroteAnalysis} 题解析` : ''}` +
      (plan.unmatched.length ? `；${plan.unmatched.length} 条没匹配上已忽略` : ''),
    'ok',
  )
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
      '回滚会覆盖现在这一组；现在的内容也会先存一版，滚错了还能再滚回来。旧卷的作答记录不会跟着回来。' + '\n\n' +
      '和发布一样，要等手机点确认才会执行。',
    '发到手机确认',
  )
  if (!ok) return
  if (ui.waitingId) { toast('还有一条请求在等手机处理，等它结束再发', 'err'); return }
  ui.busy = true
  try {
    const r = await api.restoreVersion(v.id, state.paper?.id ?? 0, state.paper?.name ?? '')
    const result = 'needConfirm' in r
      ? await waitPhone(
        'restore',
        r,
        `回滚「${r.title}」`,
        '回滚会把这一组换成选中的那一版；换之前的内容也会先存一版。',
      )
      : r
    if (!result) return
    await loadSets()
    if (state.paper) await openSet((result as PublishResponse).setId, { force: true })
    toast(`已回滚到 ${formatTime(v.savedAt)} 那一版 · ${(result as PublishResponse).questionCount} 题`, 'ok')
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
    ui.waitingKind = 'delete'
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
  ui.waitingKind = null
  if (ui.modal?.kind === 'waiting') closeModal()
}

/** 网页上撤回：手机还没点的话，那边就不弹了 */
export async function cancelWaiting(): Promise<void> {
  const id = ui.waitingId
  if (!id) return
  const kind = ui.waitingKind
  finishWaiting()
  try {
    const r = kind === 'delete' ? await api.cancelDelete(id) : await api.cancelPublish(id)
    // 手机上刚点过确认的话，服务端会明确回 ok=false——这时候别说「已撤回」
    if (r.ok === false) toast('手机上已经点了确认，撤不回来了', 'err')
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

/* ------------------------------------------------------------------ 背诵词库（政治 / 常识） + 词库（词条） */

/**
 * 背诵编辑器自己的状态。与题库编辑完全独立：
 * `subject = 'words'` 是词条词库（DB 逐条改），其余是政治 / 常识两块背诵内容
 * （整份编辑、整份保存，保存要过手机确认）。
 */
export const recite = reactive({
  subjects: [] as ReciteSubjectInfo[],
  /** '' = 还没选；'words' = 词条词库；'politics' / 'common' = 背诵内容 */
  subject: '',
  catalog: null as ReciteCatalog | null,
  loading: false,
  /** 有没保存到手机的改动（保存成功后清掉） */
  dirty: false,
  query: '',
  catFilter: 'all' as string,
  /** 正在展开编辑的卡片 id */
  expanded: [] as string[],

  // ---- 词条词库 ----
  words: [] as WordItem[],
  wordsTotal: 0,
  wordsTags: [] as { name: string; count: number }[],
  wordsCounts: {} as Record<string, number>,
  wordQuery: '',
  wordCategory: 'all' as string,
  wordTag: 'all' as string,
  /** 词条两千多条，一次全渲染会明显卡顿：按页切片，只在列表区渲染当前页 */
  wordPage: 1,
  wordPageSize: 50,
  /** 正在展开编辑的词条 id（0 = 新词条还没落库） */
  wordExpanded: [] as number[],
})

/** 背诵卡片的本地 id 前缀：新卡在保存前都是这个开头，落盘前由服务器校验唯一 */
let reciteSeq = 0

function nextReciteCardId(): string {
  reciteSeq++
  return `web-${Date.now().toString(36)}-${reciteSeq.toString(36)}`
}

/* ---------------- 模式切换 ---------------- */

export async function switchMode(mode: 'paper' | 'recite'): Promise<void> {
  if (state.mode === mode) return
  if (mode === 'recite' && (recite.dirty || library.dirty)) {
    const ok = await askConfirm(
      '背诵词库还有没保存的改动',
      '切回题库编辑不会丢内容，但改动要记得保存；确定切换吗？',
      '切换',
    )
    if (!ok) return
  }
  state.mode = mode
  if (mode === 'recite' && !recite.subjects.length) {
    await loadReciteSubjects()
    // 第一次进来默认打开政治大纲（App 政治页就是这份库），别让人对着空页面猜
    if (!recite.subject) await openReciteSubject('library')
  }
}

/* ---------------- 列表与打开 ---------------- */

export async function loadReciteSubjects(): Promise<void> {
  try {
    const r = await api.reciteSubjects()
    recite.subjects = r.subjects ?? []
  } catch (e) {
    toast(e instanceof Error ? e.message : '读取背诵词库失败', 'err')
  }
}

/** 打开一块内容。'words' = 词条词库，'mindmap' = 思维导图，其余 = 背诵内容 */
export async function openReciteSubject(id: string, opts: { force?: boolean } = {}): Promise<void> {
  if (recite.subject === id && !opts.force) return
  if ((recite.dirty || mindmap.dirty || library.dirty) && !opts.force) {
    const ok = await askConfirm(
      '还有没保存的改动',
      '换一块内容不会丢改动（改动还在内存里），但先保存更稳。直接换吗？',
      '直接换',
    )
    if (!ok) return
  }
  recite.subject = id
  recite.query = ''
  recite.catFilter = 'all'
  recite.expanded = []
  recite.wordExpanded = []
  recite.wordQuery = ''
  recite.wordCategory = 'all'
  recite.wordTag = 'all'
  recite.wordPage = 1
  recite.catalog = null
  recite.words = []
  if (id === 'words') {
    await loadWords()
  } else if (id === 'mindmap') {
    await loadMindMap(opts.force === true)
  } else if (id === 'library') {
    await loadLibrary(opts.force === true)
  } else {
    recite.loading = true
    try {
      recite.catalog = await api.reciteCatalog(id)
      recite.dirty = false
    } catch (e) {
      toast(e instanceof Error ? e.message : '读取失败', 'err')
    } finally {
      recite.loading = false
    }
  }
}

/* ---------------- 背诵卡片：编辑 / 校验 ---------------- */

/** 单卡检查：和手机端落盘校验同一套口径，发布前就地兜住 */
export function reciteCardIssues(c: ReciteCard): string[] {
  const out: string[] = []
  if (!c.text.trim()) out.push('正文为空')
  const blanks = (c.text.match(/____/g) ?? []).length
  if (blanks !== c.answers.length) {
    out.push(`正文有 ${blanks} 个 ____，但给了 ${c.answers.length} 个答案`)
  }
  if (c.answers.some((a) => !a.trim())) out.push('有空答案')
  if (!recite.catalog?.categories.some((x) => x.id === c.cat)) out.push('所属分类不存在')
  return out
}

export function reciteIssueCount(): number {
  return (recite.catalog?.cards ?? []).reduce((n, c) => n + reciteCardIssues(c).length, 0)
}

export function reciteDirty(): boolean {
  return recite.dirty
}

export function markReciteDirty() {
  recite.dirty = true
}

export function toggleReciteCard(id: string) {
  recite.expanded = recite.expanded.includes(id)
    ? recite.expanded.filter((x) => x !== id)
    : [...recite.expanded, id]
}

export function addReciteCard(catId?: string): ReciteCard | null {
  const cat = recite.catalog
  if (!cat) return null
  // 优先用调用方给的分类；没给就落进当前筛选的分类，再退到第一个分类
  const target = catId ?? (recite.catFilter !== 'all' ? recite.catFilter : cat.categories[0]?.id)
  if (!target) { toast('先建一个分类', 'err'); return null }
  const card: ReciteCard = { id: nextReciteCardId(), cat: target, text: '', answers: [], note: '', source: '' }
  cat.cards.push(card)
  recite.expanded = [card.id]
  markReciteDirty()
  return card
}

export function removeReciteCard(id: string) {
  const cat = recite.catalog
  if (!cat) return
  cat.cards = cat.cards.filter((c) => c.id !== id)
  recite.expanded = recite.expanded.filter((x) => x !== id)
  markReciteDirty()
}

export function updateReciteCard(id: string, patch: Partial<ReciteCard>) {
  const c = recite.catalog?.cards.find((x) => x.id === id)
  if (!c) return
  Object.assign(c, patch)
  markReciteDirty()
}

/** 换填空答案列表：数量要与正文里的 ____ 对上（界面负责让两处一起改） */
export function setReciteCardAnswers(id: string, answers: string[]) {
  updateReciteCard(id, { answers })
}

/** 把选中的一段正文变成填空：正文加 ____，答案追加一项 */
export function blankReciteSelection(id: string, term: string) {
  const c = recite.catalog?.cards.find((x) => x.id === id)
  if (!c || !term.trim()) return
  c.text = `${c.text} ____ `
  c.answers.push(term.trim())
  markReciteDirty()
}

export function moveReciteCard(id: string, catId: string) {
  updateReciteCard(id, { cat: catId })
}

/* ---------------- 背诵分类 ---------------- */

function reciteCategoryId(name: string): string {
  const base = (recite.catalog?.subject === 'common' ? 'cs' : 'p') +
    name.replace(/[^\u4e00-\u9fa5a-zA-Z0-9]/g, '').slice(0, 6) || 'cat'
  let id = base
  let i = 2
  while (recite.catalog?.categories.some((c) => c.id === id)) id = `${base}${i++}`
  return id
}

export async function addReciteCategory(): Promise<void> {
  if (!recite.catalog) return
  const group = (await askText({
    title: '新分类 · 所属分组',
    label: '二级分组标题（弹层里的分组行）。留空 = 落到「未分组」',
    value: recite.catalog.categories[0]?.group ?? '',
    rows: 1,
  }))
  if (group === null) return
  const name = await askText({ title: '新分类 · 名称', label: '分类名（范围弹层里的一行）', rows: 1 })
  if (!name?.trim()) return
  recite.catalog.categories.push({
    id: reciteCategoryId(name.trim()),
    name: name.trim(),
    group: group.trim(),
  })
  markReciteDirty()
  toast('分类已加，记得「＋ 新卡」往里放内容', 'ok')
}

export async function renameReciteCategory(id: string): Promise<void> {
  const c = recite.catalog?.categories.find((x) => x.id === id)
  if (!c) return
  const name = await askText({ title: '改分类名', value: c.name, rows: 1 })
  if (name?.trim()) { c.name = name.trim(); markReciteDirty() }
  const group = await askText({
    title: '改所属分组',
    label: '二级分组标题。留空 = 未分组',
    value: c.group,
    rows: 1,
  })
  if (group !== null && group.trim() !== c.group) { c.group = group.trim(); markReciteDirty() }
}

/** 只许删空分类：带着卡删会把卡挂到不存在的分类上 */
export async function removeReciteCategory(id: string): Promise<void> {
  const cat = recite.catalog
  const c = cat?.categories.find((x) => x.id === id)
  if (!cat || !c) return
  const n = cat.cards.filter((x) => x.cat === id).length
  if (n > 0) { toast(`「${c.name}」里还有 ${n} 张卡，先移走或删掉再删分类`, 'err'); return }
  const ok = await askConfirm(`删除分类「${c.name}」？`, '分类是空的，删掉不影响任何卡片。', '删除')
  if (!ok) return
  cat.categories = cat.categories.filter((x) => x.id !== id)
  if (recite.catFilter === id) recite.catFilter = 'all'
  markReciteDirty()
}

/* ---------------- 背诵筛选 ---------------- */

/** 当前筛选下的卡片（分类 + 搜索） */
export const reciteVisibleCards = computed<ReciteCard[]>(() => {
  const cards = recite.catalog?.cards ?? []
  const cat = recite.catFilter
  const kw = recite.query.trim().toLowerCase()
  return cards.filter((c) => {
    if (cat !== 'all' && c.cat !== cat) return false
    if (kw) {
      const hay = `${c.text} ${c.answers.join(' ')} ${c.note} ${c.source}`.toLowerCase()
      if (!hay.includes(kw)) return false
    }
    return true
  })
})

/* ---------------- 背诵保存 / 重置 ---------------- */

/**
 * 整份保存。网页没有写权限：先在服务端校验结构，再登记一条待确认请求，
 * 手机上点「确认保存」才落盘（和发布同一套流程）。
 */
export async function saveRecite(): Promise<boolean> {
  const cat = recite.catalog
  if (!cat) return false
  const issues = reciteIssueCount()
  if (issues > 0) {
    toast(`有 ${issues} 处校验问题（空正文 / ____ 与答案数对不上），改完再保存`, 'err')
    return false
  }
  if (!cat.title.trim()) { toast('先给这块内容起个标题', 'err'); return false }
  if (ui.waitingId) {
    toast('还有一条请求在等手机处理，等它结束再发', 'err')
    return false
  }

  ui.busy = true
  try {
    const payload = {
      subject: cat.subject,
      title: cat.title,
      intro: cat.intro,
      categories: cat.categories.map((c) => ({ id: c.id, name: c.name, group: c.group })),
      cards: cat.cards.map((c) => ({ ...c })),
    }
    const r = await api.reciteSave(payload)
    const result = 'needConfirm' in r
      ? await waitPhone(
        'recite-save',
        r,
        `保存背诵词库「${r.title}」`,
        '确认后 App 里的这块背诵内容会换成网页上这一版。',
      )
      : r
    if (!result) return false
    recite.dirty = false
    await loadReciteSubjects()
    await openReciteSubject(cat.subject, { force: true })
    const cardCount = 'cardCount' in result ? result.cardCount : undefined
    const savedTitle = 'title' in result ? result.title : cat.title
    toast(`已保存「${savedTitle ?? cat.title}」· ${cardCount ?? '?'} 张卡片，回手机就能背`, 'ok')
    return true
  } catch (e) {
    await showErrors('保存没成功', e instanceof Error ? e.message : '保存失败')
    return false
  } finally {
    ui.busy = false
  }
}

/** 重置为 App 内置内容：删掉覆盖文件，网页上的改动就没了 */
export async function resetRecite(): Promise<void> {
  const cat = recite.catalog
  if (!cat) return
  const ok = await askConfirm(
    `把「${cat.title}」重置回 App 内置内容？`,
    '会删掉网页 / AI 存过的那版，回到 APK 里内置的词库。' +
      (cat.hasEdits ? '\n\n这块内容现在有覆盖版，重置后当前网页上的未保存改动也一并丢弃。' : ''),
    '发到手机确认',
  )
  if (!ok) return
  if (ui.waitingId) { toast('还有一条请求在等手机处理，等它结束再发', 'err'); return }
  ui.busy = true
  try {
    const r = await api.reciteReset(cat.subject)
    const result = 'needConfirm' in r
      ? await waitPhone('recite-reset', r, `重置「${cat.title}」`, '重置会删掉覆盖版，回到内置内容。')
      : r
    if (!result) return
    recite.dirty = false
    await loadReciteSubjects()
    await openReciteSubject(cat.subject, { force: true })
    const wasReset = 'reset' in result ? result.reset : undefined
    toast(wasReset === false ? '本来就没有覆盖版，内置内容原样' : '已重置回内置内容', 'ok')
  } catch (e) {
    toast(e instanceof Error ? e.message : '重置失败', 'err')
  } finally {
    ui.busy = false
  }
}

/* ---------------- 词库（词条） ---------------- */

export const WORD_CATEGORY_LABEL: Record<string, string> = {
  idiom: '成语',
  word: '实词',
  other: '其他',
}

export async function loadWords(): Promise<void> {
  recite.loading = true
  try {
    const r = await api.words({
      q: recite.wordQuery,
      category: recite.wordCategory,
      tag: recite.wordTag,
    })
    recite.words = r.words ?? []
    recite.wordsTotal = r.total ?? 0
    recite.wordsTags = r.tags ?? []
    recite.wordsCounts = r.counts ?? {}
    // 筛选 / 搜索后条数变少，当前页可能已经越界
    recite.wordPage = Math.min(recite.wordPage, wordPageCount())
  } catch (e) {
    toast(e instanceof Error ? e.message : '读取词库失败', 'err')
  } finally {
    recite.loading = false
  }
}

export function toggleWord(id: number) {
  recite.wordExpanded = recite.wordExpanded.includes(id)
    ? recite.wordExpanded.filter((x) => x !== id)
    : [...recite.wordExpanded, id]
}

/** 当前筛选结果的总页数（至少 1 页，空列表也能显示「第 1 / 1 页」） */
export function wordPageCount(): number {
  return Math.max(1, Math.ceil(recite.words.length / recite.wordPageSize))
}

/** 跳页：越界直接夹到首尾页 */
export function setWordPage(page: number) {
  recite.wordPage = Math.min(Math.max(1, page), wordPageCount())
}

export function addWordRow(): void {
  const blank: WordItem = {
    id: 0, text: '', pinyin: '', initial: '#', category: 'idiom',
    tag: '', meaning: '', example: '', source: '',
  }
  recite.words = [blank, ...recite.words]
  recite.wordExpanded = [0]
  recite.wordPage = 1
}

export function updateWordRow(id: number, patch: Partial<WordItem>) {
  const w = recite.words.find((x) => x.id === id)
  if (!w) return
  Object.assign(w, patch)
}

/** 保存一条词条：直接写库（逐条小改不过手机确认），失败弹原因 */
export async function saveWordItem(w: WordItem): Promise<void> {
  if (!w.text.trim()) { toast('词条不能为空', 'err'); return }
  ui.busy = true
  try {
    const saved = await api.wordSave({ ...w })
    const i = recite.words.findIndex((x) => x.id === w.id)
    if (i >= 0) recite.words.splice(i, 1, saved)
    toast(`已保存「${saved.text}」${w.id === 0 ? '，词库里能搜到了' : ''}`, 'ok')
    recite.wordExpanded = recite.wordExpanded.filter((x) => x !== w.id)
    // 分类 / 标签计数变了，后台刷一遍筛选数据（不打断编辑）
    void loadWords()
  } catch (e) {
    toast(e instanceof Error ? e.message : '保存失败', 'err')
  } finally {
    ui.busy = false
  }
}

export async function deleteWordItem(w: WordItem): Promise<void> {
  if (!w.id) {
    // 没落过库的新行：撤掉就行
    recite.words = recite.words.filter((x) => x !== w)
    recite.wordExpanded = recite.wordExpanded.filter((x) => x !== w.id)
    return
  }
  const ok = await askConfirm(`删除词条「${w.text}」？`, '词库里直接删掉，不过手机确认；删错可以重新加回来。', '删除')
  if (!ok) return
  try {
    await api.wordDelete(w.id)
    recite.words = recite.words.filter((x) => x.id !== w.id)
    recite.wordExpanded = recite.wordExpanded.filter((x) => x !== w.id)
    recite.wordsTotal = Math.max(0, recite.wordsTotal - 1)
    toast('词条已删', 'ok')
    void loadWords()
  } catch (e) {
    toast(e instanceof Error ? e.message : '删除失败', 'err')
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
  /** 旧版草稿里有 source / local 字段（本地试卷包模式），现在只认手机题库，读到就忽略 */
  source?: string
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
  // 本地试卷包模式已经删了：它留下的草稿没有落库对象，读到就丢掉
  if (d.source === 'local') { clearDraft(); return false }
  const age = Date.now() - (d.savedAt ?? 0)
  const when = age > 24 * 3600_000
    ? `${formatTime(d.savedAt)}（${Math.floor(age / 86_400_000)} 天前）`
    : formatTime(d.savedAt)
  const ok = await askConfirm(
    '接着上次改？',
    `本地存着一份 ${when} 的未发布改动：「${d.name}」共 ${d.sheets.length} 卷。\n\n` +
      '恢复后再点「发布到 App」才会落库；不要的话就从当前内容打开（选「不要」会删掉这份草稿）。',
    '接着改',
  )
  if (!ok) { clearDraft(); return false }
  state.paper = {
    id: d.setId ?? 0,
    name: d.name,
    sheets: d.sheets.map((s) => ({
      name: s.name,
      // 老数据里没有 optionLayout：补成 ''（单列），后面每题切换时才有初值可读
      questions: (s.questions ?? []).map((q) => ({
        ...q,
        optionLayout: q.optionLayout ?? '',
        _uid: nextUid(),
        _issues: null,
      })),
    })),
  }
  if (!state.paper.sheets.length) state.paper.sheets = [{ name: '试卷', questions: [] }]
  state.sheet = 0
  state.expanded = []
  state.focused = null
  state.dirty = true
  clearSelection()
  ui.draftSavedAt = d.savedAt ?? 0
  refreshGroups()
  return true
}

/* ------------------------------------------------------------------ 思维导图（整份读、整份写） */

/**
 * 导图编辑器自己的状态。与题库编辑、背诵词库都独立：
 * 整份读出来在前端改，一次提交整份回去；保存要过手机确认。
 *
 * 展开状态只活在内存里（编辑一下路径就会变，持久化反而错位）。
 */
export const mindmap = reactive({
  catalog: null as MindMapCatalog | null,
  loading: false,
  /** 有没保存到手机的改动（保存成功后清掉） */
  dirty: false,
  /** 顶部板块筛选；'all' = 全部板块 */
  section: 'all',
  /** 展开的主题 id */
  openTopics: [] as string[],
  /** 节点展开的覆盖：没点过的按深度决定。key = "主题id#路径" */
  openNodes: {} as Record<string, boolean>,
})

/** 默认摊开到第几层：0 中心主题 / 1 一级分支 / 2 分支的要点 */
export const MINDMAP_DEFAULT_OPEN_DEPTH = 2

export function markMindMapDirty() {
  mindmap.dirty = true
}

export async function loadMindMap(force = false): Promise<void> {
  if (mindmap.catalog && !force) return
  mindmap.loading = true
  try {
    mindmap.catalog = await api.mindmap()
    mindmap.dirty = false
    mindmap.section = 'all'
    mindmap.openTopics = []
    mindmap.openNodes = {}
  } catch (e) {
    toast(e instanceof Error ? e.message : '读取导图失败', 'err')
  } finally {
    mindmap.loading = false
  }
}

/** 当前筛选下的板块（保留板块外壳，界面上要按板块分段显示） */
export const mindMapVisibleSections = computed<MindMapSection[]>(() => {
  const sections = mindmap.catalog?.sections ?? []
  return mindmap.section === 'all' ? sections : sections.filter((s) => s.id === mindmap.section)
})

/** 命中搜索的节点数：用于「该主题下有 N 处命中」的提示 */
export function mindMapCount(nodes: MindMapNode[]): number {
  return nodes.reduce((n, x) => n + 1 + mindMapCount(x.children ?? []), 0)
}

/** 按索引链定位：返回所在兄弟数组与下标，越界一律 null */
export function mindMapLocate(
  nodes: MindMapNode[],
  path: number[],
): { list: MindMapNode[]; index: number } | null {
  if (!path.length) return null
  let list = nodes
  for (let i = 0; i < path.length - 1; i++) {
    const node = list[path[i]]
    if (!node) return null
    node.children = node.children ?? []
    list = node.children
  }
  const index = path[path.length - 1]
  if (!list[index]) return null
  return { list, index }
}

export function mindMapNodeAt(topic: MindMapTopic, path: number[]): MindMapNode | null {
  const found = mindMapLocate(topic.nodes, path)
  return found ? found.list[found.index] : null
}

export function mindMapTopicById(id: string): MindMapTopic | null {
  for (const s of mindmap.catalog?.sections ?? []) {
    const t = s.topics.find((x) => x.id === id)
    if (t) return t
  }
  return null
}

export function mindMapSectionOf(topicId: string): MindMapSection | null {
  return (mindmap.catalog?.sections ?? []).find((s) => s.topics.some((t) => t.id === topicId)) ?? null
}

/* ---------------- 展开 / 收起 ---------------- */

export function mindMapNodeKey(topicId: string, path: number[]): string {
  return `${topicId}#${path.join('.')}`
}

export function mindMapIsOpen(topicId: string, path: number[], depth: number): boolean {
  const v = mindmap.openNodes[mindMapNodeKey(topicId, path)]
  return v ?? depth < MINDMAP_DEFAULT_OPEN_DEPTH
}

export function toggleMindMapNode(topicId: string, path: number[], depth: number) {
  const key = mindMapNodeKey(topicId, path)
  const cur = mindmap.openNodes[key] ?? depth < MINDMAP_DEFAULT_OPEN_DEPTH
  mindmap.openNodes[key] = !cur
}

export function toggleMindMapTopic(id: string) {
  mindmap.openTopics = mindmap.openTopics.includes(id)
    ? mindmap.openTopics.filter((x) => x !== id)
    : [...mindmap.openTopics, id]
}

export function expandMindMapTopic(id: string, open: boolean) {
  const has = mindmap.openTopics.includes(id)
  if (open === has) return
  toggleMindMapTopic(id)
}

/* ---------------- 节点编辑 ---------------- */

export function updateMindMapNode(topicId: string, path: number[], text: string) {
  const topic = mindMapTopicById(topicId)
  if (!topic) return
  const found = mindMapLocate(topic.nodes, path)
  if (!found) return
  found.list[found.index].text = text
  markMindMapDirty()
}

/** 在某节点后面插一个同级空节点（同级最快；要更深就走「缩进」） */
export function addMindMapSibling(topicId: string, path: number[]): number[] | null {
  const topic = mindMapTopicById(topicId)
  if (!topic) return null
  const found = mindMapLocate(topic.nodes, path)
  if (!found) return null
  const at = found.index + 1
  found.list.splice(at, 0, { text: '' })
  markMindMapDirty()
  return [...path.slice(0, -1), at]
}

/** 给节点加一个子节点（挂在末尾） */
export function addMindMapChild(topicId: string, path: number[]): number[] | null {
  const topic = mindMapTopicById(topicId)
  if (!topic) return null
  const found = mindMapLocate(topic.nodes, path)
  if (!found) return null
  const node = found.list[found.index]
  node.children = node.children ?? []
  node.children.push({ text: '' })
  mindmap.openNodes[mindMapNodeKey(topicId, path)] = true
  markMindMapDirty()
  return [...path, node.children.length - 1]
}

/** 主题为空时也要能加第一个节点 */
export function addMindMapRoot(topicId: string): number[] | null {
  const topic = mindMapTopicById(topicId)
  if (!topic) return null
  topic.nodes.push({ text: '' })
  markMindMapDirty()
  return [topic.nodes.length - 1]
}

/** 删节点连同它下面整棵子树（内容不少，界面上要二次确认） */
export function removeMindMapNode(topicId: string, path: number[]) {
  const topic = mindMapTopicById(topicId)
  if (!topic) return
  const found = mindMapLocate(topic.nodes, path)
  if (!found) return
  found.list.splice(found.index, 1)
  markMindMapDirty()
}

/** 缩进：挂到前一个兄弟节点下（第一个兄弟没法缩进——上面没有可当父的） */
export function indentMindMapNode(topicId: string, path: number[]): number[] | null {
  const topic = mindMapTopicById(topicId)
  if (!topic) return null
  const found = mindMapLocate(topic.nodes, path)
  if (!found || found.index === 0) return null
  const [node] = found.list.splice(found.index, 1)
  const prev = found.list[found.index - 1]
  prev.children = prev.children ?? []
  prev.children.push(node)
  mindmap.openNodes[mindMapNodeKey(topicId, path.slice(0, -1).concat(found.index - 1))] = true
  markMindMapDirty()
  return [...path.slice(0, -1), found.index - 1, prev.children.length - 1]
}

/** 反缩进：升成父节点的兄弟，排在父节点后面 */
export function outdentMindMapNode(topicId: string, path: number[]): number[] | null {
  const topic = mindMapTopicById(topicId)
  if (!topic || path.length < 2) return null
  const parentPath = path.slice(0, -1)
  const parentFound = mindMapLocate(topic.nodes, parentPath)
  if (!parentFound) return null
  const selfFound = mindMapLocate(topic.nodes, path)
  if (!selfFound) return null
  const [node] = selfFound.list.splice(selfFound.index, 1)
  const at = parentFound.index + 1
  parentFound.list.splice(at, 0, node)
  markMindMapDirty()
  return [...parentPath.slice(0, -1), at]
}

/** 同级上移 / 下移：delta = -1 / +1 */
export function moveMindMapNode(topicId: string, path: number[], delta: number): number[] | null {
  const topic = mindMapTopicById(topicId)
  if (!topic) return null
  const found = mindMapLocate(topic.nodes, path)
  if (!found) return null
  const at = found.index + delta
  if (at < 0 || at >= found.list.length) return null
  const [node] = found.list.splice(found.index, 1)
  found.list.splice(at, 0, node)
  markMindMapDirty()
  return [...path.slice(0, -1), at]
}

/* ---------------- 主题编辑 ---------------- */

let mindMapSeq = 0

export function renameMindMapTopic(topicId: string, name: string) {
  const topic = mindMapTopicById(topicId)
  if (!topic) return
  topic.name = name
  markMindMapDirty()
}

export function addMindMapTopic(sectionId: string): string | null {
  const section = mindmap.catalog?.sections.find((s) => s.id === sectionId)
  if (!section) return null
  mindMapSeq++
  const id = `web-${Date.now().toString(36)}-${mindMapSeq.toString(36)}`
  section.topics.push({
    id,
    label: '新主题',
    name: '新主题',
    nodes: [{ text: '' }],
    pairs: [],
  })
  mindmap.openTopics = [...mindmap.openTopics, id]
  markMindMapDirty()
  return id
}

export async function removeMindMapTopic(sectionId: string, topicId: string): Promise<void> {
  const section = mindmap.catalog?.sections.find((s) => s.id === sectionId)
  if (!section) return
  const topic = section.topics.find((t) => t.id === topicId)
  if (!topic) return
  const ok = await askConfirm(
    `删除主题「${topic.name}」？`,
    '这一页的整棵树都会删掉（保存后才真正落盘）。书页上的配对题也一起没。',
    '删除',
  )
  if (!ok) return
  section.topics = section.topics.filter((t) => t.id !== topicId)
  markMindMapDirty()
}

/* ---------------- 保存 / 重置 ---------------- */

/** 保存前的本地校验：和手机端落盘校验同一套口径，先在网页上兜住 */
export function mindMapIssue(cat: MindMapCatalog): string | null {
  if (!cat.sections.length) return '至少要保留一个板块'
  for (const s of cat.sections) {
    if (!s.name.trim()) return '有板块没填名字'
    if (!s.topics.length) return `板块「${s.name}」里至少要有一个主题`
    for (const t of s.topics) {
      if (!t.name.trim()) return '有主题没填名字'
      if (!t.nodes.length) return `主题「${t.name}」里至少要有一个节点`
      const walk = (nodes: MindMapNode[]): string | null => {
        for (const n of nodes) {
          if (!n.text.trim()) return `「${t.name}」里有空白节点，删掉或补上文字再保存`
          const deep = walk(n.children ?? [])
          if (deep) return deep
        }
        return null
      }
      const bad = walk(t.nodes)
      if (bad) return bad
    }
  }
  return null
}

export async function saveMindMap(): Promise<boolean> {
  const cat = mindmap.catalog
  if (!cat) return false
  const problem = mindMapIssue(cat)
  if (problem) { toast(problem, 'err'); return false }
  if (ui.waitingId) {
    toast('还有一条请求在等手机处理，等它结束再发', 'err')
    return false
  }

  ui.busy = true
  try {
    const r = await api.mindmapSave({ ...cat })
    const result = 'needConfirm' in r
      ? await waitPhone(
        'mindmap-save',
        r,
        `保存导图「${r.title}」`,
        '确认后 App 里的思维导图会换成网页上这一版。',
      )
      : r
    if (!result) return false
    mindmap.dirty = false
    await loadMindMap(true)
    const topics = (result as MindMapSaveResult).topicCount
    toast(`已保存思维导图 · ${topics ?? '?'} 个主题，回手机就能看`, 'ok')
    return true
  } catch (e) {
    await showErrors('保存没成功', e instanceof Error ? e.message : '保存失败')
    return false
  } finally {
    ui.busy = false
  }
}

/** 重置为 App 内置内容：删掉覆盖文件，网页上的改动就没了 */
export async function resetMindMap(): Promise<void> {
  const cat = mindmap.catalog
  if (!cat) return
  const ok = await askConfirm(
    '把思维导图重置回 App 内置内容？',
    '会删掉网页 / AI 存过的那版导图，回到 APK 里内置的树。' +
      (cat.hasEdits ? '\n\n现在有覆盖版，重置后当前网页上的未保存改动也一并丢弃。' : ''),
    '发到手机确认',
  )
  if (!ok) return
  if (ui.waitingId) { toast('还有一条请求在等手机处理，等它结束再发', 'err'); return }
  ui.busy = true
  try {
    const r = await api.mindmapReset()
    const result = 'needConfirm' in r
      ? await waitPhone('mindmap-reset', r, '重置思维导图', '重置会删掉覆盖版，回到内置内容。')
      : r
    if (!result) return
    mindmap.dirty = false
    await loadMindMap(true)
    const wasReset = (result as MindMapSaveResult).reset
    toast(wasReset === false ? '本来就没有覆盖版，内置内容原样' : '已重置回内置导图', 'ok')
  } catch (e) {
    toast(e instanceof Error ? e.message : '重置失败', 'err')
  } finally {
    ui.busy = false
  }
}

/* ---------------- 配对题（书页上的「内涵连线」） ---------------- */

export function updateMindMapPair(topicId: string, index: number, side: 'left' | 'right', text: string) {
  const topic = mindMapTopicById(topicId)
  const pair = topic?.pairs[index]
  if (!pair) return
  pair[side] = text
  markMindMapDirty()
}

export function addMindMapPair(topicId: string) {
  const topic = mindMapTopicById(topicId)
  if (!topic) return
  topic.pairs.push({ left: '', right: '' })
  markMindMapDirty()
}

export function removeMindMapPair(topicId: string, index: number) {
  const topic = mindMapTopicById(topicId)
  if (!topic) return
  topic.pairs.splice(index, 1)
  markMindMapDirty()
}

export function renameMindMapLabel(topicId: string, label: string) {
  const topic = mindMapTopicById(topicId)
  if (!topic) return
  topic.label = label
  markMindMapDirty()
}

/* ------------------------------------------------------------------ 预览取值（背诵 / 导图右栏） */

/**
 * 预览栏显示哪一张？规则统一成「看最近展开的那张，没有就取当前筛选的第一张」——
 * 不用再维护一份「当前选中」状态，点开哪张卡片，右边就是哪张。
 */
export function recitePreviewCard(): ReciteCard | null {
  const cards = reciteVisibleCards.value
  if (!cards.length) return null
  for (let i = recite.expanded.length - 1; i >= 0; i--) {
    const hit = cards.find((c) => c.id === recite.expanded[i])
    if (hit) return hit
  }
  return cards[0]
}

/** 词库同理；词库列表分页，只在当前页里找（页外那张右栏看不到也没意义） */
export function wordPreviewItem(): WordItem | null {
  const page = Math.min(recite.wordPage, wordPageCount())
  const shown = recite.words.slice((page - 1) * recite.wordPageSize, page * recite.wordPageSize)
  if (!shown.length) return null
  for (let i = recite.wordExpanded.length - 1; i >= 0; i--) {
    const hit = shown.find((w) => w.id === recite.wordExpanded[i])
    if (hit) return hit
  }
  return shown[0]
}

/** 导图：最近展开的主题；一个都没展开就取当前板块的第一个 */
export function mindMapPreviewTopic(): MindMapTopic | null {
  for (let i = mindmap.openTopics.length - 1; i >= 0; i--) {
    const t = mindMapTopicById(mindmap.openTopics[i])
    if (t) return t
  }
  const sections = mindMapVisibleSections.value
  return sections[0]?.topics[0] ?? null
}

/* ------------------------------------------------------------------ 政治理论大纲（统一库） */

/**
 * 大纲编辑器自己的状态。政治页在 App 里就是这一份库（`recite_library.json`），
 * 所以网页这边改的也是它——旧的卡片库（`recite_politics.json`）已经退场。
 *
 * 整份读、整份写：保存要过手机确认（和词库 / 导图同一套闸门）。
 * 1400 多条一次全渲染会卡，按标签分段 + 每段先出 30 条，点「显示更多」再放。
 */
export const library = reactive({
  catalog: null as LibraryCatalog | null,
  loading: false,
  /** 有没保存到手机的改动（保存成功后清掉） */
  dirty: false,
  query: '',
  /** 标签筛选：'all' = 全部 */
  tag: 'all' as string,
  /** 来源筛选：item.source 的原值，'all' = 全部 */
  source: 'all' as string,
  /** 正在展开编辑的条目 id */
  expanded: [] as string[],
  /** 每个标签当前渲染多少条（key = 标签 id 或 'all'） */
  limit: {} as Record<string, number>,
})

/** 每段先渲染这么多条：1400+ 条全铺出来，滚一下都掉帧 */
export const LIBRARY_PAGE = 30

/** 正文里的挖空标记，和 App 的 blankCount 同一个口径 */
export const LIBRARY_BLANK = '____'

/** 正文里 `____` 的个数 */
export function libraryBlankCount(text: string): number {
  return text.split(LIBRARY_BLANK).length - 1
}

let librarySeq = 0

export function markLibraryDirty() {
  library.dirty = true
}

export async function loadLibrary(force = false): Promise<void> {
  if (library.catalog && !force) return
  library.loading = true
  try {
    library.catalog = await api.library()
    library.dirty = false
    library.tag = 'all'
    library.source = 'all'
    library.query = ''
    library.expanded = []
    library.limit = {}
  } catch (e) {
    toast(e instanceof Error ? e.message : '读取政治大纲失败', 'err')
  } finally {
    library.loading = false
  }
}

/* ---------------- 取值 ---------------- */

export const libraryTopicTags = computed<LibraryTag[]>(
  () => (library.catalog?.tags ?? []).filter((t) => t.kind === 'topic'),
)

export const librarySourceTags = computed<LibraryTag[]>(
  () => (library.catalog?.tags ?? []).filter((t) => t.kind === 'source'),
)

const libraryTopicIds = computed(() => new Set(libraryTopicTags.value.map((t) => t.id)))

/** 条目挂在哪个内容标签下（第一个内容标签；没有就是空串） */
export function libraryMainTag(item: LibraryItem): string {
  return item.tags.find((t) => libraryTopicIds.value.has(t)) ?? ''
}

/** 来源（节名）清单，做筛选下拉用；按出现顺序排，读起来跟书一致 */
export const librarySources = computed<string[]>(() => {
  const seen: string[] = []
  for (const item of library.catalog?.items ?? []) {
    if (item.source && !seen.includes(item.source)) seen.push(item.source)
  }
  return seen
})

export const libraryVisibleItems = computed<LibraryItem[]>(() => {
  const items = library.catalog?.items ?? []
  const tag = library.tag
  const source = library.source
  const kw = library.query.trim().toLowerCase()
  return items.filter((item) => {
    if (tag !== 'all' && !item.tags.includes(tag)) return false
    if (source !== 'all' && item.source !== source) return false
    if (kw
      && !item.text.toLowerCase().includes(kw)
      && !item.source.toLowerCase().includes(kw)
      // 搜答案也命中：背到「2035」想找回原句很常见
      && !item.answers.some((a) => a.toLowerCase().includes(kw))) return false
    return true
  })
})

/** 按内容标签分段：`tag = null` 是没打上标签的一撮（「未归类」） */
export interface LibraryGroup {
  tag: LibraryTag | null
  items: LibraryItem[]
}

export const libraryGroups = computed<LibraryGroup[]>(() => {
  const items = libraryVisibleItems.value
  if (library.tag !== 'all') {
    const tag = libraryTopicTags.value.find((t) => t.id === library.tag) ?? null
    return [{ tag, items }]
  }
  const groups: LibraryGroup[] = []
  const byId = new Map<string, LibraryGroup>()
  for (const item of items) {
    const id = libraryMainTag(item)
    let group = byId.get(id)
    if (!group) {
      group = { tag: libraryTopicTags.value.find((t) => t.id === id) ?? null, items: [] }
      byId.set(id, group)
      groups.push(group)
    }
    group.items.push(item)
  }
  return groups
})

export function libraryLimitOf(key: string): number {
  return library.limit[key] ?? LIBRARY_PAGE
}

export function libraryShowMore(key: string) {
  library.limit[key] = libraryLimitOf(key) + LIBRARY_PAGE
}

/** 当前这一组里已经渲染的条目 */
export function libraryShown(group: LibraryGroup): LibraryItem[] {
  const key = group.tag?.id ?? '_none'
  return group.items.slice(0, libraryLimitOf(key))
}

/* ---------------- 编辑 ---------------- */

export function toggleLibraryItem(id: string) {
  library.expanded = library.expanded.includes(id)
    ? library.expanded.filter((x) => x !== id)
    : [...library.expanded, id]
}

export function libraryItemById(id: string): LibraryItem | null {
  return library.catalog?.items.find((x) => x.id === id) ?? null
}

export function addLibraryItem(tagId?: string): LibraryItem | null {
  const cat = library.catalog
  if (!cat) return null
  const tag = tagId || libraryTopicTags.value[0]?.id || ''
  if (!tag) { toast('先有至少一个内容标签才能加条目', 'err'); return null }
  const sourceTag = librarySourceTags.value[0]?.id
  librarySeq++
  const item: LibraryItem = {
    id: `web-${Date.now().toString(36)}-${librarySeq.toString(36)}`,
    kind: 'card',
    text: '',
    answers: [],
    source: library.source === 'all' ? '' : library.source,
    tags: sourceTag ? [tag, sourceTag] : [tag],
  }
  cat.items.unshift(item)
  library.expanded = [item.id]
  markLibraryDirty()
  return item
}

export function removeLibraryItem(id: string) {
  const cat = library.catalog
  if (!cat) return
  cat.items = cat.items.filter((x) => x.id !== id)
  library.expanded = library.expanded.filter((x) => x !== id)
  markLibraryDirty()
}

/**
 * 改正文，顺便把答案数组对齐挖空数：
 * 多出来的空补空串、少了就把尾部答案丢掉。不对齐的话保存会被服务端拦下来。
 */
export function updateLibraryItemText(id: string, text: string) {
  const item = libraryItemById(id)
  if (!item) return
  item.text = text
  const want = libraryBlankCount(text)
  while (item.answers.length < want) item.answers.push('')
  if (item.answers.length > want) item.answers = item.answers.slice(0, want)
  markLibraryDirty()
}

export function updateLibraryItemAnswer(id: string, index: number, value: string) {
  const item = libraryItemById(id)
  if (!item || index < 0 || index >= item.answers.length) return
  item.answers[index] = value
  markLibraryDirty()
}

export function updateLibraryItemSource(id: string, source: string) {
  const item = libraryItemById(id)
  if (!item) return
  item.source = source
  markLibraryDirty()
}

/** 换内容标签：内容标签放最前，来源标签原样留着 */
export function moveLibraryItemToTag(id: string, tagId: string) {
  const item = libraryItemById(id)
  if (!item || !tagId) return
  const rest = item.tags.filter((t) => !libraryTopicIds.value.has(t))
  item.tags = [tagId, ...rest]
  markLibraryDirty()
}

/* ---------------- 校验 ---------------- */

export function libraryItemIssue(item: LibraryItem): string | null {
  if (!item.text.trim()) return '正文是空的'
  const blanks = libraryBlankCount(item.text)
  if (blanks !== item.answers.length) {
    return `正文有 ${blanks} 个 ____，答案是 ${item.answers.length} 个`
  }
  if (!item.tags.some((t) => libraryTopicIds.value.has(t))) return '没挂到任何内容标签'
  return null
}

export function libraryIssueCount(): number {
  return (library.catalog?.items ?? []).reduce((n, item) => n + (libraryItemIssue(item) ? 1 : 0), 0)
}

/* ---------------- 保存 / 重置 ---------------- */

export async function saveLibrary(): Promise<boolean> {
  const cat = library.catalog
  if (!cat) return false
  const items = cat.items ?? []
  if (!items.length) { toast('大纲里一条内容都没有，先加一条', 'err'); return false }
  const bad = items.find((item) => libraryItemIssue(item))
  if (bad) {
    const why = libraryItemIssue(bad) ?? '内容不对'
    toast(`第「${bad.id}」条有问题：${why}`, 'err')
    return false
  }
  if (ui.waitingId) {
    toast('还有一条请求在等手机处理，等它结束再发', 'err')
    return false
  }

  ui.busy = true
  try {
    const payload: LibraryCatalog = {
      ...cat,
      tags: cat.tags.map((t) => ({ id: t.id, name: t.name, kind: t.kind })),
      items: cat.items.map((x) => ({
        id: x.id,
        kind: x.kind,
        text: x.text,
        answers: [...x.answers],
        source: x.source,
        tags: [...x.tags],
      })),
    }
    const r = await api.librarySave(payload)
    const result = 'needConfirm' in r
      ? await waitPhone(
        'library-save',
        r,
        `保存「${r.title}」`,
        '确认后 App 里政治大纲会换成网页上这一版（覆盖文件，删掉就回内置内容）。',
      )
      : r
    if (!result) return false
    library.dirty = false
    await loadLibrary(true)
    await loadReciteSubjects()
    const count = 'itemCount' in result ? result.itemCount : undefined
    toast(`已保存政治大纲 · ${count ?? '?'} 条内容，回手机大纲页就能看`, 'ok')
    return true
  } catch (e) {
    await showErrors('保存没成功', e instanceof Error ? e.message : '保存失败')
    return false
  } finally {
    ui.busy = false
  }
}

export async function resetLibrary(): Promise<void> {
  const cat = library.catalog
  if (!cat) return
  const ok = await askConfirm(
    '把政治大纲重置回 App 内置内容？',
    '会删掉网页 / AI 存过的那版大纲，回到 APK 里内置的重点背诵。' +
      (cat.hasEdits ? '\n\n现在有覆盖版，重置后当前网页上的未保存改动也一并丢弃。' : ''),
    '发到手机确认',
  )
  if (!ok) return
  if (ui.waitingId) { toast('还有一条请求在等手机处理，等它结束再发', 'err'); return }
  ui.busy = true
  try {
    const r = await api.libraryReset()
    const result = 'needConfirm' in r
      ? await waitPhone('library-reset', r, '重置政治大纲', '重置会删掉覆盖版，回到内置内容。')
      : r
    if (!result) return
    library.dirty = false
    await loadLibrary(true)
    await loadReciteSubjects()
    const wasReset = 'reset' in result ? result.reset : undefined
    toast(wasReset === false ? '本来就没有覆盖版，内置内容原样' : '已重置回内置大纲', 'ok')
  } catch (e) {
    toast(e instanceof Error ? e.message : '重置失败', 'err')
  } finally {
    ui.busy = false
  }
}

/* ---------------- 右栏预览取值 ---------------- */

/** 大纲：最近展开编辑的那条，一个都没展开就取当前筛选下第一条 */
export function libraryPreviewItem(): LibraryItem | null {
  const items = libraryVisibleItems.value
  if (!items.length) return null
  for (let i = library.expanded.length - 1; i >= 0; i--) {
    const hit = items.find((x) => x.id === library.expanded[i])
    if (hit) return hit
  }
  return items[0]
}
