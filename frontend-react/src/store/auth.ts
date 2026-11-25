/** 认证与权限状态：单一数据源，对齐旧版 Shell.hasPermission */
import { create } from 'zustand'
import { authApi } from '@/api'
import { getUser, clearSession, scope } from '@/api/auth'
import type { CurrentUser, PermissionsResponse, MenuItem, NavGroup } from '@/types'

interface AuthState {
  user: CurrentUser | null
  permissions: string[]
  allPermissions: string[]
  menus: MenuItem[]
  navGroups: NavGroup[]
  loaded: boolean
  loading: boolean
  has: (perm: string | undefined) => boolean
  isAdmin: () => boolean
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
  loaded: false,
  loading: false,

  has: (perm) => {
    if (!perm) return true
    if (get().isAdmin()) return true
    return get().permissions.includes(perm)
  },
  isAdmin: () => !!get().user?.admin || get().permissions.includes('admin:access'),

  loadPermissions: async () => {
    if (get().loading) return null
    set({ loading: true })
    try {
      const j = await authApi.permissions()
      set({
        user: j.user ? {
          user: j.user,
          displayName: j.displayName || get().user?.displayName || j.user,
          groups: j.groups || get().user?.groups || [],
          tenant: j.tenant || get().user?.tenant || 'default',
          admin: !!j.admin,
          anonymous: !!j.anonymous
        } : get().user,
        permissions: j.permissions || [],
        allPermissions: j.allPermissions || [],
        menus: j.menus || [],
        navGroups: j.navGroups || [],
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
    set({ user: null, permissions: [], allPermissions: [], menus: [], navGroups: [], loaded: false })
  }
}))

/** 账号级 localStorage key（切账号不串数据） */
export function scopedKey(name: string): string {
  return `${scope()}:${name}`
}
