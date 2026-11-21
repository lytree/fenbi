import type {
  ConfirmStatus, EditorPaper, LibraryCatalog, LibrarySaveResult, MindMapCatalog, MindMapSaveResult,
  MigrationReport, PaperSetInfo,
  ReciteCatalog, ReciteSaveResult, ReciteSubjectInfo, ResourceFile, Sheet, VersionInfo,
  WireSheet, WordItem, WordsResponse,
} from './types'

export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message)
    this.name = 'ApiError'
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, init)
  const text = await res.text()
  let data: any = {}
  let isJson = true
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      isJson = false
      data = { error: text }
    }
  }
  if (res.status === 401) {
    // 没登录 / 会话过期（手机改了密码、服务重启过）：整页刷新会落到服务端的登录页。
    // 比在每个调用点弹「需要登录」干净，也不会把半截数据留在界面上。
    window.location.reload()
    throw new ApiError('需要登录', 401)
  }
  if (!res.ok) throw new ApiError(data?.error || `请求失败（${res.status}）`, res.status)
  // 开发服务器对 /api/* 会回落到 index.html：拿到 HTML 说明手机端接口没接上，
  // 不能当成「空题库」继续走下去
  if (!isJson) {
    throw new ApiError('接口返回的不是 JSON：手机端局域网服务可能没开', res.status)
  }
  return data as T
}

const jsonPost = (body: unknown): RequestInit => ({
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
})

export interface SetsResponse {
  sets: PaperSetInfo[]
  questionTotal: number
  paperTotal: number
}

export interface PaperResponse {
  id: number
  title: string
  sheets: Sheet[]
  questionCount: number
  missingAnswers: number
}

export interface PublishResponse {
  ok: boolean
  setId: number
  title: string
  questionCount: number
  /** 组里被禁用的题数（题还在，只是不参与卷面与统计） */
  disabledQuestions?: number
  replaced: boolean
  /** 覆盖发布时存下的旧版（第一次发布为 null） */
  version: VersionInfo | null
  papers: { name: string; questionCount: number }[]
}

/**
 * 网页没有写权限：发布 / 回滚只会登记一条请求，手机上点确认才真写。
 *
 * 响应里带 `needConfirm` 就说明是这条路；不带就是直发（AI 客户端带 `X-Lan-Key`，
 * 网页永远拿不到那串 key）。
 */
export interface ConfirmRequired {
  needConfirm: true
  requestId: string
  title: string
  detail: string
  ttlSec: number
  replaced?: boolean
}

export interface DeleteRequestResponse {
  requestId: string
  title: string
  detail: string
  ttlSec: number
}

/** 申请 MCP 写入口令：手机上确认后才通过 publish/status 的 result 拿到配置 */
export interface McpKeyRequest {
  needConfirm: true
  requestId: string
  url: string
  ttlSec: number
}

/** 手机同意后交回的整段 MCP 配置 */
export interface McpKeyResult {
  url: string
  key: string
  config: string
}

export interface ResourcesResponse {
  setId: number
  /** 这一组的图片目录，形如 images/12 */
  dir: string
  files: ResourceFile[]
}

/** 一条作答记录（列表项，对应 App 的 records 表） */
export interface RecordInfo {
  id: number
  /** 负数 = 「整组作答」的虚拟卷（-setId） */
  paperId: number
  /** 这轮练习的名字（一般是卷名 / 组名） */
  name: string
  paperName: string
  mode: string
  /** done = 已交卷，ongoing = 进行中，abandoned = 已放弃 */
  status: string
  startedAt: number
  submittedAt: number
  durationSec: number
  total: number
  answered: number
  correct: number
  partial: number
  score: number
  fullScore: number
}

/** 逐题明细里的一道题：题面 + 这次作答的结果 */
export interface RecordAnswer {
  no: number
  type: string
  stem: string
  material: string
  options: { key: string; text: string }[]
  answer: string
  analysis: string
  knowledge: string
  userAnswer: string
  /** 客观题判分结果；pending = 还没批（主观题） */
  correct: boolean
  partial: boolean
  pending: boolean
  earned: number
  full: number
}

export interface RecordDetail extends RecordInfo {
  answers: RecordAnswer[]
}

export const api = {
  sets: () => request<SetsResponse>('/api/papers'),

  paper: (id: number) => request<PaperResponse>(`/api/paper?id=${id}`),

  publish: (title: string, replace: boolean, sheets: WireSheet[]) =>
    request<PublishResponse | ConfirmRequired>('/api/publish', jsonPost({ title, replace, sheets })),

  /** 等手机确认期间轮询；approved 时 result 就是发布 / 保存的结果 */
  publishStatus: (id: string) =>
    request<{ status: ConfirmStatus; result: PublishResponse | ReciteSaveResult | null; error?: string | null }>(
      `/api/publish/status?id=${encodeURIComponent(id)}`,
    ),

  /** 网页上撤回：手机还没点的话，那边就不弹了 */
  cancelPublish: (id: string) =>
    request<{ ok: boolean; error?: string | null }>('/api/publish/cancel', jsonPost({ id })),

  /**
   * 上传一张图，返回题目里该写的相对路径 `images/<组编号>/xxx.png`。
   *
   * `setId` 是当前打开的组；新建还没发布的组传 0，手机端先存在 `images/draft/`，
   * 下次打开这一组时会自动搬进正式目录。
   */
  upload: async (file: File, setId: number, replacePath?: string): Promise<string> => {
    const name = file.name || `paste-${Date.now()}.png`
    const query = new URLSearchParams({ name, setId: String(setId) })
    if (replacePath) query.set('replace', replacePath)
    const res = await fetch(`/api/image?${query.toString()}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/octet-stream' },
      body: file,
    })
    const data = await res.json().catch(() => ({}))
    if (res.status === 401) {
      window.location.reload()
      throw new ApiError('需要登录', 401)
    }
    if (!res.ok) throw new ApiError(data?.error || '图片上传失败', res.status)
    return data.path as string
  },

  imgUrl: (path: string) => `/api/img?p=${encodeURIComponent(path)}`,

  /** 一组已上传的图片（按组编号分目录） */
  resources: (setId: number) =>
    request<ResourcesResponse>(`/api/resources?setId=${setId}`),

  deleteResource: (path: string) =>
    request<{ ok: boolean; path: string }>(
      `/api/resource?p=${encodeURIComponent(path)}`,
      { method: 'DELETE' },
    ),

  /**
   * 把老的散图整理进 `images/<组编号>/`，改写题库里的图片路径并返回统计。
   * `setId = 0` 表示全库整理（MCP / 调试脚本用）。
   */
  migrateImages: (setId: number) =>
    request<MigrationReport>('/api/images/migrate', jsonPost({ setId })),

  /** 某组的历史版本；不给 title 就是全部 */
  versions: (title?: string) =>
    request<{ versions: VersionInfo[] }>(
      '/api/versions' + (title ? `?title=${encodeURIComponent(title)}` : ''),
    ),

  /** setId = 当前打开的组；组名改过时回滚要落回它，而不是快照里的旧名字 */
  restoreVersion: (id: string, setId?: number, title?: string) =>
    request<PublishResponse | ConfirmRequired>(
      '/api/version/restore',
      jsonPost({ id, setId: setId ?? 0, title: title ?? '' }),
    ),

  deleteVersion: (id: string) => request<{ ok: boolean }>('/api/version/delete', jsonPost({ id })),

  /** 只发起删除请求；真正删不删由手机上那一下决定 */
  requestDelete: (setId: number) =>
    request<DeleteRequestResponse>('/api/delete/request', jsonPost({ setId })),

  /**
   * 归档 / 恢复一整组。归档只是「收起来」：题目、作答记录都留着，
   * 随时能恢复，所以这一步不用手机确认。
   */
  archiveSet: (setId: number, archived: boolean) =>
    request<{ ok: boolean; setId: number; title: string; archived: boolean }>(
      '/api/archive/set',
      jsonPost({ setId, archived }),
    ),

  deleteStatus: (id: string) =>
    request<{ status: ConfirmStatus; error?: string | null }>(
      `/api/delete/status?id=${encodeURIComponent(id)}`,
    ),

  cancelDelete: (id: string) => request<{ ok: boolean }>('/api/delete/cancel', jsonPost({ id })),

  /* ------------ 作答记录（只读） ------------ */

  /** 全库最近的作答记录（服务端最多给 200 条）；传 setId 只看这一组的 */
  records: (setId?: number) =>
    request<{ records: RecordInfo[]; total: number }>(
      '/api/records' + (setId && setId > 0 ? `?setId=${setId}` : ''),
    ),

  /** 一次作答的逐题明细：用户答案、标准答案、对错、得分，按卷面顺序 */
  record: (id: number) => request<RecordDetail>(`/api/record?id=${id}`),

  /* ------------ 背诵词库（政治 / 常识） ------------ */

  reciteSubjects: () =>
    request<{ subjects: ReciteSubjectInfo[] }>('/api/recite/subjects'),

  reciteCatalog: (subject: string) =>
    request<ReciteCatalog>(`/api/recite/catalog?subject=${encodeURIComponent(subject)}`),

  /**
   * 整份保存背诵词库。响应和发布一样分两路：
   * AI（带 key）直发拿到 `ReciteSaveResult`；网页拿到 `ConfirmRequired`，
   * 轮询 /api/publish/status（那条接口按请求 id 查，对任何操作都通用）。
   */
  reciteSave: (payload: Partial<ReciteCatalog> & { subject: string }) =>
    request<ReciteSaveResult | ConfirmRequired>('/api/recite/save', jsonPost(payload)),

  reciteReset: (subject: string) =>
    request<ReciteSaveResult | ConfirmRequired>('/api/recite/reset', jsonPost({ subject })),

  /* ------------ 思维导图（整份读、整份写） ------------ */

  mindmap: () => request<MindMapCatalog>('/api/mindmap'),

  /**
   * 整份保存导图。响应和背诵一样分两路：
   * AI（带 key）直发拿到 `MindMapSaveResult`；网页拿到 `ConfirmRequired`，
   * 轮询 /api/publish/status（按请求 id 查，对任何操作都通用）。
   */
  mindmapSave: (payload: MindMapCatalog) =>
    request<MindMapSaveResult | ConfirmRequired>('/api/mindmap/save', jsonPost(payload)),

  mindmapReset: () =>
    request<MindMapSaveResult | ConfirmRequired>('/api/mindmap/reset', jsonPost({})),

  /* ------------ 政治理论大纲（统一库，整份读整份写） ------------ */

  library: () => request<LibraryCatalog>('/api/library'),

  /**
   * 整份保存大纲。响应同样分两路：AI（带 key）直发拿到 `LibrarySaveResult`；
   * 网页拿到 `ConfirmRequired`，轮询 /api/publish/status。
   */
  librarySave: (payload: LibraryCatalog) =>
    request<LibrarySaveResult | ConfirmRequired>('/api/library/save', jsonPost(payload)),

  libraryReset: () =>
    request<LibrarySaveResult | ConfirmRequired>('/api/library/reset', jsonPost({})),

  /* ------------ 词库（词条 CRUD） ------------ */

  words: (opts: { q?: string; category?: string; tag?: string } = {}) => {
    const qs = new URLSearchParams()
    if (opts.q) qs.set('q', opts.q)
    if (opts.category && opts.category !== 'all') qs.set('category', opts.category)
    if (opts.tag && opts.tag !== 'all') qs.set('tag', opts.tag)
    const s = qs.toString()
    return request<WordsResponse>('/api/words' + (s ? `?${s}` : ''))
  },

  wordSave: (w: Partial<WordItem>) =>
    request<WordItem>('/api/word', jsonPost(w)),

  wordDelete: (id: number) =>
    request<{ ok: boolean; id: number }>(`/api/word?id=${id}`, { method: 'DELETE' }),

  /**
   * 申请 MCP 配置（含写入口令）。
   *
   * 口令不直接返回：服务端只登记一条待确认请求，手机上点「确认提供」后，
   * 轮询 /api/publish/status 的 `result` 里才有 `config`；拒绝或超时就拿不到。
   */
  mcpInfo: () =>
    request<McpKeyRequest>('/api/mcp', jsonPost({})),
}

export type { EditorPaper }
