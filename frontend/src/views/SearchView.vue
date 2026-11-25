<script setup lang="ts">
import { ref } from 'vue'
import { searchApi } from '@/api'

const q = ref('')
const results = ref<Record<string, any>>({})
const loading = ref(false)
const error = ref('')

async function run() {
  if (!q.value.trim()) return
  loading.value = true; error.value = ''
  try { results.value = await searchApi.compare(q.value) as any }
  catch (e: any) { error.value = e.message }
  finally { loading.value = false }
}
</script>

<template>
  <div class="page">
    <div class="page-head"><h1 class="page-title">检索对比</h1><p class="page-desc">对比关键词、语义与融合检索的结果。</p></div>
    <div class="card">
      <div class="toolbar">
        <el-input v-model="q" placeholder="输入检索词" style="max-width:420px" @keyup.enter="run" />
        <el-button type="primary" :loading="loading" @click="run">检索</el-button>
      </div>
      <div v-if="error" class="empty">{{ error }}</div>
      <div v-else-if="Object.keys(results).length" style="display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:14px">
        <div v-for="(list, name) in results" :key="name" class="card">
          <b>{{ name }}</b>
          <div v-for="(h, i) in (list as any[])" :key="i" style="margin-top:8px;font-size:12px;border-top:1px dashed var(--line);padding-top:8px">
            <div style="color:var(--muted)">{{ h.file }} · 第{{ (h.chunk ?? 0) + 1 }}段 · {{ h.score?.toFixed?.(4) }}</div>
            <div>{{ h.text }}</div>
          </div>
        </div>
      </div>
      <div v-else class="empty">输入检索词开始对比</div>
    </div>
  </div>
</template>
