import type {
  PublishResult,
  RecordDetail,
  RecordSummary,
  SetDetail,
  SetsResponse,
  VersionInfo,
  WireSheet,
} from './schema.js'
import type { QbankConfig } from './config.js'

export class QbankError extends Error {
  constructor(message: string, readonly status = 0) {
    super(message)
    this.name = 'QbankError'
  }
}

export interface DeleteRequestResult {
  requestId: string
  title: string
  detail: string
  ttlSec: number
}

export type ConfirmStatus = 'pending' | 'approved' | 'rejected' | 'expired'

/**
 * 手机端 HTTP API 的薄封装。
 *
 * 认证靠 query 里的 `t=`（服务端只认 Cookie 或这个参数，MCP 这种无 Cookie 的场景只能用它）。
 * 所有写操作最终都落到 `/api/publish`——和网页上点「发布到 App」是同一条路。
 */
export class QbankClient {
  private readonly base: string

  constructor(private readonly cfg: QbankConfig) {
    this.base = cfg.baseUrl.replace(/\/+$/, '')
  }

  get host(): string {
    try {
      return new URL(this.base).host
    } catch {
      return this.base
    }
  }

  private url(path: string, query: Record<string, string | number> = {}): string {
    const url = new URL(this.base + path)
    url.searchParams.set('t', this.cfg.token)
    for (const [k, v] of Object.entries(query)) url.searchParams.set(k, String(v))
    return url.toString()
  }

  private async call<T>(
    path: string,
    init: RequestInit = {},
    query: Record<string, string | number> = {}
  ): Promise<T> {
    let res: Response
    try {
      res = await fetch(this.url(path, query), {
        ...init,
        // 手机在同一个局域网里，正常是毫秒级；20 秒还没回基本就是掉线 / 换了 Wi-Fi
        signal: AbortSignal.timeout(20_000),
      })
    } catch (e) {
      const reason = e instanceof Error ? e.message : String(e)
      throw new QbankError(
        `连不上手机（${this.host}）：${reason}。确认手机和电脑在同一个 Wi-Fi、App 里的服务还开着，` +
          '并且地址是最近一次启动生成的那串（服务重启后口令会变）。'
      )
    }

    const text = await res.text()
    let body: unknown = null
    if (text) {
      try {
        body = JSON.parse(text)
      } catch {
        body = null
      }
    }

    if (!res.ok) {
      const message =
        (body as { error?: string } | null)?.error || text.slice(0, 300) || `HTTP ${res.status}`
      throw new QbankError(message, res.status)
    }
    return body as T
  }

  // ---------------------------------------------------------------- 读

  listSets(): Promise<SetsResponse> {
    return this.call<SetsResponse>('/api/papers')
  }

  getSet(setId: number): Promise<SetDetail> {
    return this.call<SetDetail>('/api/paper', {}, { id: setId })
  }

  /** 作答记录：传 setId 只看这一组 */
  listRecords(setId?: number): Promise<{ records: RecordSummary[]; total: number }> {
    return this.call<{ records: RecordSummary[]; total: number }>(
      '/api/records',
      {},
      setId ? { setId } : {}
    )
  }

  /** 一次作答的逐题明细（用户答案 + 标准答案 + 对错） */
  getRecord(recordId: number): Promise<RecordDetail> {
    return this.call<RecordDetail>('/api/record', {}, { id: recordId })
  }

  listVersions(title?: string): Promise<{ versions: VersionInfo[] }> {
    return this.call<{ versions: VersionInfo[] }>(
      '/api/versions',
      {},
      title ? { title } : {}
    )
  }

  // ---------------------------------------------------------------- 写

  publish(title: string, replace: boolean, sheets: WireSheet[]): Promise<PublishResult> {
    return this.call<PublishResult>('/api/publish', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title, replace, sheets }),
    })
  }

  restoreVersion(id: string, setId = 0): Promise<PublishResult> {
    return this.call<PublishResult>('/api/version/restore', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id, setId }),
    })
  }

  deleteVersion(id: string): Promise<{ ok: boolean }> {
    return this.call<{ ok: boolean }>('/api/version/delete', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id }),
    })
  }

  // ---------------------------------------------------------------- 删除（要手机确认）

  requestDelete(setId: number): Promise<DeleteRequestResult> {
    return this.call<DeleteRequestResult>('/api/delete/request', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ setId }),
    })
  }

  deleteStatus(id: string): Promise<{ status: ConfirmStatus; error?: string | null }> {
    return this.call<{ status: ConfirmStatus; error?: string | null }>(
      '/api/delete/status',
      {},
      { id }
    )
  }

  cancelDelete(id: string): Promise<{ ok: boolean }> {
    return this.call<{ ok: boolean }>('/api/delete/cancel', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id }),
    })
  }
}

export type { QbankConfig }
