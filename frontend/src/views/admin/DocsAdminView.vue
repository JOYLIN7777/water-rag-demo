<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { docApi, uploadDocument } from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const fileInput = ref<HTMLInputElement | null>(null)
const rows = ref<any[]>([])
const loading = ref(false)
const error = ref('')
const kw = ref('')
const dialogVisible = ref(false)
const includeSubtree = ref(false)
const uploading = ref(false)
const progress = ref(0)
const chunksVisible = ref(false)
const chunks = ref<any[]>([])

async function load() {
  loading.value = true; error.value = ''
  try { rows.value = await docApi.list() as any[] }
  catch (e: any) { error.value = e.message }
  finally { loading.value = false }
}
onMounted(load)

const filtered = () => {
  const k = kw.value.trim().toLowerCase()
  return k ? rows.value.filter(r => String(r.fileName || '').toLowerCase().includes(k)) : rows.value
}

async function doUpload() {
  const input = fileInput.value
  const files = Array.from(input?.files || [])
  if (!files.length) return
  uploading.value = true
  let ok = 0
  for (const f of files) {
    try { await uploadDocument(f, p => { progress.value = p }, includeSubtree.value); ok++ }
    catch (e: any) { error.value = e.message }
  }
  uploading.value = false
  dialogVisible.value = false
  if (input) input.value = ''
  ElMessage.success(ok ? `已入库 ${ok} 个文档` : '没有文档入库')
  await load()
}

async function showChunks(fileName: string) {
  try { chunks.value = await docApi.chunks(fileName) as any[]; chunksVisible.value = true }
  catch (e: any) { error.value = e.message }
}

async function remove(row: any) {
  try {
    await ElMessageBox.confirm(`确定删除「${row.fileName}」及其全部切片吗？删除后无法恢复。`, '删除文档', { type: 'warning' })
    await docApi.delete(row.fileName, row.tenantId)
    ElMessage.success('已删除'); await load()
  } catch { /* 用户取消 */ }
}
</script>

<template>
  <div class="page">
    <div class="page-head"><h1 class="page-title">知识库文档</h1><p class="page-desc">上传与管理知识库文档，删除会同时移除其全部切片。</p></div>
    <div class="card">
      <div class="toolbar">
        <el-input v-model="kw" placeholder="搜索文档名称" style="max-width:260px" />
        <el-button v-if="auth.has('doc:write_batch')" type="primary" size="small" @click="dialogVisible = true">＋ 选择文件</el-button>
        <span class="grow" />
        <el-button size="small" @click="load">刷新</el-button>
      </div>
      <div v-if="error" class="empty">{{ error }}</div>
      <el-table v-else :data="filtered()" v-loading="loading" size="small" stripe>
        <el-table-column prop="fileName" label="文档名称" min-width="220" />
        <el-table-column prop="chunks" label="切片数" width="100" />
        <el-table-column prop="ownerUser" label="上传人" width="120" />
        <el-table-column prop="uploadedAt" label="上传时间" width="180" />
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button v-if="auth.has('doc:chunks')" link type="primary" size="small" @click="showChunks(row.fileName)">查看分片</el-button>
            <el-button v-if="auth.has('doc:delete')" link type="danger" size="small" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" title="上传文档" width="460px">
      <input ref="fileInput" id="vueDocFile" type="file" multiple hidden />
      <el-button @click="fileInput?.click()">选择文件</el-button>
      <el-checkbox v-model="includeSubtree" style="display:block;margin-top:12px">含下级部门可见</el-checkbox>
      <div v-if="uploading" style="margin-top:12px"><el-progress :percentage="progress" /></div>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="doUpload">确认上传</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="chunksVisible" title="文档分片" width="720px">
      <div v-for="c in chunks" :key="c.chunkIndex" class="card" style="margin-bottom:8px;padding:10px 14px">
        <b>第{{ (c.chunkIndex ?? 0) + 1 }}片</b>
        <div style="font-size:12px;color:var(--muted);margin-top:4px">{{ c.content }}</div>
      </div>
    </el-dialog>
  </div>
</template>
