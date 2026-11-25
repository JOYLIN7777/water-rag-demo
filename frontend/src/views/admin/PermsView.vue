<script setup lang="ts">
import { ref, onMounted, h } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api'
import type { PermissionNode } from '@/types'

const rows = ref<PermissionNode[]>([])
const loading = ref(false)
const error = ref('')
const kw = ref('')

async function load() {
  loading.value = true; error.value = ''
  try { rows.value = await adminApi.perms() as PermissionNode[] }
  catch (e: any) { error.value = e.message }
  finally { loading.value = false }
}
onMounted(load)
const filtered = () => {
  const k = kw.value.trim().toLowerCase()
  return k ? rows.value.filter(r => [r.name, r.code, r.module].some(v => String(v || '').toLowerCase().includes(k))) : rows.value
}
async function remove(row: PermissionNode) {
  try {
    await ElMessageBox.confirm(`删除「${row.name}」(${row.code}) 吗？`, '删除权限节点', { type: 'warning' })
    await adminApi.deletePerm(row.code); ElMessage.success('已删除'); await load()
  } catch { /* 取消 */ }
}
</script>

<template>
  <div class="page">
    <div class="page-head"><h1 class="page-title">菜单权限</h1><p class="page-desc">维护权限节点与角色授权。</p></div>
    <div class="card">
      <div class="toolbar">
        <el-input v-model="kw" placeholder="搜索名称 / 权限标识" style="max-width:260px" />
        <span class="grow" />
        <el-button size="small" @click="load">刷新</el-button>
      </div>
      <div v-if="error" class="empty">{{ error }}</div>
      <el-table v-else :data="filtered()" v-loading="loading" size="small" stripe>
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column label="类型" width="90"><template #default="{ row }">{{ ({directory:'目录',menu:'菜单',btn:'按钮'} as Record<string,string>)[String(row.nodeType)] || row.nodeType }}</template></el-table-column>
        <el-table-column prop="code" label="权限标识" min-width="180" />
        <el-table-column prop="module" label="模块" width="120" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button v-if="!row.builtin" link type="danger" size="small" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>
