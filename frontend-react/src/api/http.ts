/** HTTP 客户端：友好错误文案，401 由 fetch 补丁统一处理 */
import { AppError } from '@/types'

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

function msg(status: number, fallback?: string): string {
  return fallback || FRIENDLY[status] || '暂时无法加载，请稍后重试'
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  let r: Response
  try {
    r = await fetch(path, { ...init, headers })
  } catch {
    throw new AppError('网络连接失败，请检查网络或稍后重试')
  }
  if (!r.ok) {
    let backendMsg: string | undefined
    try { backendMsg = (await r.json() as { message?: string }).message } catch { /* 非 JSON */ }
    throw new AppError(msg(r.status, backendMsg), r.status)
  }
  if (r.status === 204) return undefined as T
  const ct = r.headers.get('Content-Type') || ''
  return (ct.includes('application/json') ? await r.json() : await r.text()) as T
}

export const http = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) => request<T>(path, {
    method: 'POST', body: body === undefined ? undefined : JSON.stringify(body)
  }),
  put: <T>(path: string, body?: unknown) => request<T>(path, {
    method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body)
  }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' })
}
