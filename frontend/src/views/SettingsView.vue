<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const cards = computed(() => auth.menus.filter(m => m.allowed && m.href !== 'profile.html'))
</script>

<template>
  <div class="page">
    <div class="page-head"><h1 class="page-title">设置</h1><p class="page-desc">进入各功能模块。</p></div>
    <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:14px">
      <RouterLink v-for="m in cards" :key="m.href" :to="'/' + String(m.href).replace(/^\//, '')" class="card" style="display:block;transition:.15s">
        <div style="font-weight:650;margin-bottom:6px">{{ m.icon || '◈' }} {{ m.name }}</div>
        <div style="color:var(--muted);font-size:12px">进入功能 →</div>
      </RouterLink>
    </div>
  </div>
</template>
