<script setup lang="ts">
import { ref, computed, nextTick, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { useChatStore } from '@/stores/chat'
import { useAuthStore } from '@/stores/auth'
import { chatApi, docApi, streamChat, uploadDocument } from '@/api'
import type { StreamEvent } from '@/api'
import '@/styles/chat.css'

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
        <div v-for="(m, i) in chat.messages" :key="i" class="msg-group" :class="m.role">
          <template v-if="m.role === 'user'">
            <div class="msg-user">{{ m.content }}</div>
          </template>
          <template v-else>
            <div class="ai-card">
              <div class="ai-card-head">
                <span class="ai-card-mark">◈</span><span class="ai-card-name">知识库问答</span>
              </div>
              <div class="ai-card-body" v-html="renderMarkdown(m.content)"></div>
              <div v-if="m.sources?.length" class="cite-zone">
                <div class="cite-row">
                  <span v-for="(s, k) in m.sources as any[]" :key="k" class="cite-chip">
                    <span class="cite-num">{{ k + 1 }}</span>
                    <span class="cite-file">{{ s.fileName || s.file || '引用' }}</span>
                  </span>
                </div>
              </div>
            </div>
          </template>
        </div>
      </div>
    </div>

    <div class="composer-wrap">
      <div v-if="error" class="error-bar show">{{ error }}</div>
      <div class="composer">
        <textarea v-model="question" rows="1" placeholder="输入你的问题，Enter 发送，Shift+Enter 换行"
          @keydown="onKeydown"></textarea>
        <div class="composer-bar">
          <span class="composer-tool" title="基于知识库检索作答"><span style="color:var(--primary)">◈</span> 知识库问答</span>
          <button v-if="auth.has('doc:write_chat')" class="composer-tool" type="button"
            @click="uploadVisible = true" title="上传文档到知识库">⇪ 上传文档</button>
          <button class="send-btn" type="button" @click="send()">发送 <span style="opacity:.75">↵</span></button>
        </div>
      </div>
      <div class="composer-tip">回答基于知识库生成，关键数据请以正式规程为准</div>
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
/* 视觉样式全部来自旧版 chat.css；这里只做 SPA 视口高度适配 */
.chat-page { display: flex; flex-direction: column; flex: 1; min-height: 0; height: 100%; overflow: hidden; }
.chat-page .topbar { flex: 0 0 auto; }
.chat-page .scroll-area { flex: 1 1 auto; min-height: 0; }
.chat-page .composer-wrap { flex: 0 0 auto; }
</style>
