/** 问答页：DOM/class 与旧版 index.html 1:1，流式/引用/分支/草稿隔离全功能 */
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { message, Modal, Select } from 'antd'
import { useSearchParams } from 'react-router-dom'
import { useChatStore, type ChatMessageExt } from '@/store/chat'
import { useAuthStore } from '@/store/auth'
import { tsLocal } from '@/lib/ui'
import { http } from '@/api/http'
import WbModal from '@/components/WbModal'
import { streamChat, uploadDocument } from '@/api'
import AdminEntry from '@/components/AdminEntry'
import '@/styles/chat.css'

interface CiteGroup { name: string; segs: Array<{ fileName?: string; chunkIndex?: number; preview?: string }> }

/** 模型偶尔输出「###一、标题」「####1.小节」这类标题后不空格的 Markdown，
 *  marked 会当普通文本原样渲染。这里只规范化行首标题，别动正文里的 #。 */
function normalizeMarkdown(text: string): string {
  return String(text || '').replace(/^([ \t]*)(#{1,6})[ \t\u3000]*(?=\S)/gm, '$1$2 ')
}

/**
 * 模型常把表格输出成空格对齐的纯文本（"序号  特征水位  相应水位/米"），marked 认不出来。
 * 这里把连续多行、列数一致的对齐文本补成标准 Markdown 表格，再交给 marked 渲染。
 */
function normalizeTables(text: string): string {
  const lines = String(text || '').split('\n')
  const isRow = (s: string) => {
    const t = s.trim()
    if (!t || t.length > 200) return false
    const parts = t.split(/\s{2,}/)
    return parts.length >= 2 && parts.every(p => p.length > 0 && p.length < 40 && !/[。；，：]$/.test(p))
  }
  const out: string[] = []
  let i = 0
  while (i < lines.length) {
    if (isRow(lines[i])) {
      const rows: string[][] = []
      while (i < lines.length && isRow(lines[i])) { rows.push(lines[i].trim().split(/\s{2,}/).map(s => s.trim())); i++ }
      const cols = rows[0].length
      if (rows.length >= 2 && rows.every(r => r.length === cols)) {
        out.push('| ' + rows[0].join(' | ') + ' |')
        out.push('| ' + Array(cols).fill('---').join(' | ') + ' |')
        rows.slice(1).forEach(r => out.push('| ' + r.join(' | ') + ' |'))
      } else out.push(...rows.map(r => r.join('  ')))
      continue
    }
    out.push(lines[i]); i++
  }
  return out.join('\n')
}

/**
 * 按回复内容动态识别「示例问题」：列表项里的问句、以及正文里以 -/序号开头的短问句，
 * 都标记为蓝色可点击；点一下把问题填进输入框。不是写死匹配某几种句式。
 */
/** 只认「独立成行、以问号结尾的短问句」；说明文字/网址/引用片段一律不算推荐问题 */
function isRealQuestion(txt: string): boolean {
  const s = String(txt || '').trim()
  if (s.length < 4 || s.length > 60) return false
  if (!/[？?]$/.test(s)) return false
  if (/https?:\/\/|www\.|片段\d|【片段|\([^)]{0,40}\)/.test(s)) return false
  // 带多个逗号/顿号的句子是说明文字，不是提问
  if ((s.match(/[，,]/g) || []).length >= 3) return false
  return true
}

function markSuggestions(html: string): string {
  let out = html.replace(/<li>([\s\S]*?)<\/li>/g, (m, inner) => {
    const txt = String(inner).replace(/<[^>]+>/g, '').trim()
    return isRealQuestion(txt) ? `<li class="ask-suggest">${inner}</li>` : m
  })
  out = out.replace(/<p>([\s\S]*?)<\/p>/g, (m, inner) => {
    const parts = String(inner).split(/(<br\s*\/?>)/i)
    const marked = parts.map(part => {
      if (/^<br/i.test(part)) return part
      const txt = part.replace(/<[^>]+>/g, '').trim()
      const clean = txt.replace(/^[-–—•*·\d一二三四五六七八九十、.\s]+/, '')
      if (!isRealQuestion(clean)) return part
      return `<span class="ask-suggest" data-q="${clean.replace(/"/g, '&quot;')}">${part}</span>`
    })
    return '<p>' + marked.join('') + '</p>'
  })
  return out
}

function renderMarkdown(text: string): string {
  const html = marked.parse(normalizeTables(normalizeMarkdown(text)), { breaks: true }) as string
  return DOMPurify.sanitize(markSuggestions(html))
}

/** 问答时间统一显示完整年月日时分秒：提问在气泡上方、回答在卡片头部，层级分明 */
function fullTime(ts?: number): string {
  if (!ts) return '—'
  const d = new Date(ts)
  if (isNaN(d.getTime())) return tsLocal(ts)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** 千分位：与旧版 fmtInt 一致 */
function fmtInt(n?: number): string {
  return Number(n || 0).toLocaleString('en-US')
}

/** 本次调用的消耗：主显示"合计 token + 耗时"，明细放 title 悬浮提示（对齐旧版 usageInner） */
function usageInner(u: Record<string, any>) {
  const sec = (u.durationMs || 0) / 1000
  const d = sec >= 10 ? Math.round(sec) + 's' : sec.toFixed(1) + 's'
  const total = u.totalTokens ?? (Number(u.promptTokens || 0) + Number(u.completionTokens || 0))
  return <>本次消耗<b>{fmtInt(total)}</b> tokens，耗时{d}</>
}

function usageTip(u: Record<string, any>): string {
  const sec = (u.durationMs || 0) / 1000
  return '本次调用消耗：输入 ' + fmtInt(u.promptTokens) + ' tokens / 输出 ' + fmtInt(u.completionTokens)
    + ' tokens，合计 ' + fmtInt(u.totalTokens ?? (Number(u.promptTokens || 0) + Number(u.completionTokens || 0)))
    + ' tokens · 模型 ' + (u.model || '-') + ' · 耗时 ' + sec.toFixed(2) + ' 秒'
}

function groupCites(src: any[]): CiteGroup[] {
  const map = new Map<string, CiteGroup['segs']>()
  for (const s of src || []) {
    const name = s.fileName || '(未知文档)'
    if (!map.has(name)) map.set(name, [])
    map.get(name)!.push(s)
  }
  return Array.from(map, ([name, segs]) => ({ name, segs }))
}

export default function IndexPage() {
  const chat = useChatStore()
  const auth = useAuthStore()
  const [params] = useSearchParams()
  const scrollRef = useRef<HTMLDivElement>(null)
  const [q, setQ] = useState('')
  const [error, setError] = useState('')
  const [openCites, setOpenCites] = useState<Record<string, boolean>>({})
  const [uploadMsg, setUploadMsg] = useState<{ type: string; text: string } | null>(null)
  const [chunkDetail, setChunkDetail] = useState<{ fileName: string; index: number; content: string } | null>(null)
  /** 当前点开的引用片段：回答结束后也能看到选中态 */
  const [activeCite, setActiveCite] = useState<string>('')
  /** 多个可用 CHAT 模型时可按次选择（类似 WorkBuddy），选择保存在本机 */
  const [chatModels, setChatModels] = useState<Array<{ id: number; name: string; model: string; active?: boolean }>>([])
  const [modelId, setModelId] = useState<number | null>(() => {
    const v = Number(localStorage.getItem('wrk_chat_model'))
    return Number.isFinite(v) && v > 0 ? v : null
  })

  /** 点引用片段：按文件名+序号取该段原文，弹框展示完整内容 */
  async function showChunkDetail(fileName: string, index: number) {
    setChunkDetail({ fileName, index, content: '正在读取原文…' })
    try {
      const j = await http.get<{ chunks?: Array<{ chunkIndex: number; content: string }> }>(
        '/api/docs/chunks?fileName=' + encodeURIComponent(fileName))
      const c = (j?.chunks || []).find(x => Number(x.chunkIndex) === Number(index))
      setChunkDetail({ fileName, index, content: c?.content || '未找到该片段的原文' })
    } catch (e: any) {
      setChunkDetail({ fileName, index, content: '读取失败：' + (e?.message || '') })
    }
  }
  const fileInput = useRef<HTMLInputElement>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const [force, setForce] = useState(0)   // 触发重渲染（流式增量）
  const [initializing, setInitializing] = useState(true)  // 刷新时先不渲染欢迎页，避免闪一下

  function scrollBottom(forceScroll = false) {
    if (localStorage.getItem('wrk_pref_autoscroll') === '0') return
    requestAnimationFrame(() => {
      const el = scrollRef.current
      if (el) el.scrollTop = el.scrollHeight
      void forceScroll
    })
  }

  useEffect(() => {
    void (async () => {
      await chat.syncSessions()
      await chat.loadSuggestions()
      void http.get<Array<{ id: number; name: string; model: string; type: string; enabled?: boolean; active?: boolean }>>('/api/models')
        .then(list => setChatModels((Array.isArray(list) ? list : [])
          .filter(m => m.type === 'CHAT' && m.active === true && m.enabled !== false)
          .map(m => ({ id: m.id, name: m.name, model: m.model, active: m.active }))))
        .catch(() => setChatModels([]))
      // 读最新 store：挂载到 syncSessions 返回期间用户可能已经提问，
      // 这时候再用旧闭包里的 currentId/会话列表去 switchSession 会把新消息冲掉。
      const state = useChatStore.getState()
      const sid = params.get('sid')
      if (sid && state.sessions.some(s => s.id === sid)) await chat.switchSession(sid)
      else if (params.get('new') === '1') await chat.switchSession(chat.newSession())
      else if (state.currentId) {
        const cached = state.bySession[state.currentId]
        // 已经写入本地消息说明用户正在这个会话提问，不能被历史覆盖
        if (!cached || cached.length === 0) await chat.switchSession(state.currentId)
      }
      else if (state.sessions.length) await chat.switchSession(state.sessions[0].id)
      else await chat.switchSession(chat.newSession())
      // 刷新/切会话后定位到最新一条消息，而不是停在最初提问的位置
      setTimeout(() => scrollBottom(true), 150)
      setInitializing(false)
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 草稿按会话隔离：切会话时存旧草稿、载新草稿
  /** 防同一问题在 1.5 秒内被 Enter/点击重复触发，产生多条同名会话 */
  const lastSendRef = useRef<{ q: string; at: number } | null>(null)
  /** 回答中又输入的问题排队（支持多条），等本轮结束后按顺序自动提交 */
  const queuedRef = useRef<string[]>([])
  const [queuedList, setQueuedList] = useState<string[]>([])
  function enqueue(text: string) {
    queuedRef.current = [...queuedRef.current, text]
    setQueuedList([...queuedRef.current])
  }
  function dequeueAt(idx: number) {
    queuedRef.current = queuedRef.current.filter((_, i) => i !== idx)
    setQueuedList([...queuedRef.current])
  }
  const prevSid = useRef('')
  useEffect(() => {
    const old = prevSid.current
    if (old && old !== chat.currentId) {
      try { localStorage.setItem(`draft:${old}`, q) } catch { /* 隐私模式 */ }
    }
    if (chat.currentId !== old) {
      let next = ''
      try { next = localStorage.getItem(`draft:${chat.currentId}`) || '' } catch { /* 同上 */ }
      setQ(next)
      prevSid.current = chat.currentId
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chat.currentId])

  // 草稿载入 / 会话切换后立刻按内容定高，避免首屏先渲染 1 行再变高导致按钮跳动
  useLayoutEffect(() => {
    const el = inputRef.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = Math.min(200, Math.max(38, el.scrollHeight)) + 'px'
  }, [q, chat.currentId])

  async function send(text?: string) {
    const query = (text ?? q).trim()
    // 页面刚挂载、初始化还没设 currentId 时先建空会话，避免消息挂到空 key 上被后续初始化冲掉
    const ownerId = chat.currentId || chat.newSession()
    if (!query || chat.busyIds.has(ownerId)) return
    const now = Date.now()
    if (lastSendRef.current && lastSendRef.current.q === query && now - lastSendRef.current.at < 1500) return
    lastSendRef.current = { q: query, at: now }
    setQ('')
    if (inputRef.current) inputRef.current.style.height = '38px' 
    setError('')
    try { localStorage.removeItem(`draft:${ownerId}`) } catch { /* 同上 */ }

    chat.touchSession(ownerId, query.length > 18 ? query.slice(0, 18) + '…' : query, query)
    const ac = new AbortController()
    chat.setAborter(ownerId, ac)
    setBusy(ownerId, true)

    const store = chat.bySession[ownerId] || chat.messages
    const at = Date.now()
    const um: ChatMessageExt = { role: 'user', content: query, sources: [], ts: at }
    const ai: ChatMessageExt = { role: 'assistant', content: '', sources: [], streaming: true, ts: at }
    const next = [...store, um, ai]
    chat.cacheMessages(ownerId, next)
    if (chat.currentId === ownerId) { setForce(x => x + 1); scrollBottom(true) }

    let finished = false
    try {
      // modelId 必须放进请求体：后端 @RequestBody ChatRequest 从 body 读，放 URL query 会被忽略
      const r = await fetch('/api/chat/stream', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: ownerId, question: query, modelId: modelId || undefined }), signal: ac.signal
      })
      if (!r.ok) throw new Error('请求失败（HTTP ' + r.status + '）')
      const reader = r.body!.getReader()
      const dec = new TextDecoder()
      let buf = ''
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buf = (buf + dec.decode(value, { stream: true })).replace(/\r\n/g, '\n')
        let p: number
        while ((p = buf.indexOf('\n\n')) !== -1) {
          const block = buf.slice(0, p); buf = buf.slice(p + 2)
          let ev = 'message'; const dataLines: string[] = []
          for (const line of block.split('\n')) {
            if (line.startsWith('event:')) ev = line.slice(6).trim()
            else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
          }
          const data = dataLines.join('\n')
          if (ev === 'sources') { try { ai.sources = JSON.parse(data) } catch { /* 忽略 */ } }
          else if (ev === 'error') { ai.content = data; setError(data) }
          else if (ev === 'message') ai.content += data
          else if (ev === 'done') {
            finished = true
            if (data.startsWith('{')) {
              try {
                const d = JSON.parse(data)
                if (d?.usage) ai.usage = { durationMs: d.durationMs || 0, ...d.usage }
                if (d?.nodes && next.length >= 2) {
                  next[next.length - 2].nodeId = d.nodes.questionId
                  next[next.length - 1].nodeId = d.nodes.answerId
                  next[next.length - 1].parentId = d.nodes.questionId
                }
              } catch { /* 兼容旧格式 */ }
            }
          }
        }
        // 流式增量只重渲染，不重建整列 DOM（React 的 key 保证最小更新）
        if (chat.currentId === ownerId) { setForce(x => x + 1); scrollBottom() }
        if (finished) break
      }
    } catch (e: any) {
      const aborted = ac.signal.aborted
      if (!finished && !aborted && !ai.content) {
        ai.content = '（回答生成失败，请稍后重试）'
        setError('连接中断，请检查网络后重试')
      } else if (aborted) {
        message.info('已停止生成，已收到的部分保留')
      }
    } finally {
      chat.removeAborter(ownerId)
      setBusy(ownerId, false)
      ai.streaming = false
      chat.cacheMessages(ownerId, next)
      if (chat.currentId === ownerId) setForce(x => x + 1)
      const queued = queuedRef.current.shift()
      queuedRef.current = [...queuedRef.current]
      setQueuedList([...queuedRef.current])
      if (queued) setTimeout(() => void send(queued), 200)
    }
  }

  function setBusy(id: string, busy: boolean) {
    const s = useChatStore.getState()
    const ids = new Set(s.busyIds)
    busy ? ids.add(id) : ids.delete(id)
    useChatStore.setState({ busyIds: ids, sending: ids.has(s.currentId) })
  }

  async function uploadFiles(files: FileList | File[] | null) {
    const list = Array.from(files || [])
    if (!list.length) return
    // 旧版：文档页有勾选框则直接用；否则弹确认框。问答页无勾选框 → 弹框
    const ok = await new Promise<boolean | null>(resolve => {
      let choice = false
      Modal.confirm({
        title: '文档可见范围', okText: '确认上传', cancelText: '取消',
        content: <div>
          <p style={{ margin: '0 0 12px' }}>已选择 {list.length} 个文件，请确认文档可见范围：</p>
          <label style={{ display: 'flex', gap: 8, alignItems: 'flex-start', marginBottom: 10 }}>
            <input type="radio" name="chatVis" defaultChecked onChange={() => { choice = false }} />
            <span><b>仅本部门 + 上级</b><div style={{ fontSize: 11.5, color: 'var(--muted)' }}>只有本部门及上级组织能看到</div></span>
          </label>
          <label style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
            <input type="radio" name="chatVis" onChange={() => { choice = true }} />
            <span><b>含下级部门可见</b><div style={{ fontSize: 11.5, color: 'var(--muted)' }}>本部门及下级组织都能看到</div></span>
          </label>
        </div>,
        onOk: () => resolve(choice), onCancel: () => resolve(null)
      })
    })
    if (ok === null) return
    let success = 0
    for (let i = 0; i < list.length; i++) {
      setUploadMsg({ type: '', text: `[${i + 1}/${list.length}] 正在解析 ${list[i].name} …` })
      try { await uploadDocument(list[i], undefined, ok); success++ }
      catch (e: any) { message.error(`${list[i].name}：${e?.message || ''}`) }
    }
    setUploadMsg(null)
    if (success) message.success(`完成：成功 ${success} 份，已可提问`)
  }

  const msgs = chat.messages
  const hasMessages = msgs.length > 0
  const busy = chat.sending
  void force  // 流式重渲染触发器

  return (
    <>
      <header className="topbar">
        <div className="topbar-left"><div className="topbar-title">知识库问答</div></div>
        <div className="topbar-nav"><AdminEntry /></div>
      </header>
      <div className="scroll-area" id="scrollArea" ref={scrollRef}>
        {!hasMessages && !initializing ? (
          <div className="welcome" id="welcome">
            <div className="welcome-mark has-logo"><img className="brand-logo" src="/assets/logo.svg" alt="" /></div>
            <h1>水利知识库智能问答</h1>
            <p>上传规程、报告或表格文档后，基于知识库内容提问。回答下方会标注引用的原文片段，支持多轮追问与会话分支。</p>
            <div className="quick-list" id="quickList">
              {chat.suggestions.map((s, i) => <button className="quick-item" key={i} onClick={() => setQ(s)}>{s}</button>)}
            </div>
            <div className="quick-tip" id="quickTip">根据你的提问记录推荐，点击可填入输入框</div>
          </div>
        ) : (
          <div className="messages" id="messages">
            {msgs.map((m, i) => m.role === 'user' ? (
              <div className="msg-group user" key={i}>
                {m.branchSeal && <div className="branch-seal"><span>↳ 接着这一轮换了个问法 · 以下为新分支（上面的原对话保留）</span></div>}
                <div className="user-ask">
                  <div className="ask-meta"><span className="ask-time">{fullTime(m.ts)}</span></div>
                  <div className="user-bubble">{m.content}</div>
                </div>
              </div>
            ) : (
              <div className="msg-group ai" key={i}>
                <div className={'ai-card' + (m.streaming ? ' streaming' : '')}>
                  {/* 头部从提问发出起就显示： badge/助手名/时间/运行中状态；
                      token 消耗要等 done 事件才有值，所以它自然在回答结束后出现。 */}
                  <div className="ai-card-head">
                    <span className="ai-badge">水</span><span className="ai-card-name">水利知识库助手</span>
                    {m.branch && <span className="branch-chip">分支</span>}
                    {m.streaming && <span className="ai-state" style={{ color: 'var(--primary)' }}>生成中…</span>}
                    <span className="ai-time">{fullTime(m.ts)}</span>
                    <span className="ai-card-actions">
                      {m.usage && localStorage.getItem('wrk_pref_usage') !== '0' &&
                        <span className="ai-usage" title={usageTip(m.usage)}>{usageInner(m.usage)}</span>}
                      {!!m.sources?.length && <button className="icon-btn ai-cite-count" title="点击查看全部引用片段"
                        onClick={() => {
                          const groups = groupCites(m.sources || [])
                          const allOpen = groups.every((_, k) => openCites[`${i}-${k}`])
                          setOpenCites(s => {
                            const next = { ...s }
                            groups.forEach((_, k) => { next[`${i}-${k}`] = !allOpen })
                            return next
                          })
                          setTimeout(() => scrollBottom(true), 120)
                        }}>▤ {groupCites(m.sources).length} 文档 / {m.sources.length} 片段</button>}
                      <button className="icon-btn" title="复制回答到剪贴板"
                        onClick={() => { void navigator.clipboard.writeText(m.content); message.success('回答已复制') }}>复制</button>
                    </span>
                  </div>
                  <div className="ai-card-body"
                    onClick={e => {
                      const el = (e.target as HTMLElement).closest('.ask-suggest') as HTMLElement | null
                      if (!el) return
                      const q = el.getAttribute('data-q') || String(el.textContent || '').trim()
                      if (!q) return
                      setQ(q)
                      document.getElementById('question')?.focus()
                      message.info('已填入输入框，确认后即可发送')
                    }}
                    dangerouslySetInnerHTML={{ __html: renderMarkdown(m.content) + (m.streaming ? '<span class="cursor"></span>' : '') }} />
                  {!m.streaming && <div className="cite-zone">
                    {!!m.sources?.length && <>
                      <div className="cite-row">
                        {groupCites(m.sources).map((g, k) => (
                          <span className={'cite-chip' + (openCites[`${i}-${k}`] ? ' on' : '')}
                            key={k} title={`展开该文档的 ${g.segs.length} 个引用片段`}
                            onClick={() => setOpenCites(s => ({ ...s, [`${i}-${k}`]: !s[`${i}-${k}`] }))}>
                            <span className="cite-num">{k + 1}</span><span className="cite-file">{g.name}</span>
                            {g.segs.length > 1 && <span className="cite-multi">{g.segs.length}段</span>}
                          </span>
                        ))}
                      </div>
                      {groupCites(m.sources).map((g, k) => (
                        <div className={`cite-preview${openCites[`${i}-${k}`] ? ' open' : ''}`} key={k} id={`cite-p-${i}-${k}`}>
                          {g.segs.map((s, j) => <div className={'cite-seg' + (activeCite === `${i}-${k}-${j}` ? ' on' : '')} key={j}
                            role="button" title="点击查看该段原文全文"
                            onClick={() => {
                              setActiveCite(`${i}-${k}-${j}`)
                              void showChunkDetail(String(s.fileName || g.name), Number(s.chunkIndex || 0))
                            }}>
                            <span className="cite-seg-no">第{Number(s.chunkIndex || 0) + 1}段</span>{s.preview || ''}
                          </div>)}
                        </div>
                      ))}
                    </>}
                  </div>}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
      <div className="composer-wrap">
        <div className={`error-bar${error ? ' show' : ''}`}>{error}</div>
        <div className="composer">
          {queuedList.length > 0 && <div className="queue-bar">
            <span className="queue-label">待发送</span>
            {queuedList.map((text, idx) => <span className="queue-chip" key={idx}
              title="点击撤回这条问题到输入框重新编辑"
              onClick={() => {
                setQ(text)
                dequeueAt(idx)
                setTimeout(() => inputRef.current?.focus(), 50)
              }}>
              <span className="queue-no">{idx + 1}</span>
              <span className="queue-text">{text}</span>
              <span className="queue-undo">↩</span>
            </span>)}
          </div>}
          <textarea id="question" rows={1} placeholder="输入你的问题，Enter 发送，Shift+Enter 换行"
            value={q} ref={inputRef}
            onChange={e => {
              setQ(e.target.value)
              // 按内容高度自适应，长问题完整显示（最多约 8 行）
              const el = e.target
              el.style.height = 'auto'
              el.style.height = Math.min(200, Math.max(38, el.scrollHeight)) + 'px'
            }}
            onKeyDown={e => {
              if (e.key === 'Enter' && !e.shiftKey && !(e as any).isComposing) {
                e.preventDefault()
                if (busy) { const text = q.trim(); if (text) { enqueue(text); setQ(''); message.info(`已排队（第 ${queuedRef.current.length} 条），回答结束后依次发送`) } }
                else void send()
              }
            }} />
          <div className="composer-bar">
            <span className="composer-tool" title="基于知识库检索作答"><span style={{ color: 'var(--primary)' }}>◈</span> 知识库问答</span>
            {auth.has('doc:write_chat') && <>
              <button className="composer-tool" onClick={() => fileInput.current?.click()} title="上传文档到知识库">⇪ 上传文档</button>
              <input ref={fileInput} type="file" multiple hidden onChange={e => { void uploadFiles(e.target.files); e.target.value = '' }} />
            </>}
            <span className="model-pick-slot">
              <Select className="model-pick" value={modelId || undefined} size="small"
                popupMatchSelectWidth={false} placeholder="默认模型" title="选择本次回答使用的对话模型"
                disabled={chatModels.length <= 1}
                style={{ visibility: chatModels.length > 1 ? 'visible' : 'hidden' }}
                options={[
                  { value: 0, label: '默认模型' },
                  ...chatModels.map(m => ({ value: m.id, label: m.name }))
                ]}
                onChange={v => {
                  const n = Number(v) || 0
                  setModelId(n || null)
                  localStorage.setItem('wrk_chat_model', n ? String(n) : '')
                }} />
            </span>
            {busy && <button className="stop-btn" onClick={() => chat.aborters[chat.currentId]?.abort()}
              title="停止当前回答">■ 停止</button>}
            <button className="send-btn" onClick={() => {
              if (busy) {
                const text = q.trim()
                if (!text) { message.info('当前正在回答，输入问题后可排队发送'); return }
                enqueue(text)
                setQ('')
                message.info(`已排队（第 ${queuedRef.current.length} 条），回答结束后依次发送`)
                return
              }
              void send()
            }}>{busy ? '排队' : <>发送 <span style={{ opacity: .75 }}>↵</span></>}</button>
          </div>
        </div>
        <div className="composer-tip">回答基于知识库生成，关键数据请以正式规程为准</div>
        {uploadMsg && <div className={`upload-msg ${uploadMsg.type}`}>{uploadMsg.text}</div>}
      </div>

      <WbModal size="chunk"
        title={chunkDetail ? `原文详情 · ${chunkDetail.fileName} · 第${chunkDetail.index + 1}段` : '原文详情'}
        open={!!chunkDetail} okText="关闭" cancelText={null}
        onOk={() => setChunkDetail(null)} onCancel={() => setChunkDetail(null)}>
        <div className="chunk-detail-text">{chunkDetail?.content || '正在读取原文…'}</div>
      </WbModal>
    </>
  )
}
