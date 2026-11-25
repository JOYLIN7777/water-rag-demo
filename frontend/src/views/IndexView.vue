<script setup lang="ts">
import { ref, computed, nextTick, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { useChatStore } from '@/stores/chat'
import { useAuthStore } from '@/stores/auth'
import { chatApi, docApi, streamChat, uploadDocument } from '@/api'
import type { StreamEvent } from '@/api'

const chat = useChatStore()
const auth = useAuthStore()
const fileInput = ref<HTMLInputElement | null>(null)
const question = ref('')
const error = ref('')
const scrollArea = ref<HTMLElement | null>(null)
const uploadVisible = ref(false)
const uploadIncludeSubtree = ref(false)
const uploadProgress = ref(0)
const uploading = ref(false)

const hasMessages = computed(() => chat.messages.length > 0)
const quickQuestions = [
  '云溪水库的汛限水位是多少？',
  '闸门开启有哪些操作原则？',
  '汛期入库流量超过2000时怎么调度？',
  '云溪水库现在的实时水位是多少？'
]

function renderMarkdown(text: string): string {
  return DOMPurify.sanitize(marked.parse(text || '', { breaks: true }) as string)
}

async function scrollToBottom() {
  await nextTick()
  if (scrollArea.value) scrollArea.value.scrollTop = scrollArea.value.scrollHeight
}

async function init() {
  await chat.syncSessions()
  if (!chat.currentId && chat.sessions.length) chat.currentId = chat.sessions[0].id
  if (!chat.currentId) chat.currentId = chat.newSession()
  if (chat.currentId) await chat.switchSession(chat.currentId)
  await scrollToBottom()
}
onMounted(init)

async function send(text?: string) {
  const q = (text ?? question.value).trim()
  if (!q || chat.sending) return
  question.value = ''
  error.value = ''
  chat.sending = true

  const s = chat.sessions.find(x => x.id === chat.currentId)
  if (s && !s.title) {
    s.title = q.length > 18 ? q.slice(0, 18) + '…' : q
    chat.saveLocal()
  }
  chat.messages.push({ role: 'user', content: q })
  const ai = { role: 'assistant' as const, content: '', sources: [] as unknown[], streaming: true }
  chat.messages.push(ai)
  await scrollToBottom()

  try {
    let buf = ''
    await streamChat(chat.currentId, q, (event: string, data: string) => {
      if (event === 'sources') {
        try { ai.sources = JSON.parse(data) } catch { /* 忽略坏数据 */ }
      } else if (event === 'message') {
        ai.content += data
        buf += data
        if (buf.length > 12) { void scrollToBottom(); buf = '' }
      }
    })
  } catch (e: any) {
    error.value = e.message || '请求失败'
    if (!ai.content) ai.content = '（请求失败，请检查服务与配置）'
  } finally {
    ai.streaming = false
    chat.sending = false
    await scrollToBottom()
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !(e as any).isComposing) {
    e.preventDefault()
    void send()
  }
}

async function clearHistory() {
  if (!chat.currentId) return
  try {
    await chatApi.clearHistory(chat.currentId)
    chat.messages = []
    error.value = ''
  } catch (e: any) { error.value = e.message }
}

async function doUpload() {
  const input = fileInput.value
  const files = Array.from(input?.files || [])
  if (!files.length) return
  uploading.value = true
  uploadProgress.value = 0
  let ok = 0
  for (const f of files) {
    try {
      await uploadDocument(f, p => { uploadProgress.value = p }, uploadIncludeSubtree.value)
      ok++
    } catch (e: any) { error.value = e.message }
  }
  uploading.value = false
  uploadVisible.value = false
  if (input) input.value = ''
  ElMessage.success(ok ? `已入库 ${ok} 个文档，现在可以提问了` : '没有文档入库')
}
</script>

<template>
  <div class="chat-page">
    <header class="topbar">
      <div class="topbar-left">
        <div class="topbar-title">知识库问答</div>
        <div class="topbar-sub">基于知识库内容提问，回答会标注引用原文</div>
      </div>
      <div style="display:flex;gap:8px">
        <el-button size="small" @click="clearHistory" :disabled="!hasMessages">清空历史</el-button>
      </div>
    </header>

    <div ref="scrollArea" class="scroll-area">
      <div v-if="!hasMessages" class="welcome">
        <div class="welcome-mark">水</div>
        <h1>水利知识库智能问答</h1>
        <p>上传规程、报告或表格文档后，基于知识库内容提问。回答下方会标注引用的原文片段。</p>
        <div class="quick-list">
          <button v-for="q in quickQuestions" :key="q" class="quick-item" @click="send(q)">{{ q }}</button>
        </div>
      </div>
      <div v-else class="messages">
        <div v-for="(m, i) in chat.messages" :key="i" class="msg" :class="m.role">
          <template v-if="m.role === 'user'"><div class="bubble user">{{ m.content }}</div></template>
          <template v-else>
            <div class="bubble ai" v-html="renderMarkdown(m.content)"></div>
            <span v-if="m.streaming" class="cursor">▌</span>
            <div v-if="m.sources?.length" class="sources">
              <span v-for="(s, k) in m.sources as any[]" :key="k" class="source-chip">{{ s.fileName || s.file || '引用' }} · 第{{ (s.chunkIndex ?? 0) + 1 }}段</span>
            </div>
          </template>
        </div>
      </div>
    </div>

    <div v-if="error" class="error-tip">{{ error }}</div>
    <div class="composer">
      <el-input v-model="question" type="textarea" :rows="2" placeholder="输入你的问题，Enter 发送，Shift+Enter 换行"
        resize="none" @keydown="onKeydown" />
      <div class="composer-bar">
        <el-button v-if="auth.has('doc:write_chat')" text size="small" @click="uploadVisible = true">⇪ 上传文档</el-button>
        <el-button type="primary" :loading="chat.sending" @click="send()">发送 ↵</el-button>
      </div>
    </div>

    <el-dialog v-model="uploadVisible" title="上传文档" width="460px">
      <input ref="fileInput" id="vueFileInput" type="file" multiple hidden />
      <el-button @click="fileInput?.click()">选择文件</el-button>
      <el-checkbox v-model="uploadIncludeSubtree" style="display:block;margin-top:12px">含下级部门可见</el-checkbox>
      <div v-if="uploading" style="margin-top:12px"><el-progress :percentage="uploadProgress" /></div>
      <template #footer><el-button @click="uploadVisible = false">取消</el-button><el-button type="primary" :loading="uploading" @click="doUpload">确认上传</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.chat-page { display: flex; flex-direction: column; flex: 1; min-height: 0; height: 100%; padding: 0; overflow: hidden; }
.chat-page .topbar { flex: 0 0 auto; }
.scroll-area { flex: 1 1 auto; min-height: 0; overflow-y: auto; padding: 26px max(26px, calc((100% - 860px) / 2)); }
.composer { flex: 0 0 auto; padding: 12px max(26px, calc((100% - 860px) / 2)) 18px; background: #fff; border-top: 1px solid var(--line); box-shadow: 0 -2px 12px rgba(16,28,54,.04); }
.welcome { max-width: 640px; margin: 8vh auto 0; text-align: center; }
.welcome-mark { width: 58px; height: 58px; border-radius: 16px; margin: 0 auto 18px; background: linear-gradient(135deg, var(--primary-hi), var(--primary)); color: #fff; display: flex; align-items: center; justify-content: center; font-size: 26px; font-weight: 700; }
.welcome h1 { font-size: 24px; margin: 0 0 8px; }
.welcome p { color: var(--muted); font-size: 13px; }
.quick-list { display: flex; flex-wrap: wrap; gap: 8px; justify-content: center; margin-top: 24px; }
.quick-item { border: 1px solid var(--line); background: #fff; border-radius: 18px; padding: 7px 14px; font-size: 12px; cursor: pointer; color: var(--ink-2); }
.quick-item:hover { border-color: var(--primary); color: var(--primary); }
.messages { max-width: 860px; margin: 0 auto; }
.msg { margin-bottom: 18px; }
.bubble { padding: 12px 16px; border-radius: 12px; line-height: 1.7; }
.bubble.user { background: var(--primary); color: #fff; margin-left: auto; max-width: 76%; }
.bubble.ai { background: #fff; border: 1px solid var(--line); max-width: 92%; }
.cursor { color: var(--primary); animation: blink 1s infinite; }
@keyframes blink { 50% { opacity: 0 } }
.sources { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.source-chip { font-size: 10.5px; background: var(--primary-soft); color: #25469b; padding: 2px 8px; border-radius: 10px; }
.error-tip { color: var(--danger); font-size: 12px; padding: 0 26px 8px; }
.composer-bar { display: flex; justify-content: space-between; align-items: center; margin-top: 8px; }
</style>
