/** 认证与权限状态：单一数据源，所有页面从这里读权限 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { CurrentUser, PermissionsResponse, MenuItem, NavGroup } from '@/types'
import { authApi } from '@/api'
import { getUser, clearSession } from '@/api/auth'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<CurrentUser | null>(getUser())
  const permissions = ref<string[]>([])
  const allPermissions = ref<string[]>([])
  const menus = ref<MenuItem[]>([])
  const navGroups = ref<NavGroup[]>([])
  const loaded = ref(false)
  const loading = ref(false)

  const isAdmin = computed(() => !!user.value?.admin || permissions.value.includes('admin:access'))
  const isLoggedIn = computed(() => !!user.value?.user)

  function has(perm: string | undefined): boolean {
    if (!perm) return true
    if (isAdmin.value) return true
    return permissions.value.includes(perm)
  }

  /** 加载权限视图；返回是否有权访问当前路径对应的页面 */
  async function loadPermissions(): Promise<PermissionsResponse | null> {
    if (loading.value) return null
    loading.value = true
    try {
      const j = await authApi.permissions()
      permissions.value = j.permissions || []
      allPermissions.value = j.allPermissions || []
      menus.value = j.menus || []
      navGroups.value = j.navGroups || []
      if (user.value) {
        user.value = {
          ...user.value,
          user: j.user || user.value.user,
          groups: j.groups || user.value.groups,
          tenant: j.tenant || user.value.tenant,
          admin: !!j.admin
        }
      }
      loaded.value = true
      return j
    } catch {
      loaded.value = false
      return null
    } finally {
      loading.value = false
    }
  }

  /** 登录落地页：按服务端菜单顺序取第一个 allowed（问答在前、后台在后） */
  function landingPage(): string {
    for (const m of menus.value) {
      if (!m.allowed) continue
      const href = String(m.href || '').replace(/^\//, '')
      if (!href || href === 'profile.html') continue
      return href
    }
    return 'profile'
  }

  function reset(): void {
    user.value = null
    permissions.value = []
    allPermissions.value = []
    menus.value = []
    navGroups.value = []
    loaded.value = false
    clearSession()
  }

  return {
    user, permissions, allPermissions, menus, navGroups, loaded, loading,
    isAdmin, isLoggedIn, has, loadPermissions, landingPage, reset
  }
})
