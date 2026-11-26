/** 认证与权限状态：单一数据源，对齐旧版 Shell.hasPermission */
import { create } from 'zustand'
import { authApi } from '@/api'
import { http } from '@/api/http'
import { getUser, clearSession, scope } from '@/api/auth'
import type { CurrentUser, PermissionsResponse, MenuItem, NavGroup, RoleItem } from '@/types'

interface AuthState {
  user: CurrentUser | null
  permissions: string[]
  allPermissions: string[]
  menus: MenuItem[]
  navGroups: NavGroup[]
  roleMatrix: RoleItem[]
  loaded: boolean
  loading: boolean
  has: (perm: string | undefined) => boolean
  isAdmin: () => boolean
  /** 角色码 → 中文名（对齐旧版 gl()：优先服务端角色定义，回落原码） */
  groupLabel: (code: string) => string
  loadPermissions: () => Promise<PermissionsResponse | null>
  landingPage: () => string
  reset: () => void
}

export const useAuthStore = create<AuthState>((set, get) => ({
  user: getUser(),
  permissions: [],
  allPermissions: [],
  menus: [],
  navGroups: [],
  roleMatrix: [],
  loaded: false,
  loading: false,

  has: (perm) => {
    if (!perm) return true
    if (get().isAdmin()) return true
    return get().permissions.includes(perm)
  },
  isAdmin: () => !!get().user?.admin || get().permissions.includes('admin:access'),

  groupLabel: (code) => {
    if (!code) return ''
    const hit = get().roleMatrix.find(r => r.role === code)
    return hit?.name || code
  },

  loadPermissions: async () => {
    if (get().loading) return null
    set({ loading: true })
    try {
      const j = await authApi.permissions()
      // 显示名/组织名从 /me 取（permissions 接口不返回 displayName）
      let me: { displayName?: string; tenant?: string } | null = null
      try { me = await http.get<{ displayName?: string; tenant?: string }>('/api/auth/me') }
      catch { /* 失败时用 sessionStorage 里的旧值 */ }
      set({
        user: j.user ? {
          user: j.user,
          displayName: me?.displayName || j.displayName || get().user?.displayName || j.user,
          groups: j.groups || get().user?.groups || [],
          tenant: me?.tenant || j.tenant || get().user?.tenant || 'default',
          admin: !!j.admin,
          anonymous: !!j.anonymous
        } : get().user,
        permissions: j.permissions || [],
        allPermissions: j.allPermissions || [],
        menus: j.menus || [],
        navGroups: j.navGroups || [],
        roleMatrix: j.roleMatrix || [],
        loaded: true
      })
      return j
    } catch {
      set({ loaded: false })
      return null
    } finally {
      set({ loading: false })
    }
  },

  /** 落地页：按服务端菜单顺序取第一个 allowed，问答优先 */
  landingPage: () => {
    const menus = get().menus
    for (const m of menus) {
      if (!m.allowed) continue
      const href = String(m.href || '').replace(/^\//, '')
      if (!href || href === 'profile.html' || href === 'login.html') continue
      return '/' + href
    }
    return '/profile.html'
  },

  reset: () => {
    clearSession()
    set({ user: null, permissions: [], allPermissions: [], menus: [], navGroups: [], roleMatrix: [], loaded: false })
  }
}))

/** 账号级 localStorage key（切账号不串数据） */
export function scopedKey(name: string): string {
  return `${scope()}:${name}`
}
