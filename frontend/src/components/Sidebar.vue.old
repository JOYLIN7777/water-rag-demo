<script setup>
import { ref, onMounted } from 'vue'
import { uploadDocument, fetchFormats } from '../api.js'

const props = defineProps({
  sessions: { type: Array, required: true },
  currentId: { type: String, default: '' }
})
const emit = defineEmits(['select', 'new', 'remove', 'uploaded'])

const formats = ref([])
const dragging = ref(false)
const uploading = ref(false)
const progress = ref(0)
const uploadMsg = ref('')
const fileInput = ref(null)

onMounted(async () => {
  try {
    const data = await fetchFormats()
    formats.value = data.extensions
  } catch {
    formats.value = ['txt', 'md', 'pdf', 'doc', 'docx', 'xls', 'xlsx', 'csv']
  }
})

function shortId(id) {
  return id.startsWith('session-') ? id.slice(8, 14) : id.slice(0, 8)
}

async function handleFile(file) {
  if (!file) return
  uploading.value = true
  progress.value = 0
  uploadMsg.value = `正在解析 ${file.name} ...`
  try {
    const result = await uploadDocument(file, p => { progress.value = p })
    uploadMsg.value = `✓ ${file.name} 已入库（${result.chunks} 个切片）`
    emit('uploaded', result)
  } catch (e) {
    uploadMsg.value = `✗ ${e.message}`
  } finally {
    uploading.value = false
    setTimeout(() => { uploadMsg.value = '' }, 5000)
  }
}

function onDrop(e) {
  dragging.value = false
  handleFile(e.dataTransfer.files[0])
}
</script>

<template>
  <aside class="sidebar">
    <div class="brand">
      <div class="brand-mark">水</div>
      <div>
        <div class="brand-title">水利知识库</div>
        <div class="brand-sub">RAG 智能问答系统</div>
      </div>
    </div>

    <button class="new-chat" @click="emit('new')">
      <span class="plus">＋</span> 新建会话
    </button>

    <div class="section-label">历史会话</div>
    <div class="session-list">
      <div
        v-for="s in sessions" :key="s.id"
        class="session-item" :class="{ active: s.id === currentId }"
        @click="emit('select', s.id)"
      >
        <span class="session-dot"></span>
        <span class="session-name">{{ s.title || `会话 ${shortId(s.id)}` }}</span>
        <button
          class="session-delete" title="删除本地会话"
          @click.stop="emit('remove', s.id)"
        >×</button>
      </div>
    </div>

    <div class="upload-area">
      <div class="section-label">知识库文档</div>
      <div
        class="dropzone" :class="{ dragging }"
        @dragover.prevent="dragging = true"
        @dragleave.prevent="dragging = false"
        @drop.prevent="onDrop"
        @click="fileInput?.click()"
      >
        <div class="drop-icon">⇪</div>
        <div class="drop-text">{{ uploading ? `上传中 ${progress}%` : '点击或拖拽上传文档' }}</div>
        <div class="drop-formats">{{ formats.join(' / ') }}</div>
      </div>
      <div v-if="uploadMsg" class="upload-msg">{{ uploadMsg }}</div>
      <input
        ref="fileInput" type="file" hidden
        :accept="formats.map(f => '.' + f).join(',')"
        @change="handleFile($event.target.files[0]); $event.target.value = ''"
      />
    </div>

    <div class="sidebar-footer">Spring Boot 3.4 · Spring AI · pgvector</div>
  </aside>
</template>

<style scoped>
.sidebar {
  width: 272px; flex: 0 0 272px; height: 100%;
  background: #fff; border-right: 1px solid #e5e7eb;
  display: flex; flex-direction: column; padding: 18px 14px 12px;
}
.brand { display: flex; align-items: center; gap: 10px; padding: 0 4px 18px; }
.brand-mark {
  width: 36px; height: 36px; border-radius: 9px; background: #2563eb; color: #fff;
  display: flex; align-items: center; justify-content: center; font-size: 17px; font-weight: 600;
}
.brand-title { font-size: 15px; font-weight: 600; color: #111827; }
.brand-sub { font-size: 11px; color: #9ca3af; margin-top: 2px; }
.new-chat {
  width: 100%; padding: 10px; border: 0; border-radius: 9px;
  background: #2563eb; color: #fff; font-size: 13px; font-weight: 500;
  display: flex; align-items: center; justify-content: center; gap: 6px;
}
.new-chat:hover { background: #1d4ed8; }
.plus { font-size: 17px; line-height: 1; }
.section-label {
  font-size: 11px; color: #9ca3af; text-transform: uppercase;
  letter-spacing: .5px; margin: 20px 4px 8px;
}
.session-list { flex: 1; overflow-y: auto; min-height: 60px; }
.session-item {
  display: flex; align-items: center; gap: 8px; padding: 9px 10px;
  border-radius: 8px; font-size: 13px; color: #374151; cursor: pointer;
}
.session-item:hover { background: #f3f4f6; }
.session-item.active { background: #eff6ff; color: #1d4ed8; }
.session-dot { width: 6px; height: 6px; border-radius: 50%; background: #cbd5e1; flex: 0 0 6px; }
.session-item.active .session-dot { background: #2563eb; }
.session-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.session-delete {
  border: 0; background: transparent; color: #9ca3af; font-size: 16px;
  opacity: 0; padding: 0 2px; line-height: 1;
}
.session-item:hover .session-delete { opacity: 1; }
.session-delete:hover { color: #dc2626; }
.upload-area { border-top: 1px solid #f3f4f6; padding-top: 4px; }
.dropzone {
  border: 1.5px dashed #cbd5e1; border-radius: 10px; padding: 16px 10px;
  text-align: center; cursor: pointer; transition: all .2s;
}
.dropzone:hover, .dropzone.dragging { border-color: #2563eb; background: #eff6ff; }
.drop-icon { font-size: 22px; color: #60a5fa; line-height: 1; }
.drop-text { font-size: 12.5px; color: #4b5563; margin-top: 7px; }
.drop-formats { font-size: 10px; color: #9ca3af; margin-top: 5px; line-height: 1.5; word-break: break-all; }
.upload-msg { font-size: 11.5px; color: #059669; margin-top: 8px; padding: 0 4px; }
.sidebar-footer { font-size: 10px; color: #cbd5e1; text-align: center; margin-top: 14px; }
</style>
