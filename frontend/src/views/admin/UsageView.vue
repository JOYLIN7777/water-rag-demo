<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { usageApi } from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const rows = ref<any[]>([])
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try { rows.value = await usageApi.summary() as unknown as any[] }
  catch (e: any) { error.value = e.message || '暂时无法显示'; }
  finally { loading.value = false }
}
onMounted(load)

</script>

<template>
  <div class="page">
    <div class="page-head">
      <h1 class="page-title">用量统计</h1>
      <p class="page-desc">查看模型调用与 Token 消耗。</p>
    </div>
    <div class="card">
      <div class="toolbar">
        <el-button size="small" @click="load">刷新</el-button>
      </div>
      <div v-if="error" class="empty">{{ error }}</div>
      <el-table v-else :data="rows" v-loading="loading" size="small" stripe>
      <el-table-column prop="model" label="模型" />
      <el-table-column prop="requests" label="请求次数" />
      <el-table-column prop="inputTokens" label="输入Token" />
      </el-table>
    </div>
  </div>
</template>
