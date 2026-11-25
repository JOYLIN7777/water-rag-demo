/**
 * 认证与会话：对齐后端 /api/auth/** 与既有 sessionStorage key，
 * 保证 Vue 版与旧 static 版可共用同一套登录态（便于灰度切换）。
 */
import { AppError, type CurrentUser, type LoginResult } from '@/types'

const KEY_ACCESS = 'wrk_access_token'
const KEY_REFRESH = 'wrk_refresh_token'
const KEY_USER = 'wrk_user_info'

export function getAccess(): string {
  try { return sessionStorage.getItem(KEY_ACCESS) || '' } catch { return '' }
}

export function getRefresh(): string {
  try { return sessionStorage.getItem(KEY_REFRESH) || '' } catch { return '' }
}

export function getUser(): CurrentUser | null {
  try {
    return JSON.parse(sessionStorage.getItem(KEY_USER) || 'null') as CurrentUser | null
  } catch { return null }
}

export function saveSession(data: LoginResult): void {
  try {
    sessionStorage.setItem(KEY_ACCESS, data.accessToken)
    sessionStorage.setItem(KEY_REFRESH, data.refreshToken)
    sessionStorage.setItem(KEY_USER, JSON.stringify({
      user: data.user, displayName: data.displayName, groups: data.groups, tenant: data.tenant
    }))
  } catch { /* 隐私模式下 sessionStorage 可能不可用，静默降级 */ }
}

export function clearSession(): void {
  try {
    sessionStorage.removeItem(KEY_ACCESS)
    sessionStorage.removeItem(KEY_REFRESH)
    sessionStorage.removeItem(KEY_USER)
  } catch { /* 同上 */ }
}

/** 获取服务端 RSA 公钥并加密密码（非安全上下文退回明文，后端仍校验 PBKDF2） */
let cachedKey: CryptoKey | null = null
let cachedKeyPromise: Promise<CryptoKey> | null = null

function toBytes(base64: string): Uint8Array {
  const raw = atob(base64)
  const out = new Uint8Array(raw.length)
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i)
  return out
}

async function publicKey(): Promise<CryptoKey> {
  if (cachedKey) return cachedKey
  if (!cachedKeyPromise) {
    cachedKeyPromise = (async () => {
      const r = await fetch('/api/auth/public-key')
      if (!r.ok) throw new AppError('无法获取加密公钥，请稍后重试')
      const j = await r.json() as { publicKey: string }
      const keyBytes = toBytes(j.publicKey)
      return crypto.subtle.importKey(
        'spki', keyBytes.buffer.slice(keyBytes.byteOffset, keyBytes.byteOffset + keyBytes.byteLength) as ArrayBuffer,
        { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['encrypt'])
    })()
  }
  cachedKey = await cachedKeyPromise
  return cachedKey
}

export async function encryptPassword(value: string): Promise<string> {
  if (!value) return value
  if (!globalThis.crypto?.subtle) {
    console.warn('当前不是安全上下文，密码无法加密传输')
    return value
  }
  const key = await publicKey()
  const buf = await crypto.subtle.encrypt({ name: 'RSA-OAEP' }, key, new TextEncoder().encode(value))
  let bin = ''
  new Uint8Array(buf).forEach(b => { bin += String.fromCharCode(b) })
  return btoa(bin)
}

/** 用 refresh token 换新 token；成功返回 true */
export async function refreshSession(): Promise<boolean> {
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

export async function logout(): Promise<void> {
  try {
    await fetch('/api/auth/logout', { method: 'POST', headers: { Authorization: `Bearer ${getAccess()}` } })
  } catch { /* 网络失败也要清本地态 */ }
  clearSession()
}
