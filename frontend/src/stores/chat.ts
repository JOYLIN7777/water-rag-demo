/** 问答会话状态：服务端为准，localStorage 只存会话索引做首屏缓存 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { chatApi } from '@/api'

export interface SessionItem { id: string; title: string; updatedAt?: number }
export interface MessageItem {
  role: 'user' | 'assistant'
  content: string
  sources?: unknown[]
  streaming?: boolean
}

const KEY = 'water-rag-sessions'

function loadLocal(): SessionItem[] {
  try { return JSON.parse(localStorage.getItem(KEY) || '[]') as SessionItem[] }
  catch { return [] }
}

export const useChatStore = defineStore('chat', () => {
  const sessions = ref<SessionItem[]>(loadLocal())
  const currentId = ref('')
  const messages = ref<MessageItem[]>([])
  const sending = ref(false)

  function saveLocal(): void {
    try { localStorage.setItem(KEY, JSON.stringify(sessions.value)) }
    catch { /* 隐私模式静默降级 */ }
  }

  function newSession(): string {
    const id = `session-${Date.now()}`
    sessions.value.unshift({ id, title: '' })
    saveLocal()
    currentId.value = id
    messages.value = []
    return id
  }

  async function syncSessions(): Promise<void> {
    try {
      const list = await chatApi.sessions()
      if (list?.length) {
        sessions.value = list
        saveLocal()
        if (!currentId.value) currentId.value = list[0].id
      }
    } catch { /* 服务端不可用时使用本地缓存 */ }
  }

  async function switchSession(id: string): Promise<void> {
    currentId.value = id
    sending.value = false
    try {
      const list = await chatApi.history(id)
      messages.value = (list || []).map(m => ({
        role: m.role === 'assistant' ? 'assistant' : 'user',
        content: m.content || '',
        sources: m.sources
      }))
    } catch { messages.value = [] }
  }

  function removeSession(id: string): void {
    sessions.value = sessions.value.filter(s => s.id !== id)
    saveLocal()
    if (currentId.value === id) {
      currentId.value = sessions.value[0]?.id || newSession()
      void switchSession(currentId.value)
    }
  }

  return { sessions, currentId, messages, sending, newSession, syncSessions, switchSession, removeSession, saveLocal }
})
