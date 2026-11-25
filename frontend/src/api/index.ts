/** 后端接口封装：SSE 流式解析 + 所有业务接口 */
import { http, http as api } from './http'
import { AppError } from '@/types'

export interface StreamEvent { event: string; data: string }

/** 流式问答：POST + ReadableStream 手动解析 SSE（EventSource 只支持 GET） */
export async function streamChat(
  sessionId: string,
  question: string,
  onEvent: (event: string, data: string) => void,
  signal?: AbortSignal
): Promise<void> {
  const r = await http.raw('/api/chat/stream', {
    method: 'POST',
    body: JSON.stringify({ sessionId, question }),
    signal
  })
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

/** 上传文档（XHR 以支持进度回调） */
export function uploadDocument(file: File, onProgress?: (percent: number) => void, includeSubtree = false): Promise<{ fileName: string; chunks: number; chars: number }> {
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
        let msg = '上传失败，请稍后重试'
        try { msg = (JSON.parse(xhr.responseText).message as string) || msg } catch { /* 非 JSON */ }
        reject(new AppError(msg, xhr.status))
      }
    }
    xhr.onerror = () => reject(new AppError('网络连接失败，请检查网络或稍后重试'))
    xhr.send(form)
  })
}

// ===== 权限与登录 =====
export const authApi = {
  login: (username: string, password: string, encrypted: boolean, encryptedPassword?: string) =>
    api.post<Record<string, unknown>>('/api/auth/login', {
      username, password: encrypted ? encryptedPassword : password, encrypted
    }),
  permissions: () => api.get<import('@/types').PermissionsResponse>('/api/auth/permissions'),
  changePassword: (body: unknown) => api.post('/api/auth/change-password', body)
}

// ===== 问答与会话 =====
export const chatApi = {
  sessions: () => api.get<Array<{ id: string; title: string; updatedAt?: number }>>('/api/chat/sessions'),
  history: (sessionId: string) => api.get<Array<{ role: string; content: string; sources?: unknown[] }>>(`/api/chat/history?sessionId=${encodeURIComponent(sessionId)}`),
  clearHistory: (sessionId: string) => api.delete(`/api/chat/sessions/${encodeURIComponent(sessionId)}`),
  tree: (sessionId: string) => api.get<{ nodes: unknown[]; head: string; branches: Record<string, string> }>(`/api/chat/tree?sessionId=${encodeURIComponent(sessionId)}`),
  fork: (body: unknown) => api.post('/api/chat/fork', body),
  rewind: (body: unknown) => api.post('/api/chat/rewind', body),
  suggestions: () => api.get<string[]>('/api/chat/suggestions'),
  tool: (name: string, args: unknown) => api.post(`/api/chat/tool/${encodeURIComponent(name)}`, args)
}

// ===== 文档 =====
export const docApi = {
  list: () => api.get<Array<Record<string, unknown>>>('/api/docs/list'),
  formats: () => api.get<{ extensions: string[]; maxFileSizeBytes: number }>('/api/docs/formats'),
  chunks: (fileName: string) => api.get<unknown[]>(`/api/docs/chunks?fileName=${encodeURIComponent(fileName)}`),
  delete: (fileName: string, tenantId?: string) =>
    api.delete(`/api/docs?fileName=${encodeURIComponent(fileName)}${tenantId ? `&tenantId=${encodeURIComponent(tenantId)}` : ''}`)
}

// ===== 检索 / 用量 / 模型 =====
export const searchApi = {
  compare: (q: string) => api.get(`/api/search/compare?q=${encodeURIComponent(q)}`),
  health: () => api.get<{ healthy: boolean }>('/api/search/health')
}
export const usageApi = {
  summary: () => api.get<Record<string, unknown>>('/api/usage/summary'),
  records: (limit = 500) => api.get<unknown[]>(`/api/usage/records?limit=${limit}`)
}
export const modelApi = {
  list: () => api.get<Array<Record<string, unknown>>>('/api/models'),
  create: (body: unknown) => api.post('/api/models', body),
  update: (id: number, body: unknown) => api.put(`/api/models/${id}`, body),
  remove: (id: number) => api.delete(`/api/models/${id}`),
  activate: (id: number) => api.post(`/api/models/${id}/activate`),
  test: (id: number) => api.post(`/api/models/${id}/test`)
}

// ===== 后台管理 =====
export const adminApi = {
  users: () => api.get<Array<Record<string, unknown>>>('/api/admin/users'),
  createUser: (body: unknown) => api.post('/api/admin/users', body),
  updateUser: (username: string, body: unknown) => api.put(`/api/admin/users/${encodeURIComponent(username)}`, body),
  deleteUser: (username: string) => api.delete(`/api/admin/users/${encodeURIComponent(username)}`),
  resetPassword: (username: string, body: unknown) => api.post(`/api/admin/users/${encodeURIComponent(username)}/reset-password`, body),
  passwordResets: () => api.get<unknown[]>('/api/admin/password-resets'),
  approveReset: (id: string) => api.post(`/api/admin/password-resets/${id}/approve`),
  rejectReset: (id: string) => api.post(`/api/admin/password-resets/${id}/reject`),

  orgs: () => api.get<Array<Record<string, unknown>>>('/api/orgs'),
  createOrg: (body: unknown) => api.post('/api/admin/orgs', body),
  updateOrg: (key: string, body: unknown) => api.put(`/api/admin/orgs/${encodeURIComponent(key)}`, body),
  deleteOrg: (key: string) => api.delete(`/api/admin/orgs/${encodeURIComponent(key)}`),

  roles: () => api.get<import('@/types').RoleItem[]>('/api/admin/roles'),
  setRolePermissions: (role: string, permissions: string[], roleName?: string) =>
    api.put(`/api/admin/roles/${encodeURIComponent(role)}/permissions`, { permissions, roleName }),
  deleteRole: (role: string) => api.delete(`/api/admin/roles/${encodeURIComponent(role)}`),

  perms: () => api.get<import('@/types').PermissionNode[]>('/api/admin/perms'),
  createPerm: (body: unknown) => api.post('/api/admin/perms', body),
  updatePerm: (code: string, body: unknown) => api.put(`/api/admin/perms/${encodeURIComponent(code)}`, body),
  deletePerm: (code: string) => api.delete(`/api/admin/perms/${encodeURIComponent(code)}`),
  syncPerms: (items: unknown[]) => api.post('/api/admin/perms/sync', items),
  usedCodes: () => api.get<string[]>('/api/admin/perms/used-codes'),
  unusedCodes: () => api.get<Array<{ code: string; name: string; sources: string[] }>>('/api/admin/perms/unused-codes'),

  loginLogs: (limit = 500) => api.get<unknown[]>(`/api/admin/login-logs?limit=${limit}`),
  auditLogs: (limit = 500) => api.get<unknown[]>(`/api/audit/logs?limit=${limit}`)
}
