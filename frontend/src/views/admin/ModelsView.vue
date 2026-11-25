<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { modelApi } from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const rows = ref<any[]>([])
const loading = ref(false)
const error = ref('')
const dialog = ref(false)
const form = ref<any>({ name: '', type: 'CHAT', baseUrl: '', model: '', apiKey: '', dimensions: null })

async function load() {
  loading.value = true; error.value = ''
  try { rows.value = await modelApi.list() as any[] }
  catch (e: any) { error.value = e.message }
  finally { loading.value = false }
}
onMounted(load)

async function createModel() {
  try {
    await modelApi.create(form.value); dialog.value = false
    ElMessage.success('配置已添加'); await load()
  } catch (e: any) { error.value = e.message }
}
async function activate(id: number) {
  try { await modelApi.activate(id); ElMessage.success('已激活'); await load() }
  catch (e: any) { error.value = e.message }
}
async function testModel(id: number) {
  try { const j = await modelApi.test(id) as any; ElMessage.success(j.result || '测试完成') }
  catch (e: any) { error.value = e.message }
}
async function remove(id: number) {
  try {
    await ElMessageBox.confirm('确定删除该模型配置吗？', '删除', { type: 'warning' })
    await modelApi.remove(id); ElMessage.success('已删除'); await load()
  } catch { /* 取消 */ }
}
</script>

<template>
  <div class="page">
    <div class="page-head"><h1 class="page-title">模型设置</h1><p class="page-desc">设置对话与检索模型。</p></div>
    <div class="card">
      <div class="toolbar">
        <el-button v-if="auth.has('model:write')" type="primary" size="small" @click="dialog = true">＋ 添加配置</el-button>
        <span class="grow" />
        <el-button size="small" @click="load">刷新</el-button>
      </div>
      <div v-if="error" class="empty">{{ error }}</div>
      <el-table v-else :data="rows" v-loading="loading" size="small" stripe>
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="type" label="类型" width="110" />
        <el-table-column prop="model" label="模型" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">{{ row.active ? '使用中' : row.enabled ? '待命' : '已禁用' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <el-button v-if="!row.active && auth.has('model:write')" link type="primary" size="small" @click="activate(row.id)">激活</el-button>
            <el-button v-if="auth.has('model:write')" link size="small" @click="testModel(row.id)">测试</el-button>
            <el-button v-if="!row.active && auth.has('model:write')" link type="danger" size="small" @click="remove(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="添加模型配置" width="520px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="类型"><el-select v-model="form.type"><el-option label="CHAT 对话" value="CHAT" /><el-option label="EMBEDDING 向量" value="EMBEDDING" /></el-select></el-form-item>
        <el-form-item label="服务地址"><el-input v-model="form.baseUrl" /></el-form-item>
        <el-form-item label="模型名"><el-input v-model="form.model" /></el-form-item>
        <el-form-item label="访问密钥"><el-input v-model="form.apiKey" type="password" /></el-form-item>
        <el-form-item v-if="form.type === 'EMBEDDING'" label="向量维度"><el-input-number v-model="form.dimensions" :min="1" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" @click="createModel">创建</el-button></template>
    </el-dialog>
  </div>
</template>
