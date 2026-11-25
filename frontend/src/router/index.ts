/** 路由表：与 static 版页面一一对应，按权限守卫 */
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import MainLayout from '@/layouts/MainLayout.vue'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/index.html' },
  { path: '/login.html', name: 'login', component: () => import('@/views/LoginView.vue') },
  {
    path: '/',
    component: MainLayout,
    children: [
      { path: 'index.html', name: 'index', component: () => import('@/views/IndexView.vue'), meta: { perm: 'chat:use' } },
      { path: 'manage.html', name: 'manage', component: () => import('@/views/DocsView.vue'), meta: { perm: 'doc:read' } },
      { path: 'search.html', name: 'search', component: () => import('@/views/SearchView.vue'), meta: { perm: 'search:use' } },
      { path: 'tree.html', name: 'tree', component: () => import('@/views/TreeView.vue'), meta: { perm: 'tree:use' } },
      { path: 'settings.html', name: 'settings', component: () => import('@/views/SettingsView.vue') },
      { path: 'profile.html', name: 'profile', component: () => import('@/views/ProfileView.vue') },
      { path: 'admin.html', redirect: '/admin.html/users.html' },
      { path: 'admin.html/users.html', name: 'users', component: () => import('@/views/admin/UsersView.vue'), meta: { perm: 'user:read' } },
      { path: 'admin.html/org.html', name: 'org', component: () => import('@/views/admin/OrgView.vue'), meta: { perm: 'org:read' } },
      { path: 'admin.html/roles.html', name: 'roles', component: () => import('@/views/admin/RolesView.vue'), meta: { perm: 'role:read' } },
      { path: 'admin.html/perms.html', name: 'perms', component: () => import('@/views/admin/PermsView.vue'), meta: { perm: 'perm:read' } },
      { path: 'admin.html/docs.html', name: 'admin-docs', component: () => import('@/views/admin/DocsAdminView.vue'), meta: { perm: 'admin:docs' } },
      { path: 'admin.html/models.html', name: 'models', component: () => import('@/views/admin/ModelsView.vue'), meta: { perm: 'model:read' } },
      { path: 'admin.html/usage.html', name: 'usage', component: () => import('@/views/admin/UsageView.vue'), meta: { perm: 'usage:read' } },
      { path: 'admin.html/loginlogs.html', name: 'loginlogs', component: () => import('@/views/admin/LoginLogsView.vue'), meta: { perm: 'loginlog:read' } },
      { path: 'admin.html/audit.html', name: 'audit', component: () => import('@/views/admin/AuditView.vue'), meta: { perm: 'audit:read' } }
    ]
  },
  // 兼容旧 static 独立路由，重定向到 Vue 的嵌套路由
  { path: '/users.html', redirect: '/admin.html/users.html' },
  { path: '/org.html', redirect: '/admin.html/org.html' },
  { path: '/roles.html', redirect: '/admin.html/roles.html' },
  { path: '/perms.html', redirect: '/admin.html/perms.html' },
  { path: '/docs.html', redirect: '/admin.html/docs.html' },
  { path: '/models.html', redirect: '/admin.html/models.html' },
  { path: '/usage.html', redirect: '/admin.html/usage.html' },
  { path: '/loginlogs.html', redirect: '/admin.html/loginlogs.html' },
  { path: '/audit.html', redirect: '/admin.html/audit.html' },
  { path: '/:pathMatch(.*)*', redirect: '/index.html' }
]

export const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach(async to => {
  if (to.name === 'login') return true
  const auth = useAuthStore()
  if (!auth.loaded) await auth.loadPermissions()
  const perm = to.meta.perm as string | undefined
  if (perm && !auth.has(perm)) return { name: 'login' }
  return true
})
