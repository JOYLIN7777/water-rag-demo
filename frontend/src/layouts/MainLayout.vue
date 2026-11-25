<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const admin = computed(() => route.path.startsWith('/admin.html'))
const currentKey = computed(() => {
  const seg = route.name?.toString() || ''
  return seg.replace('-', '/')
})

const menuGroups = computed(() => {
  const groups = auth.navGroups.map(g => ({
    code: g.code,
    label: g.name,
    items: auth.menus.filter(m => m.allowed && m.group === g.code && m.href !== 'profile.html')
  }))
  const grouped = new Set(auth.navGroups.map(g => g.code))
  const rest = auth.menus.filter(m => m.allowed && !grouped.has(m.code || '') && !grouped.has(m.group || '') && m.href !== 'profile.html')
  if (rest.length) groups.push({ code: '__rest', label: '', items: rest })
  return groups.filter(g => g.items.length)
})

const chatMenus = computed(() => auth.menus.filter(m => m.allowed && !m.href.includes('admin') &&
  ['index.html', 'manage.html', 'search.html', 'tree.html', 'settings.html'].includes(m.href.replace(/^\//, ''))))

function hrefOf(m: { href: string }): string {
  return '/' + String(m.href).replace(/^\//, '')
}
function routeKey(m: { href: string }): string {
  return String(m.href).replace(/^\//, '').replace('.html', '')
}
async function doLogout() {
  await auth.reset()
  await router.push('/login.html')
}
</script>

<template>
  <div class="layout">
    <aside class="sidebar" :class="{ admin }">
      <div class="brand">
        <div class="brand-mark">◈</div>
        <div>
          <div class="brand-title">{{ admin ? '管理后台' : '水利知识库' }}</div>
          <div class="brand-sub">{{ admin ? '水利知识库' : 'RAG 智能问答系统' }}</div>
        </div>
      </div>

      <template v-if="admin">
        <div v-for="g in menuGroups" :key="g.code">
          <div v-if="g.label" class="section-label">{{ g.label }}</div>
          <RouterLink v-for="m in g.items" :key="m.code || m.href"
            :to="hrefOf(m)" class="nav-item"
            :class="{ active: routeKey(m) === currentKey }">
            <span class="ic">{{ m.icon || '▤' }}</span>{{ m.name }}
          </RouterLink>
        </div>
      </template>
      <template v-else>
        <div class="section-label">工作台</div>
        <RouterLink v-for="m in chatMenus" :key="m.href" :to="hrefOf(m)" class="nav-item"
          :class="{ active: routeKey(m) === currentKey }">
          <span class="ic">◈</span>{{ m.name }}
        </RouterLink>
      </template>

      <div class="sidebar-back">
        <RouterLink v-if="auth.has('chat:use')" to="/index.html" class="nav-item">
          <span class="ic">⌂</span>返回问答
        </RouterLink>
        <div class="user-block">
          <span class="brand-mark" style="width:32px;height:32px;flex-basis:32px;font-size:13px">
            {{ auth.user?.displayName?.slice(0, 1) || '?' }}
          </span>
          <div class="user-meta" style="flex:1;min-width:0">
            <div class="user-name">{{ auth.user?.displayName || auth.user?.user || '未登录' }}</div>
            <div class="user-tenant">{{ auth.user?.tenant || '—' }}</div>
          </div>
          <button class="user-logout" title="退出登录" @click="doLogout">⏻</button>
        </div>
      </div>
    </aside>
    <main class="main">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.user-logout { background: none; border: 0; color: #9fb0cf; font-size: 16px; cursor: pointer; padding: 4px; }
.user-logout:hover { color: #fff; }
</style>
