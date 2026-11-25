<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const rows = ref<any[]>([])
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try { rows.value = await adminApi.auditLogs() as any[] }
  catch (e: any) { error.value = e.message || '暂时无法显示'; }
  finally { loading.value = false }
}
onMounted(load)

</script>

<template>
  <div class="page">
    <div class="page-head">
      <h1 class="page-title">操作日志</h1>
      <p class="page-desc">记录管理端的所有操作。</p>
    </div>
    <div class="card">
      <div class="toolbar">
        <el-button size="small" @click="load">刷新</el-button>
      </div>
      <div v-if="error" class="empty">{{ error }}</div>
      <el-table v-else :data="rows" v-loading="loading" size="small" stripe>
      <el-table-column prop="user_id" label="账号" />
      <el-table-column prop="action" label="操作" />
      <el-table-column prop="status" label="状态" />
      <el-table-column prop="result" label="结果" />
      </el-table>
    </div>
  </div>
</template>
