/** 会话状态：历史会话 + 当前消息，按账号隔离 */
import { create } from 'zustand'
import { chatApi } from '@/api'
import { scopedKey } from './auth'
import type { SessionItem, ChatMessage } from '@/types'

function loadLocal(): SessionItem[] {
  try { return JSON.parse(localStorage.getItem(scopedKey('sessions')) || '[]') as SessionItem[] }
  catch { return [] }
}

interface ChatState {
  sessions: SessionItem[]
  currentId: string
  messages: ChatMessage[]
  sending: boolean
  syncSessions: () => Promise<void>
  newSession: () => string
  switchSession: (id: string) => Promise<void>
  removeSession: (id: string) => void
  saveLocal: () => void
  reset: () => void
}

export const useChatStore = create<ChatState>((set, get) => ({
  sessions: loadLocal(),
  currentId: '',
  messages: [],
  sending: false,

  saveLocal: () => {
    try { localStorage.setItem(scopedKey('sessions'), JSON.stringify(get().sessions)) }
    catch { /* 隐私模式 */ }
  },

  syncSessions: async () => {
    try {
      const list = await chatApi.sessions()
      // 用服务端会话为准，合并本地未上送的新会话
      const local = get().sessions
      const ids = new Set(list.map(s => s.id))
      const merged = [...list, ...local.filter(s => !ids.has(s.id))]
      set({ sessions: merged })
      get().saveLocal()
    } catch { /* 服务端不可用用本地 */ }
  },

  newSession: () => {
    const id = `session-${Date.now()}`
    set(s => ({
      sessions: [{ id, title: '', updatedAt: Date.now() }, ...s.sessions],
      currentId: id,
      messages: []
    }))
    get().saveLocal()
    void chatApi.createSession(id).catch(() => {})
    return id
  },

  switchSession: async (id: string) => {
    set({ currentId: id, sending: false })
    try {
      const list = await chatApi.history(id)
      set({ messages: (list || []).map((m, i) => ({
        role: m.role === 'assistant' ? 'assistant' : 'user',
        content: m.content || '', sources: m.sources, id: `${id}-${i}`
      })) })
    } catch { set({ messages: [] }) }
  },

  removeSession: (id: string) => {
    set(s => {
      const sessions = s.sessions.filter(x => x.id !== id)
      const next = s.currentId === id ? (sessions[0]?.id || '') : s.currentId
      return { sessions, currentId: next, messages: s.currentId === id ? [] : s.messages }
    })
    get().saveLocal()
    void chatApi.clearSession(id).catch(() => {})
  },

  reset: () => set({ sessions: [], currentId: '', messages: [], sending: false })
}))
