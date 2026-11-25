/**
 * 统一 HTTP 客户端：自动带 Bearer、401 刷新一次、403 友好文案。
 * 业务层只处理业务错误，不看 HTTP 状态码。
 */
import { AppError } from '@/types'
import { getAccess, refreshSession, clearSession } from './auth'

const FRIENDLY: Record<number, string> = {
  400: '提交的内容有误，请检查后重试',
  401: '登录已过期，请重新登录',
  403: '当前账号没有查看这项内容的权限，请联系管理员',
  404: '该功能暂时不可用，请稍后再试',
  413: '文件太大，请压缩后再试',
  429: '操作太频繁，请稍后再试',
  500: '服务暂时开小差了，请稍后再试',
  502: '服务暂时不可用，请稍后重试',
  503: '服务正在维护，请稍后再试'
}

function messageFor(status: number, fallback?: string): string {
  return fallback || FRIENDLY[status] || '暂时无法加载，请稍后重试'
}

async function request<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
  const headers = new Headers(init.headers)
  const token = getAccess()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  let r: Response
  try {
    r = await fetch(path, { ...init, headers })
  } catch {
    throw new AppError('网络连接失败，请检查网络或稍后重试')
  }

  if (r.status === 401 && retry) {
    const ok = await refreshSession()
    if (ok) return request<T>(path, init, false)
    clearSession()
    throw new AppError(FRIENDLY[401], 401)
  }

  if (!r.ok) {
    // 尽量取后端中文 message，取不到再用友好兜底
    let backendMsg: string | undefined
    try {
      const j = await r.json() as { message?: string }
      backendMsg = j.message
    } catch { /* 非 JSON 响应 */ }
    throw new AppError(messageFor(r.status, backendMsg), r.status)
  }

  // SSE / 空响应由调用方自行处理
  if (r.status === 204) return undefined as T
  const contentType = r.headers.get('Content-Type') || ''
  if (contentType.includes('application/json')) return await r.json() as T
  return await r.text() as T
}

export const http = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) => request<T>(path, {
    method: 'POST', body: body === undefined ? undefined : JSON.stringify(body)
  }),
  put: <T>(path: string, body?: unknown) => request<T>(path, {
    method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body)
  }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
  upload: <T>(path: string, form: FormData) => request<T>(path, { method: 'POST', body: form }),
  /** 流式问答：返回原始 Response，由调用方读 ReadableStream */
  raw: async (path: string, init: RequestInit = {}): Promise<Response> => {
    const headers = new Headers(init.headers)
    const token = getAccess()
    if (token) headers.set('Authorization', `Bearer ${token}`)
    if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json')
    }
    const r = await fetch(path, { ...init, headers })
    if (r.status === 401) {
      if (await refreshSession()) return http.raw(path, init)
      clearSession()
      throw new AppError(FRIENDLY[401], 401)
    }
    if (!r.ok) throw new AppError(messageFor(r.status), r.status)
    return r
  }
}
