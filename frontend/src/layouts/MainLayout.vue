<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'
import { logout as apiLogout } from '@/api/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const chat = useChatStore()

const admin = computed(() => route.path.startsWith('/admin.html'))
const currentKey = computed(() => String(route.name || '').replace('-', '/'))

/** 后台侧栏：只取 console.* 分组，排除自引用与 profile */
const menuGroups = computed(() => auth.navGroups
  .filter(g => g.code.startsWith('console.'))
  .map(g => ({
    code: g.code,
    label: g.name,
    items: auth.menus.filter(m => m.allowed && m.group === g.code &&
      m.href !== 'profile.html' && m.href !== 'admin.html' && !m.permissions.includes('admin:access'))
  })).filter(g => g.items.length))

/** 问答侧栏：工作台四项 + 设置，白名单过滤 */
const chatMenus = computed(() => auth.menus.filter(m => {
  const href = String(m.href || '').replace(/^\//, '')
  if (!m.allowed || href === 'profile.html' || href === 'admin.html') return false
  return ['index.html', 'manage.html', 'search.html', 'tree.html', 'settings.html'].includes(href)
}))

/** 管理后台入口：有任一后台权限才显示（与旧版 renderAdminEntry 同规则） */
const adminEntry = computed(() => {
  const pages = [
    'admin:access', 'user:read', 'org:read', 'role:read', 'perm:read',
    'admin:docs', 'model:read', 'usage:read', 'loginlog:read', 'audit:read'
  ]
  if (!auth.has('chat:use')) return ''
  const hit = pages.find(p => auth.has(p))
  return hit ? '/admin.html/users.html' : ''
})

/** 用户回显：store 为空时从 sessionStorage 直接读，避免显示"未登录" */
const displayName = computed(() => auth.user?.displayName || auth.user?.user || '演示用户')
const tenantName = computed(() => auth.user?.tenant || 'default')
const userInitial = computed(() => displayName.value.slice(0, 1).toUpperCase())

onMounted(() => {
  // 历史会话从服务端同步一次（问答页也会调，幂等）
  if (!admin.value) void chat.syncSessions()
})

function hrefOf(m: { href: string }): string {
  return '/' + String(m.href).replace(/^\//, '')
}
function routeKey(m: { href: string }): string {
  return String(m.href).replace(/^\//, '').replace('.html', '')
}

/** 菜单图标组件名：按 href 映射，与旧版 ICONS 语义一致 */
const ICONS: Record<string, string> = {
  'index': 'ChatDotRound', 'manage': 'Document', 'search': 'Search', 'tree': 'Share',
  'settings': 'Setting', 'profile': 'User', 'users': 'User', 'org': 'OfficeBuilding',
  'roles': 'UserFilled', 'perms': 'Lock', 'admin-docs': 'Files', 'models': 'Cpu',
  'usage': 'DataAnalysis', 'loginlogs': 'Tickets', 'audit': 'List'
}
function iconOf(m: { href: string }): string {
  return ICONS[routeKey(m)] || 'Document'
}
function newChat() {
  const id = chat.newSession()
  void chat.switchSession(id)
  if (route.name !== 'index') void router.push('/index.html')
}
function pickSession(id: string) { void chat.switchSession(id) }
function removeSession(id: string, e: Event) { e.stopPropagation(); chat.removeSession(id) }

/** 退出二次确认：与旧版 confirmLogout 文案一致 */
async function doLogout() {
  const detail = auth.has('chat:use')
    ? '还没发出的提问会留在这台设备上，下次用同一账号登录还能接着写；之前的对话记录不受影响。'
    : '下次用同一账号登录，会回到你上次使用的页面。'
  try {
    await ElMessageBox.confirm(
      `退出后需要重新登录才能继续使用，确定现在退出吗？\n${detail}`,
      '确认退出登录？',
      { confirmButtonText: '退出登录', cancelButtonText: '继续留在当前页面', type: 'warning' }
    )
  } catch { return }
  await apiLogout()
  await auth.reset()
  await router.push('/login.html')
}
</script>

<template>
  <div class="layout">
    <aside class="sidebar" :class="{ admin }">
      <div class="brand">
        <div class="brand-mark has-logo"><span style="font-size:20px">◇</span></div>
        <div>
          <div class="brand-title">{{ admin ? '管理后台' : '水利知识库' }}</div>
          <div class="brand-sub">{{ admin ? '水利知识库' : 'RAG 智能问答系统' }}</div>
        </div>
      </div>

      <!-- 后台侧栏 -->
      <template v-if="admin">
        <div v-for="g in menuGroups" :key="g.code">
          <div class="section-label">{{ g.label }}</div>
          <RouterLink v-for="m in g.items" :key="m.code || m.href" :to="hrefOf(m)"
            class="nav-item" :class="{ active: routeKey(m) === currentKey }">
            <span class="ic"><el-icon><component :is="iconOf(m)" /></el-icon></span>{{ m.name }}
          </RouterLink>
        </div>
      </template>

      <!-- 问答侧栏：菜单 + 新建会话 + 历史会话 -->
      <template v-else>
        <div class="section-label" style="margin-top:0">工作台</div>
        <nav class="nav-list">
          <RouterLink v-for="m in chatMenus" :key="m.href" :to="hrefOf(m)"
            class="nav-item" :class="{ active: routeKey(m) === currentKey }">
            <span class="ic"><el-icon><component :is="iconOf(m)" /></el-icon></span>{{ m.name }}
          </RouterLink>
        </nav>
        <button class="new-chat" type="button" @click="newChat">＋ 新建会话</button>
        <div class="section-label">历史会话</div>
        <div class="session-list">
          <div v-for="s in chat.sessions" :key="s.id" class="session-item"
            :class="{ active: s.id === chat.currentId }" @click="pickSession(s.id)">
            <span class="session-name">{{ s.title || '新会话' }}</span>
            <span class="session-time">{{ s.updatedAt ? new Date(s.updatedAt).toLocaleDateString('zh-CN') : '' }}</span>
            <button class="session-delete" title="删除会话" @click="removeSession(s.id, $event)">×</button>
          </div>
        </div>
      </template>

      <div class="sidebar-back">
        <RouterLink v-if="admin && auth.has('chat:use')" to="/index.html" class="nav-item">
          <span class="ic">⌂</span>返回问答
        </RouterLink>
        <!-- 问答侧栏的管理后台入口 -->
        <RouterLink v-if="!admin && adminEntry" :to="adminEntry" class="nav-item admin-entry">
          <span class="ic">⚙</span>管理后台
        </RouterLink>
        <div class="user-block">
          <RouterLink class="user-avatar" to="/profile.html" title="个人中心">{{ userInitial }}</RouterLink>
          <RouterLink class="user-meta" to="/profile.html" title="个人中心">
            <div class="user-name">{{ displayName }}</div>
            <div class="user-tenant">{{ tenantName }}</div>
          </RouterLink>
          <RouterLink class="user-icon-btn" to="/settings.html" title="设置">⚙</RouterLink>
          <button class="user-logout" title="退出登录" @click="doLogout">⏻</button>
        </div>
      </div>
    </aside>
    <main class="main"><RouterView /></main>
  </div>
</template>

<style scoped>
.user-logout, .user-icon-btn { background: none; border: 0; color: #9fb0cf; font-size: 15px; cursor: pointer; padding: 4px; text-decoration: none; }
.user-logout:hover, .user-icon-btn:hover { color: #fff; }
.admin-entry { color: #8fb4ff; }
.new-chat { width: calc(100% - 12px); margin: 4px 6px 0; padding: 8px; border: 1px solid rgba(59,110,245,.4); background: rgba(59,110,245,.14); color: #cfe0ff; border-radius: 8px; font-size: 12px; cursor: pointer; }
.new-chat:hover { background: rgba(59,110,245,.24); color: #fff; }
.session-list { flex: 1; min-height: 0; overflow-y: auto; margin-top: 2px; }
.session-item { display: flex; align-items: center; gap: 6px; padding: 7px 10px; border-radius: 7px; color: #b9c6de; font-size: 12px; cursor: pointer; margin-bottom: 2px; }
.session-item:hover { background: rgba(255,255,255,.05); }
.session-item.active { background: rgba(59,110,245,.22); color: #fff; }
.session-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.session-time { font-size: 10px; color: #7f90b3; }
.session-delete { background: none; border: 0; color: #7f90b3; font-size: 14px; cursor: pointer; padding: 0 2px; }
.session-delete:hover { color: #ff9fbb; }
</style>
