import type {
  ConfirmStatus, EditorPaper, PaperSetInfo, Sheet, VersionInfo, WireSheet,
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
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = { error: text }
    }
  }
  if (!res.ok) throw new ApiError(data?.error || `请求失败（${res.status}）`, res.status)
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

export interface DeleteRequestResponse {
  requestId: string
  title: string
  detail: string
  ttlSec: number
}

export const api = {
  sets: () => request<SetsResponse>('/api/papers'),

  paper: (id: number) => request<PaperResponse>(`/api/paper?id=${id}`),

  publish: (title: string, replace: boolean, sheets: WireSheet[]) =>
    request<PublishResponse>('/api/publish', jsonPost({ title, replace, sheets })),

  /** 上传一张图，返回题目里该写的相对路径 `images/xxx.png` */
  upload: async (file: File): Promise<string> => {
    const name = file.name || `paste-${Date.now()}.png`
    const res = await fetch(`/api/image?name=${encodeURIComponent(name)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/octet-stream' },
      body: file,
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) throw new ApiError(data?.error || '图片上传失败', res.status)
    return data.path as string
  },

  imgUrl: (path: string) => `/api/img?p=${encodeURIComponent(path)}`,

  /** 某组的历史版本；不给 title 就是全部 */
  versions: (title?: string) =>
    request<{ versions: VersionInfo[] }>(
      '/api/versions' + (title ? `?title=${encodeURIComponent(title)}` : ''),
    ),

  /** setId = 当前打开的组；组名改过时回滚要落回它，而不是快照里的旧名字 */
  restoreVersion: (id: string, setId?: number) =>
    request<PublishResponse>('/api/version/restore', jsonPost({ id, setId: setId ?? 0 })),

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
