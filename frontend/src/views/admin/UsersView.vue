<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const rows = ref<any[]>([])
const loading = ref(false)
const error = ref('')
const kw = ref('')

async function load() {
  loading.value = true; error.value = ''
  try { rows.value = await adminApi.users() as any[] }
  catch (e: any) { error.value = e.message }
  finally { loading.value = false }
}
onMounted(load)
const filtered = () => {
  const k = kw.value.trim().toLowerCase()
  return k ? rows.value.filter(r => [r.username, r.displayName, r.tenant].some(v => String(v || '').toLowerCase().includes(k))) : rows.value
}
async function remove(row: any) {
  try {
    await ElMessageBox.confirm(`确定删除账号「${row.username}」吗？`, '删除账号', { type: 'warning' })
    await adminApi.deleteUser(row.username); ElMessage.success('已删除'); await load()
  } catch { /* 取消 */ }
}
</script>

<template>
  <div class="page">
    <div class="page-head"><h1 class="page-title">用户管理</h1><p class="page-desc">按账号筛选和管理账号。</p></div>
    <div class="card">
      <div class="toolbar">
        <el-input v-model="kw" placeholder="搜索账号 / 显示名" style="max-width:240px" />
        <span class="grow" />
        <el-button size="small" @click="load">刷新</el-button>
      </div>
      <div v-if="error" class="empty">{{ error }}</div>
      <el-table v-else :data="filtered()" v-loading="loading" size="small" stripe>
        <el-table-column prop="username" label="账号" />
        <el-table-column prop="displayName" label="显示名" />
        <el-table-column label="所属角色"><template #default="{ row }">{{ (row.groups || []).join('、') }}</template></el-table-column>
        <el-table-column prop="tenant" label="所属组织" />
        <el-table-column label="状态"><template #default="{ row }">{{ row.enabled === false ? '停用' : '正常' }}</template></el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button v-if="auth.has('user:write') && !(row.groups || []).includes('admin')" link type="danger" size="small" @click="remove(row)">删除</el-button>
            <span v-else style="color:var(--muted);font-size:11px">不可编辑</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>
