import type {
  ConfirmStatus, EditorPaper, MigrationReport, PaperSetInfo, ResourceFile, Sheet,
  VersionInfo, WireSheet,
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

export interface ResourcesResponse {
  setId: number
  /** 这一组的图片目录，形如 images/12 */
  dir: string
  files: ResourceFile[]
}

export const api = {
  sets: () => request<SetsResponse>('/api/papers'),

  paper: (id: number) => request<PaperResponse>(`/api/paper?id=${id}`),

  publish: (title: string, replace: boolean, sheets: WireSheet[]) =>
    request<PublishResponse | ConfirmRequired>('/api/publish', jsonPost({ title, replace, sheets })),

  /** 等手机确认期间轮询；approved 时 result 就是发布结果 */
  publishStatus: (id: string) =>
    request<{ status: ConfirmStatus; result: PublishResponse | null; error?: string | null }>(
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

  deleteStatus: (id: string) =>
    request<{ status: ConfirmStatus; error?: string | null }>(
      `/api/delete/status?id=${encodeURIComponent(id)}`,
    ),

  cancelDelete: (id: string) => request<{ ok: boolean }>('/api/delete/cancel', jsonPost({ id })),
}

export type { EditorPaper }
