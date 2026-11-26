/** 问答页：DOM/class 与旧版 index.html 1:1，流式/引用/分支/草稿隔离全功能 */
import { useEffect, useRef, useState } from 'react'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { message } from 'antd'
import { useSearchParams } from 'react-router-dom'
import { useChatStore, type ChatMessageExt } from '@/store/chat'
import { useAuthStore } from '@/store/auth'
import { tsLocal } from '@/lib/ui'
import { streamChat, uploadDocument } from '@/api'
import { Modal } from 'antd'
import AdminEntry from '@/components/AdminEntry'
import '@/styles/chat.css'

interface CiteGroup { name: string; segs: Array<{ fileName?: string; chunkIndex?: number; preview?: string }> }

/** 模型偶尔输出「###一、标题」「####1.小节」这类标题后不空格的 Markdown，
 *  marked 会当普通文本原样渲染。这里只规范化行首标题，别动正文里的 #。 */
function normalizeMarkdown(text: string): string {
  return String(text || '').replace(/^([ \t]*)(#{1,6})[ \t\u3000]*(?=\S)/gm, '$1$2 ')
}

function renderMarkdown(text: string): string {
  const html = marked.parse(normalizeMarkdown(text), { breaks: true }) as string
  // 回答里模型给出的示例问题（含问号的列表项）做成可点击：蓝色 + 点击填入输入框
  const withSuggest = html.replace(/<li>([^<]*[？?][^<]*)<\/li>/g, '<li class="ask-suggest">$1</li>')
  return DOMPurify.sanitize(withSuggest)
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
  const fileInput = useRef<HTMLInputElement>(null)
  const abortMap = useRef<Record<string, AbortController>>({})
  const [force, setForce] = useState(0)   // 触发重渲染（流式增量）

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
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 草稿按会话隔离：切会话时存旧草稿、载新草稿
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

  async function send(text?: string) {
    const query = (text ?? q).trim()
    // 页面刚挂载、初始化还没设 currentId 时先建空会话，避免消息挂到空 key 上被后续初始化冲掉
    const ownerId = chat.currentId || chat.newSession()
    if (!query || chat.busyIds.has(ownerId)) return
    setQ('')
    setError('')
    try { localStorage.removeItem(`draft:${ownerId}`) } catch { /* 同上 */ }

    chat.touchSession(ownerId, query.length > 18 ? query.slice(0, 18) + '…' : query, query)
    const ac = new AbortController()
    abortMap.current[ownerId] = ac
    setBusy(ownerId, true)

    const store = chat.bySession[ownerId] || chat.messages
    const at = Date.now()
    const um: ChatMessageExt = { role: 'user', content: query, sources: [], ts: at }
    const ai: ChatMessageExt = { role: 'assistant', content: '', sources: [], streaming: true, ts: at }
    const next = [...store, um, ai]
    chat.cacheMessages(ownerId, next)
    if (chat.currentId === ownerId) setForce(x => x + 1)
    scrollBottom(true)

    let finished = false
    try {
      const r = await fetch('/api/chat/stream', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: ownerId, question: query }), signal: ac.signal
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
        if (chat.currentId === ownerId) setForce(x => x + 1)
        scrollBottom()
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
      delete abortMap.current[ownerId]
      setBusy(ownerId, false)
      ai.streaming = false
      chat.cacheMessages(ownerId, next)
      if (chat.currentId === ownerId) setForce(x => x + 1)
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
      catch (e: any) { setUploadMsg({ type: 'err', text: `✗ ${list[i].name}：${e?.message || ''}` }) }
    }
    setUploadMsg({ type: success ? 'ok' : 'err', text: `✓ 完成：成功 ${success} 份，已可提问` })
    message.success(`${success} 份文档已入库`)
    setTimeout(() => setUploadMsg(null), 3000)
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
        {!hasMessages ? (
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
                      {!!m.sources?.length && <button className="icon-btn ai-cite-count" title="引用片段">▤ {groupCites(m.sources).length} 篇</button>}
                      <button className="icon-btn" title="复制回答到剪贴板"
                        onClick={() => { void navigator.clipboard.writeText(m.content); message.success('回答已复制') }}>复制</button>
                    </span>
                  </div>
                  <div className="ai-card-body"
                    onClick={e => {
                      const li = (e.target as HTMLElement).closest('li.ask-suggest')
                      if (!li) return
                      const q = String(li.textContent || '').trim()
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
                          <span className="cite-chip" key={k} title={`展开该文档的 ${g.segs.length} 个引用片段`}
                            onClick={() => setOpenCites(s => ({ ...s, [`${i}-${k}`]: !s[`${i}-${k}`] }))}>
                            <span className="cite-num">{k + 1}</span><span className="cite-file">{g.name}</span>
                            {g.segs.length > 1 && <span className="cite-multi">{g.segs.length}段</span>}
                          </span>
                        ))}
                      </div>
                      {groupCites(m.sources).map((g, k) => (
                        <div className={`cite-preview${openCites[`${i}-${k}`] ? ' open' : ''}`} key={k} id={`cite-p-${i}-${k}`}>
                          {g.segs.map((s, j) => <div className="cite-seg" key={j}>
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
          <textarea id="question" rows={1} placeholder="输入你的问题，Enter 发送，Shift+Enter 换行"
            value={q} onChange={e => setQ(e.target.value)}
            onKeyDown={e => {
              if (e.key === 'Enter' && !e.shiftKey && !(e as any).isComposing) { e.preventDefault(); void send() }
            }} />
          <div className="composer-bar">
            <span className="composer-tool" title="基于知识库检索作答"><span style={{ color: 'var(--primary)' }}>◈</span> 知识库问答</span>
            {auth.has('doc:write_chat') && <>
              <button className="composer-tool" onClick={() => fileInput.current?.click()} title="上传文档到知识库">⇪ 上传文档</button>
              <input ref={fileInput} type="file" multiple hidden onChange={e => { void uploadFiles(e.target.files); e.target.value = '' }} />
            </>}
            <button className="send-btn" onClick={() => {
              if (busy) { abortMap.current[chat.currentId]?.abort(); return }
              void send()
            }}>{busy ? '停止' : <>发送 <span style={{ opacity: .75 }}>↵</span></>}</button>
          </div>
        </div>
        <div className="composer-tip">回答基于知识库生成，关键数据请以正式规程为准</div>
        {uploadMsg && <div className={`upload-msg ${uploadMsg.type}`}>{uploadMsg.text}</div>}
      </div>
    </>
  )
}
