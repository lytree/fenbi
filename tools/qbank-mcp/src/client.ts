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
    query: Record<string, string | number> = {},
    /** 超时毫秒。读组是毫秒级；整组发布要写库 + 存版本，得给足时间 */
    timeoutMs = 20_000
  ): Promise<T> {
    let res: Response
    try {
      res = await fetch(this.url(path, query), {
        ...init,
        signal: AbortSignal.timeout(timeoutMs),
      })
    } catch (e) {
      const name = e instanceof Error ? e.name : ''
      const reason = e instanceof Error ? e.message : String(e)
      const timeout = Math.round(timeoutMs / 1000)
      throw new QbankError(
        name === 'TimeoutError' || name === 'AbortError'
          ? `手机 ${timeout} 秒内没有返回（${this.host}）。请求**可能已经在手机上执行了**——` +
              '先用 qbank_get_set 核对结果，别直接重试（重试可能重复写一遍）。'
          : `连不上手机（${this.host}）：${reason}。确认手机和电脑在同一个 Wi-Fi、App 里的服务还开着，` +
              '并且地址是最近一次启动生成的那串（服务重启后口令会变）。'
      )
    }

    const contentType = res.headers.get('content-type') ?? ''
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
      // 只回服务端 JSON 里的 error。非 JSON 的响应（网关错误页、端口被别的服务占着、
      // 404 的 HTML）绝不回显原文：整段 HTML 灌进模型上下文既浪费又容易误判原因。
      const message =
        (body as { error?: string } | null)?.error ||
        (res.status === 401
          ? '口令不对或已失效：回 App 里重新复制一次地址（服务重启后口令会变）'
          : `手机返回 HTTP ${res.status}`)
      throw new QbankError(message, res.status)
    }
    if (body === null || typeof body !== 'object') {
      throw new QbankError(
        `手机返回的不是 JSON（Content-Type: ${contentType || '未知'}）——这个地址可能不是 App 起的服务。`,
        res.status
      )
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
    // 整组重导：上千题要写 SQLite、再存一份版本快照，20 秒不够，低端机上容易假超时。
    // 客户端 abort 只断了本端，服务端还在写——所以宁可多等，也别让它半路以为失败。
    return this.call<PublishResult>(
      '/api/publish',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title, replace, sheets }),
      },
      {},
      90_000
    )
  }

  restoreVersion(id: string, setId = 0): Promise<PublishResult> {
    return this.call<PublishResult>(
      '/api/version/restore',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id, setId }),
      },
      {},
      90_000
    )
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
