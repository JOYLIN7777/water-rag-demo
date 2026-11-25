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
  try { rows.value = await adminApi.roles() as any[] }
  catch (e: any) { error.value = e.message || '暂时无法显示'; }
  finally { loading.value = false }
}
onMounted(load)

</script>

<template>
  <div class="page">
    <div class="page-head">
      <h1 class="page-title">角色定义</h1>
      <p class="page-desc">维护角色与权限配置。</p>
    </div>
    <div class="card">
      <div class="toolbar">
        <el-button size="small" @click="load">刷新</el-button>
      </div>
      <div v-if="error" class="empty">{{ error }}</div>
      <el-table v-else :data="rows" v-loading="loading" size="small" stripe>
      <el-table-column prop="role" label="角色" />
      <el-table-column prop="name" label="显示名" />
      <el-table-column prop="permissions" label="权限数" />
      </el-table>
    </div>
  </div>
</template>
