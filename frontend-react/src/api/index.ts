/** 后端接口封装：与旧版 app.js / admin.html 用的接口一一对应 */
import { http } from './http'
import { AppError } from '@/types'

export interface StreamEvent { event: string; data: string }

/** SSE 流式问答：fetch + ReadableStream 解析（对齐旧版 sendOrStop） */
export async function streamChat(
  sessionId: string,
  question: string,
  onEvent: (event: string, data: string) => void,
  signal?: AbortSignal
): Promise<void> {
  const r = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId, question }),
    signal
  })
  if (!r.ok) {
    let m = '请求失败'
    try { m = (await r.json() as { message?: string }).message || m } catch { /* 非JSON */ }
    throw new AppError(m, r.status)
  }
  if (!r.body) throw new AppError('浏览器不支持流式响应')
  const reader = r.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
    let i: number
    while ((i = buffer.indexOf('\n\n')) !== -1) {
      const block = buffer.slice(0, i)
      buffer = buffer.slice(i + 2)
      let event = 'message'
      const dataLines: string[] = []
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
      }
      if (dataLines.length) onEvent(event, dataLines.join('\n'))
    }
  }
}

/** 上传（XHR 进度），includeSubtree 对齐旧版可见范围 */
export function uploadDocument(
  file: File,
  onProgress?: (percent: number) => void,
  includeSubtree = false
): Promise<{ fileName: string; chunks: number; chars: number }> {
  return new Promise((resolve, reject) => {
    const form = new FormData()
    form.append('file', file)
    if (includeSubtree) form.append('includeSubtree', 'true')
    const xhr = new XMLHttpRequest()
    xhr.open('POST', '/api/docs/upload')
    xhr.upload.onprogress = e => {
      if (e.lengthComputable && onProgress) onProgress(Math.round(e.loaded / e.total * 100))
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try { resolve(JSON.parse(xhr.responseText)) }
        catch { reject(new AppError('服务返回了无法识别的结果')) }
      } else {
        let m = '上传失败，请稍后重试'
        try { m = (JSON.parse(xhr.responseText).message as string) || m } catch { /* 非JSON */ }
        reject(new AppError(m, xhr.status))
      }
    }
    xhr.onerror = () => reject(new AppError('网络连接失败'))
    xhr.send(form)
  })
}

export const authApi = {
  login: (username: string, password: string, encrypted: boolean, encryptedPassword?: string) =>
    http.post<Record<string, unknown>>('/api/auth/login', {
      username, password: encrypted ? encryptedPassword : password, encrypted
    }),
  permissions: () => http.get<import('@/types').PermissionsResponse>('/api/auth/permissions'),
  changePassword: (body: unknown) => http.post('/api/auth/change-password', body)
}

export const chatApi = {
  sessions: () => http.get<import('@/types').SessionItem[]>('/api/chat/sessions'),
  history: (id: string) => http.get<Array<{ role: string; content: string; sources?: unknown[] }>>(
    `/api/chat/history?sessionId=${encodeURIComponent(id)}`),
  createSession: (sessionId: string, title = '') =>
    http.post('/api/chat/sessions', { sessionId, title }),
  clearSession: (id: string) => http.delete(`/api/chat/sessions/${encodeURIComponent(id)}`),
  fork: (body: unknown) => http.post('/api/chat/fork', body),
  rewind: (body: unknown) => http.post('/api/chat/rewind', body),
  suggestions: () => http.get<string[]>('/api/chat/suggestions')
}

export const docApi = {
  list: () => http.get<import('@/types').DocItem[]>('/api/docs/list'),
  formats: () => http.get<{ extensions: string[]; maxFileSizeBytes: number }>('/api/docs/formats'),
  chunks: (fileName: string) => http.get<unknown[]>(`/api/docs/chunks?fileName=${encodeURIComponent(fileName)}`)
}

export const searchApi = {
  compare: (q: string) => http.get<Record<string, unknown>>(`/api/search/compare?q=${encodeURIComponent(q)}`),
  health: () => http.get<{ healthy: boolean }>('/api/search/health')
}

export const usageApi = {
  summary: () => http.get<Record<string, unknown>>('/api/usage/summary'),
  records: (scope = '') => http.get<unknown[]>(`/api/usage/records?scope=${encodeURIComponent(scope)}`)
}

export const modelApi = {
  list: () => http.get<Array<Record<string, unknown>>>('/api/models'),
  create: (body: unknown) => http.post('/api/models', body),
  update: (id: number, body: unknown) => http.put(`/api/models/${id}`, body),
  remove: (id: number) => http.delete(`/api/models/${id}`),
  activate: (id: number) => http.post(`/api/models/${id}/activate`),
  test: (id: number) => http.post(`/api/models/${id}/test`)
}

export const adminApi = {
  users: (params?: string) => http.get<Array<Record<string, unknown>>>('/api/admin/users' + (params || '')),
  createUser: (body: unknown) => http.post('/api/admin/users', body),
  updateUser: (username: string, body: unknown) => http.put(`/api/admin/users/${encodeURIComponent(username)}`, body),
  deleteUser: (username: string) => http.delete(`/api/admin/users/${encodeURIComponent(username)}`),
  resetPassword: (username: string, body: unknown) => http.post(`/api/admin/users/${encodeURIComponent(username)}/reset-password`, body),
  passwordResets: () => http.get<unknown[]>('/api/admin/password-resets'),
  approveReset: (id: string) => http.post(`/api/admin/password-resets/${encodeURIComponent(id)}/approve`),
  rejectReset: (id: string) => http.post(`/api/admin/password-resets/${encodeURIComponent(id)}/reject`),
  exportUsers: () => http.get<Blob>('/api/admin/users/export'),

  orgs: () => http.get<Array<Record<string, unknown>>>('/api/orgs'),
  createOrg: (body: unknown) => http.post('/api/admin/orgs', body),
  updateOrg: (key: string, body: unknown) => http.put(`/api/admin/orgs/${encodeURIComponent(key)}`, body),
  deleteOrg: (key: string) => http.delete(`/api/admin/orgs/${encodeURIComponent(key)}`),

  roles: () => http.get<import('@/types').RoleItem[]>('/api/admin/roles'),
  setRolePermissions: (role: string, permissions: string[], roleName?: string) =>
    http.put(`/api/admin/roles/${encodeURIComponent(role)}/permissions`, { permissions, roleName }),
  deleteRole: (role: string) => http.delete(`/api/admin/roles/${encodeURIComponent(role)}`),
  createRole: (body: unknown) => http.post('/api/admin/roles', body),

  perms: () => http.get<import('@/types').PermissionNode[]>('/api/admin/perms'),
  createPerm: (body: unknown) => http.post('/api/admin/perms', body),
  updatePerm: (code: string, body: unknown) => http.put(`/api/admin/perms/${encodeURIComponent(code)}`, body),
  deletePerm: (code: string) => http.delete(`/api/admin/perms/${encodeURIComponent(code)}`),
  syncPerms: (items: unknown[]) => http.post('/api/admin/perms/sync', items),
  usedCodes: () => http.get<string[]>('/api/admin/perms/used-codes'),
  unusedCodes: () => http.get<Array<{ code: string; name: string; sources: string[] }>>('/api/admin/perms/unused-codes'),

  loginLogs: (limit = 500) => http.get<unknown[]>(`/api/admin/login-logs?limit=${limit}`),
  auditLogs: (limit = 500) => http.get<unknown[]>(`/api/audit/logs?limit=${limit}`),
  exportLogs: (type: string) => http.get<Blob>(`/api/audit/logs/export?type=${encodeURIComponent(type)}`)
}
