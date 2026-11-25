<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { chatApi } from '@/api'
import { useChatStore } from '@/stores/chat'

const chat = useChatStore()
const data = ref<any>(null)
const error = ref('')

async function load() {
  if (!chat.currentId) return
  try { data.value = await chatApi.tree(chat.currentId) as any }
  catch (e: any) { error.value = e.message }
}
onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head"><h1 class="page-title">会话结构</h1><p class="page-desc">查看与切换问答分支。</p></div>
    <div class="card">
      <div v-if="error" class="empty">{{ error }}</div>
      <div v-else-if="data?.nodes?.length">
        <div v-for="n in data.nodes" :key="n.id" class="tree-node" :class="{ current: n.id === data.head }">
          <span class="ic">{{ n.role === 'user' ? '问' : '答' }}</span>
          <span>{{ String(n.content || '').slice(0, 80) }}</span>
        </div>
      </div>
      <div v-else class="empty">当前会话还没有消息</div>
    </div>
  </div>
</template>

<style scoped>
.tree-node { display: flex; gap: 10px; padding: 9px 12px; border-radius: 8px; font-size: 12.5px; margin-bottom: 4px; }
.tree-node.current { background: var(--primary-soft); }
.ic { color: var(--primary); font-weight: 700; }
</style>
