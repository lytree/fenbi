import type {
  ConfirmStatus, EditorPaper, LocalPackageInfo, PaperSetInfo, Sheet, VersionInfo, WireSheet,
} from './types'
import type { LocalPackageResponse, LocalQuestionRaw } from './local'

/**
 * 本地试卷包的图片基目录。空串 = 手机模式（走 /api/img）；
 * 非空 = 本地模式，图片由开发服务器的 `/local/img` 按试卷包目录解析。
 */
let imageBase = ''

export function setImageBase(dir: string) {
  imageBase = dir
}

export function imageBaseDir(): string {
  return imageBase
}

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
  if (!res.ok) throw new ApiError(data?.error || `请求失败（${res.status}）`, res.status)
  // 开发服务器对 /api/* 会回落到 index.html：拿到 HTML 说明手机端接口没接上，
  // 不能当成「空题库」继续走下去，否则永远不会切到本地试卷包
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
    if (imageBase) {
      throw new ApiError('本地模式暂不支持上传图片：把图片放进试卷包的 images/ 目录，再写 images/文件名', 400)
    }
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

  imgUrl: (path: string) => imageBase
    ? `/local/img?p=${encodeURIComponent(path)}&base=${encodeURIComponent(imageBase)}`
    : `/api/img?p=${encodeURIComponent(path)}`,

  // ---- 本地试卷包（只在 vite 开发服务器下可用） ----
  localPackages: () => request<{ packages: LocalPackageInfo[] }>('/local/packages'),

  localPackage: (id: string) =>
    request<LocalPackageResponse>(`/local/package?id=${encodeURIComponent(id)}`),

  localSave: (path: string, bank: string, description: string, questions: LocalQuestionRaw[]) =>
    request<{ ok: boolean; path: string; questionCount: number }>(
      '/local/save',
      jsonPost({ path, bank, description, questions }),
    ),

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
