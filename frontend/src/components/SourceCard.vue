<script setup>
import { computed } from 'vue'

const props = defineProps({
  sources: { type: Array, default: () => [] },
  open: { type: Boolean, default: false }
})
const emit = defineEmits(['toggle'])

const summary = computed(() => {
  if (!props.sources.length) return ''
  const names = [...new Set(props.sources.map(s => s.fileName))]
  return names.length === 1 ? names[0] : `${names[0]} 等 ${names.length} 个文档`
})
</script>

<template>
  <div v-if="sources.length" class="source-card">
    <button class="source-head" @click="emit('toggle')">
      <span class="source-icon">▤</span>
      <span class="source-title">参考了 {{ sources.length }} 个知识片段 · {{ summary }}</span>
      <span class="source-arrow">{{ open ? '▴' : '▾' }}</span>
    </button>
    <div v-if="open" class="source-list">
      <div v-for="(s, i) in sources" :key="i" class="source-item">
        <div class="source-meta">
          <span class="source-file">{{ s.fileName }}</span>
          <span class="source-index">第 {{ Number(s.chunkIndex) + 1 }} 段</span>
        </div>
        <div class="source-preview">{{ s.preview }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.source-card {
  border: 1px solid #dbeafe;
  background: #f8fbff;
  border-radius: 10px;
  margin-bottom: 8px;
  overflow: hidden;
}
.source-head {
  display: flex; align-items: center; gap: 7px; width: 100%;
  border: 0; background: transparent; padding: 9px 12px;
  color: #1e40af; font-size: 12.5px; text-align: left;
}
.source-head:hover { background: #eff6ff; }
.source-icon { font-size: 15px; }
.source-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.source-arrow { font-size: 10px; color: #60a5fa; }
.source-list { border-top: 1px solid #dbeafe; padding: 4px 12px 10px; }
.source-item { padding: 8px 0; border-bottom: 1px dashed #dbeafe; }
.source-item:last-child { border-bottom: 0; }
.source-meta { display: flex; gap: 10px; align-items: center; margin-bottom: 4px; }
.source-file { font-size: 12px; font-weight: 600; color: #1e3a8a; }
.source-index {
  font-size: 11px; color: #3b82f6; background: #dbeafe;
  padding: 1px 6px; border-radius: 10px;
}
.source-preview { font-size: 12.5px; color: #4b5563; line-height: 1.6; }
</style>
