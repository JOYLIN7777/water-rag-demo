<script setup>
import { ref, computed, nextTick, onMounted } from 'vue'
import Sidebar from './components/Sidebar.vue'
import MessageBubble from './components/MessageBubble.vue'
import { streamChat, fetchHistory, clearHistory, callWaterTool } from './api.js'

// ===== 会话状态 =====
const sessions = ref([])
const currentId = ref('')
const messages = ref([])          // {role, content, sources, streaming}
const question = ref('')
const sending = ref(false)
const error = ref('')
const scrollArea = ref(null)
const inputBox = ref(null)

const hasMessages = computed(() => messages.value.length > 0)
const quickQuestions = [
  '云溪水库的汛限水位是多少？',
  '汛期入库流量超过2000时怎么调度？',
  '云溪水库现在的实时水位是多少？',
  '闸门开启有哪些操作原则？'
]

// 会话列表持久化在 localStorage（后端历史在 Redis，这里只存会话索引）
onMounted(() => {
  try {
    const saved = JSON.parse(localStorage.getItem('water-rag-sessions') || '[]')
    if (saved.length) {
      sessions.value = saved
      currentId.value = saved[0].id
      restoreHistory(saved[0].id)
    } else {
      createSession()
    }
  } catch {
    createSession()
  }
})

function saveSessions() {
  localStorage.setItem('water-rag-sessions', JSON.stringify(sessions.value))
}

function createSession() {
  const id = `session-${Date.now()}`
  sessions.value.unshift({ id, title: '' })
  saveSessions()
  switchSession(id)
}

async function switchSession(id) {
  currentId.value = id
  messages.value = []
  error.value = ''
  await restoreHistory(id)
}

function removeSession(id) {
  sessions.value = sessions.value.filter(s => s.id !== id)
  saveSessions()
  if (!sessions.value.length) createSession()
  else if (currentId.value === id) switchSession(sessions.value[0].id)
}

async function restoreHistory(id) {
  try {
    const list = await fetchHistory(id)
    messages.value = list.map(m => ({ role: m.role, content: m.content, sources: [], streaming: false }))
    await scrollToBottom()
  } catch { /* 后端未启动时静默降级为空会话 */ }
}

async function clearCurrentHistory() {
  try {
    await clearHistory(currentId.value)
    messages.value = []
    error.value = ''
  } catch (e) {
    error.value = e.message
  }
}

// ===== 问答主流程 =====
async function send(text) {
  const q = (text ?? question.value).trim()
  if (!q || sending.value) return
  question.value = ''
  error.value = ''
  sending.value = true

  // 会话标题取第一条问题前 18 字
  const session = sessions.value.find(s => s.id === currentId.value)
  if (session && !session.title) {
    session.title = q.length > 18 ? q.slice(0, 18) + '…' : q
    saveSessions()
  }

  messages.value.push({ role: 'user', content: q, sources: [], streaming: false })
  const aiMessage = { role: 'assistant', content: '', sources: [], streaming: true }
  messages.value.push(aiMessage)
  await scrollToBottom()

  try {
    await streamChat(currentId.value, q, (event, data) => {
      if (event === 'sources') {
        try { aiMessage.sources = JSON.parse(data) } catch { /* 忽略异常数据 */ }
      } else if (event === 'message') {
        aiMessage.content += data
        scrollToBottom()
      }
      // done 事件无需处理，流结束即完成
    })
  } catch (e) {
    error.value = e.message
    if (!aiMessage.content) aiMessage.content = '（请求失败，请检查后端服务与 API Key 配置）'
  } finally {
    aiMessage.streaming = false
    sending.value = false
    await scrollToBottom()
  }
}

function onKeydown(e) {
  // Enter 发送，Shift+Enter 换行，中文输入法组合期间不触发
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    send()
  }
}

async function scrollToBottom() {
  await nextTick()
  if (scrollArea.value) scrollArea.value.scrollTop = scrollArea.value.scrollHeight
}

// 工具直调演示
async function testTool() {
  try {
    const data = await callWaterTool('云溪水库')
    question.value = `工具直调结果：${data}`
  } catch (e) {
    error.value = e.message
  }
}
</script>

<template>
  <div class="layout">
    <Sidebar
      :sessions="sessions" :current-id="currentId"
      @new="createSession" @select="switchSession" @remove="removeSession"
    />

    <main class="main">
      <header class="topbar">
        <div class="topbar-title">知识库问答</div>
        <div class="topbar-actions">
          <button class="text-btn" @click="testTool" title="调用实时水情 mock 工具">工具演示</button>
          <button class="text-btn" @click="clearCurrentHistory" :disabled="!hasMessages">清空历史</button>
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
          <MessageBubble
            v-for="(m, i) in messages" :key="i"
            :role="m.role" :content="m.content" :sources="m.sources" :streaming="m.streaming"
          />
        </div>
      </div>

      <div class="composer-wrap">
        <div v-if="error" class="error-bar">{{ error }}</div>
        <div class="composer">
          <textarea
            ref="inputBox" v-model="question" rows="1"
            placeholder="输入你的问题，Enter 发送，Shift+Enter 换行"
            :disabled="sending" @keydown="onKeydown"
          ></textarea>
          <button class="send-btn" :disabled="sending || !question.trim()" @click="send()">
            {{ sending ? '生成中…' : '发送' }}
          </button>
        </div>
        <div class="composer-tip">回答基于知识库生成，关键数据请以正式规程为准</div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.layout { display: flex; height: 100%; }
.main { flex: 1; display: flex; flex-direction: column; min-width: 0; background: #f9fafb; }
.topbar {
  height: 58px; flex: 0 0 58px; background: #fff; border-bottom: 1px solid #e5e7eb;
  display: flex; align-items: center; justify-content: space-between; padding: 0 24px;
}
.topbar-title { font-size: 15px; font-weight: 600; color: #111827; }
.topbar-actions { display: flex; gap: 8px; }
.text-btn {
  border: 1px solid #e5e7eb; background: #fff; color: #4b5563;
  padding: 6px 12px; border-radius: 7px; font-size: 12px;
}
.text-btn:hover:not(:disabled) { border-color: #93c5fd; color: #2563eb; background: #f8fbff; }
.scroll-area { flex: 1; overflow-y: auto; padding: 28px 6%; }
.welcome { max-width: 720px; margin: 12vh auto 0; text-align: center; }
.welcome-mark {
  width: 56px; height: 56px; border-radius: 14px; background: #2563eb; color: #fff;
  font-size: 24px; font-weight: 600; display: flex; align-items: center;
  justify-content: center; margin: 0 auto 18px;
}
.welcome h1 { font-size: 21px; color: #111827; margin: 0 0 10px; font-weight: 600; }
.welcome p { font-size: 13.5px; color: #6b7280; line-height: 1.7; margin: 0 0 28px; }
.quick-list { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.quick-item {
  border: 1px solid #e5e7eb; background: #fff; border-radius: 10px;
  padding: 13px 14px; font-size: 13px; color: #374151; text-align: left;
  box-shadow: 0 1px 2px rgba(0,0,0,.03);
}
.quick-item:hover { border-color: #93c5fd; background: #f8fbff; color: #1d4ed8; }
.messages { max-width: 860px; margin: 0 auto; }
.composer-wrap { flex: 0 0 auto; padding: 10px 6% 16px; background: linear-gradient(transparent, #f9fafb 18%); }
.error-bar {
  max-width: 860px; margin: 0 auto 8px; padding: 8px 12px; border-radius: 7px;
  background: #fef2f2; color: #b91c1c; font-size: 12.5px; border: 1px solid #fecaca;
}
.composer {
  max-width: 860px; margin: 0 auto; display: flex; gap: 10px; align-items: flex-end;
  background: #fff; border: 1px solid #d1d5db; border-radius: 12px;
  padding: 10px 10px 10px 14px; box-shadow: 0 2px 8px rgba(0,0,0,.05);
}
.composer:focus-within { border-color: #93c5fd; box-shadow: 0 0 0 3px #dbeafe; }
.composer textarea {
  flex: 1; resize: none; border: 0; outline: 0; font-size: 14px;
  line-height: 1.6; max-height: 160px; min-height: 24px; color: #1f2937;
  background: transparent;
}
.send-btn {
  border: 0; background: #2563eb; color: #fff; padding: 9px 18px;
  border-radius: 8px; font-size: 13px; font-weight: 500;
}
.send-btn:hover:not(:disabled) { background: #1d4ed8; }
.composer-tip { text-align: center; font-size: 11px; color: #9ca3af; margin-top: 8px; }
</style>
