/** 认证会话：与旧版 auth.js 完全相同的 sessionStorage key 与语义 */
import { AppError, type CurrentUser, type LoginResult } from '@/types'

export const KEY_ACCESS = 'wrk_access_token'
export const KEY_REFRESH = 'wrk_refresh_token'
export const KEY_USER = 'wrk_user_info'

export function getAccess(): string {
  try { return sessionStorage.getItem(KEY_ACCESS) || '' } catch { return '' }
}
export function getRefresh(): string {
  try { return sessionStorage.getItem(KEY_REFRESH) || '' } catch { return '' }
}
export function getUser(): CurrentUser | null {
  try { return JSON.parse(sessionStorage.getItem(KEY_USER) || 'null') as CurrentUser | null }
  catch { return null }
}

export function saveSession(data: LoginResult): void {
  try {
    sessionStorage.setItem(KEY_ACCESS, data.accessToken)
    sessionStorage.setItem(KEY_REFRESH, data.refreshToken)
    sessionStorage.setItem(KEY_USER, JSON.stringify({
      user: data.user, displayName: data.displayName, groups: data.groups, tenant: data.tenant
    }))
  } catch { /* 隐私模式静默降级 */ }
}

export function clearSession(): void {
  try {
    sessionStorage.removeItem(KEY_ACCESS)
    sessionStorage.removeItem(KEY_REFRESH)
    sessionStorage.removeItem(KEY_USER)
  } catch { /* 同上 */ }
}

export function isLoggedIn(): boolean { return !!(getAccess() || getRefresh()) }

/** 账号级命名空间：切账号后 localStorage 数据不串（对齐旧版 WrkAuth.scope） */
export function scope(): string {
  const u = getUser()
  return u && u.user ? String(u.user) : 'demo'
}

/** RSA-OAEP 加密密码。缓存 CryptoKey，但每次都校验服务端公钥指纹——服务重启换了密钥要立刻失效 */
let cachedKey: CryptoKey | null = null
let cachedFingerprint = ''

function toBytes(b64: string): Uint8Array {
  const raw = atob(b64)
  const out = new Uint8Array(raw.length)
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i)
  return out
}

async function importPublicKey(b64: string): Promise<CryptoKey> {
  const bytes = toBytes(b64)
  return crypto.subtle.importKey(
    'spki',
    bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer,
    { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['encrypt'])
}

async function publicKey(forceReload = false): Promise<CryptoKey> {
  const r = await fetch('/api/auth/public-key')
  if (!r.ok) throw new AppError('无法获取加密公钥，请稍后重试')
  const j = await r.json() as { publicKey: string }
  const fingerprint = j.publicKey
  if (!forceReload && cachedKey && cachedFingerprint === fingerprint) return cachedKey
  cachedKey = await importPublicKey(fingerprint)
  cachedFingerprint = fingerprint
  return cachedKey
}

export async function encryptPassword(value: string): Promise<string> {
  if (!value) return value
  if (!globalThis.crypto?.subtle) {
    console.warn('当前不是安全上下文，密码无法加密传输')
    return value
  }
  let key = await publicKey()
  const buf = await crypto.subtle.encrypt({ name: 'RSA-OAEP' }, key, new TextEncoder().encode(value))
  let bin = ''
  new Uint8Array(buf).forEach(b => { bin += String.fromCharCode(b) })
  return btoa(bin)
}

let refreshing: Promise<boolean> | null = null
function ensureFresh(): Promise<boolean> {
  if (!refreshing) refreshing = doRefresh().finally(() => { refreshing = null })
  return refreshing
}

async function doRefresh(): Promise<boolean> {
  const rt = getRefresh()
  if (!rt) return false
  try {
    const r = await fetch('/api/auth/refresh', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: rt })
    })
    if (!r.ok) return false
    saveSession(await r.json() as LoginResult)
    return true
  } catch { return false }
}

function needAuth(url: string): boolean {
  const u = String(url || '')
  return u.indexOf('/api/') === 0 && u.indexOf('/api/auth/login') < 0 && u.indexOf('/api/auth/refresh') < 0
}

/** 全局 fetch 补丁：Bearer、401 并发共用一次刷新、刷新后重试——对齐旧版 */
export function installFetchPatch(onAuthLost: () => void): void {
  const rawFetch = window.fetch.bind(window)
  window.fetch = async function (input: RequestInfo | URL, options?: RequestInit): Promise<Response> {
    const url = String(input)
    const opts = options || {}
    let headers = new Headers(opts.headers || {})
    if (needAuth(url)) {
      const token = getAccess()
      if (token) headers.set('Authorization', `Bearer ${token}`)
    }
    const retry = async (retried: boolean): Promise<Response> => {
      const r = await rawFetch(input, { ...opts, headers })
      if (r.status === 401 && needAuth(url) && !retried && getRefresh()) {
        if (await ensureFresh()) {
          const h2 = new Headers(opts.headers || {})
          h2.set('Authorization', `Bearer ${getAccess()}`)
          return rawFetch(input, { ...opts, headers: h2 })
        }
        onAuthLost()
      }
      return r
    }
    return retry(false)
  }
}

export async function logout(): Promise<void> {
  try {
    await fetch('/api/auth/logout', { method: 'POST' })
  } catch { /* 网络失败也要清本地 */ }
  clearSession()
}
