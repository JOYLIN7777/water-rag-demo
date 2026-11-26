/** 问答会话状态：多会话消息隔离，对齐旧版 storeOf(id)/cacheMessages */
import { create } from 'zustand'
import { chatApi } from '@/api'
import { scopedKey } from './auth'
import type { SessionItem, ChatMessage } from '@/types'

export interface ChatMessageExt extends ChatMessage {
  nodeId?: string
  parentId?: string
  branch?: boolean
  branchSeal?: boolean
  /** 问答时间（毫秒时间戳）：后端 history 的 ts 字段，本地新发消息用 Date.now() */
  ts?: number
  usage?: Record<string, any> & { durationMs?: number }
}

function loadLocal(): SessionItem[] {
  try { return JSON.parse(localStorage.getItem(scopedKey('sessions')) || '[]') as SessionItem[] }
  catch { return [] }
}
function loadMessages(id: string): ChatMessageExt[] {
  try { return JSON.parse(localStorage.getItem(scopedKey('msgs:' + id)) || '[]') as ChatMessageExt[] }
  catch { return [] }
}

/** 后端 history 返回的是 ISO 纳秒字符串（2026-11-26T10:56:55.233589571），
 *  Number() 会变成 NaN、Date 也不认 9 位小数；统一截到毫秒再转时间戳。 */
function normalizeTs(v: unknown): number | undefined {
  if (v == null) return undefined
  if (typeof v === 'number') return Number.isFinite(v) ? v : undefined
  const s = String(v)
  const m = s.match(/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})\.(\d{1,9})(.*)$/)
  const iso = m ? `${m[1]}.${m[2].slice(0, 3)}${m[3]}` : s
  const t = Date.parse(iso)
  return isNaN(t) ? undefined : t
}

interface ChatState {
  sessions: SessionItem[]
  currentId: string
  messages: ChatMessageExt[]          // 当前会话消息（视图）
  bySession: Record<string, ChatMessageExt[]> // 多会话缓存
  busyIds: Set<string>
  /** 每个会话正在进行的流式请求；删除会话时必须先 abort，否则回调还在写已删除的会话 */
  aborters: Record<string, AbortController>
  setAborter: (id: string, ac: AbortController) => void
  removeAborter: (id: string) => void
  abortSession: (id: string) => void
  sending: boolean
  suggestions: string[]
  syncSessions: () => Promise<void>
  newSession: () => string
  switchSession: (id: string) => Promise<void>
  removeSession: (id: string) => Promise<void>
  touchSession: (id: string, title: string, fullQ: string) => void
  cacheMessages: (id: string, msgs: ChatMessageExt[]) => void
  loadSuggestions: () => Promise<void>
  saveLocal: () => void
  reset: () => void
}

export const useChatStore = create<ChatState>((set, get) => ({
  sessions: loadLocal(),
  currentId: '',
  messages: [],
  bySession: {},
  busyIds: new Set(),
  aborters: {},
  setAborter: (id, ac) => set(s => ({ aborters: { ...s.aborters, [id]: ac } })),
  removeAborter: (id) => set(s => { const a = { ...s.aborters }; delete a[id]; return { aborters: a } }),
  abortSession: (id) => {
    const ac = get().aborters[id]
    if (ac) ac.abort()
    get().removeAborter(id)
  },
  sending: false,
  suggestions: [],

  saveLocal: () => {
    try { localStorage.setItem(scopedKey('sessions'), JSON.stringify(get().sessions)) }
    catch { /* 隐私模式 */ }
  },

  cacheMessages: (id, msgs) => {
    // 当前会话必须同步更新 messages 视图，否则流式回答要切一次会话才显示
    set(s => ({
      bySession: { ...s.bySession, [id]: msgs },
      messages: s.currentId === id ? [...msgs] : s.messages
    }))
    try { localStorage.setItem(scopedKey('msgs:' + id), JSON.stringify(msgs)) }
    catch { /* 同上 */ }
  },

  syncSessions: async () => {
    try {
      const list = await chatApi.sessions()
      // 服务端清单就是唯一权威：它已经按 user_id 过滤。
      // 之前把首次挂载时读到的本地缓存并进来，切账号后就会把上一个账号的会话带过来。
      if (Array.isArray(list)) {
        set({ sessions: list })
        get().saveLocal()
      }
    } catch { /* 用本地缓存 */ }
  },

  newSession: () => {
    // 空会话只作为"当前编辑态"，不进历史列表、不落后端——与旧版一致：提问后才生成会话
    const id = `session-${Date.now()}`
    set({ currentId: id, messages: [] })
    return id
  },

  switchSession: async (id: string) => {
    set({ currentId: id, messages: get().bySession[id] || loadMessages(id) })
    try {
      const list = await chatApi.history(id)
      // 回答还没落库时切走再切回：后端 history 为空，别用它盖掉本地正在生成的消息
      if ((!list || list.length === 0) && (get().bySession[id] || []).length > 0) return
      const msgs: ChatMessageExt[] = (list || []).map((m, i) => ({
        role: m.role === 'assistant' ? 'assistant' : 'user',
        content: m.content || '', sources: m.sources,
        usage: (m as any).usage || undefined,
        nodeId: (m as any).id, parentId: (m as any).parentId,
        ts: normalizeTs((m as any).ts),
        id: `${id}-${i}`
      }))
      set(s => ({ messages: msgs, bySession: { ...s.bySession, [id]: msgs } }))
      get().cacheMessages(id, msgs)
    } catch { /* 用缓存 */ }
  },

  removeSession: async (id: string) => {
    let nextId = ''
    set(s => {
      const sessions = s.sessions.filter(x => x.id !== id)
      const wasCurrent = s.currentId === id
      nextId = wasCurrent ? (sessions[0]?.id || '') : s.currentId
      const bySession = { ...s.bySession }; delete bySession[id]
      // 删掉当前会话必须把视图切到新会话；之前写成 next === s.currentId，永远不成立
      return { sessions, currentId: nextId, messages: wasCurrent ? (bySession[nextId] || []) : s.messages, bySession }
    })
    get().saveLocal()
    try { localStorage.removeItem(scopedKey('msgs:' + id)) } catch { /* 隐私模式 */ }
    void chatApi.clearSession(id).catch(() => {})
    // 消息区也要跟着跳过去：loadMessages 只读本地缓存，没缓存过就是欢迎页。
    // 调 switchSession 从服务端把下一会话的历史拉回来。
    if (nextId) await get().switchSession(nextId)
    else set({ messages: [] })
  },

  touchSession: (id, title, fullQ) => {
    set(s => {
      const exists = s.sessions.some(x => x.id === id)
      const sessions = exists
        ? s.sessions.map(x => x.id === id && !x.title ? { ...x, title, updatedAt: Date.now() } : x)
        : [{ id, title, updatedAt: Date.now() }, ...s.sessions]
      return { sessions }
    })
    get().saveLocal()
    void chatApi.createSession(id, title).catch(() => {})
  },

  loadSuggestions: async () => {
    const defaults = ['云溪水库的汛限水位是多少？', '闸门开启有哪些操作原则？']
    try {
      const list = await chatApi.suggestions()
      set({ suggestions: Array.isArray(list) && list.length ? list.slice(0, 4) : defaults })
    } catch { set({ suggestions: defaults }) }
  },

  reset: () => {
    set({ sessions: [], currentId: '', messages: [], bySession: {}, busyIds: new Set(), aborters: {}, sending: false })
  }
}))
