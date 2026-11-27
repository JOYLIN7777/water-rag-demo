/** 会话结构页：DOM/class 与旧版 tree.html 1:1，树形分支/回退/命名/分叉提问 */
import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { message } from 'antd'
import { http } from '@/api/http'
import { useAuthStore } from '@/store/auth'
import '@/styles/tree.css'
import TopbarNav from '@/components/TopbarNav'
import AdminEntry from '@/components/AdminEntry'

interface TreeNode {
  id: string
  parentId: string
  role: 'user' | 'assistant'
  content: string
  ts?: string
  branch?: string
}
interface TreeData {
  nodes: TreeNode[]
  head: string
  branches: Record<string, string>
}

const PERM_DENIED = {
  title: '没有使用权限',
  text: '当前账号未被授予「问答分支」的使用权限。',
  hint: '如需使用，请联系管理员在「菜单权限」中为你的角色勾选该功能。'
}

/** 与检索页同款的权限锁定卡片 */
function LockedCard() {
  return (
    <>
      <header className="topbar">
        <div className="topbar-left"><TopbarNav /><div className="topbar-title">会话结构</div><span className="topbar-sub">消息树与分支</span></div>
        <div className="topbar-nav"><AdminEntry /></div>
      </header>
      <div className="page">
        <div className="card" style={{ margin: 'auto', maxWidth: 520, padding: 36, textAlign: 'center' }}>
          <div style={{ fontSize: 34, marginBottom: 12 }}>🔒</div>
          <div style={{ fontSize: 15, fontWeight: 650, marginBottom: 8 }}>{PERM_DENIED.title}</div>
          <div style={{ fontSize: 13, color: '#6b7a99', lineHeight: 1.7 }}>
            {PERM_DENIED.text}<br />{PERM_DENIED.hint}
          </div>
          <a href="/index.html" style={{ display: 'inline-block', marginTop: 18, color: 'var(--primary,#3b6ef5)', textDecoration: 'none', fontSize: 13 }}>← 返回智能问答</a>
        </div>
      </div>
    </>
  )
}

export default function TreePage() {
  const auth = useAuthStore()
  const [params] = useSearchParams()
  const [sid, setSid] = useState(params.get('sid') || '')
  const [tree, setTree] = useState<TreeData | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [q, setQ] = useState('')
  const [branchName, setBranchName] = useState('')
  const [tip, setTip] = useState('')
  const [tipOk, setTipOk] = useState(true)

  function showTip(text: string, ok: boolean) { setTip(text); setTipOk(ok) }

  async function load(id = sid) {
    if (!id.trim()) { showTip('请先填写会话ID', false); return }
    try {
      const j = await http.get<TreeData>('/api/chat/tree?sessionId=' + encodeURIComponent(id.trim()))
      setTree(j)
      setSelected(j.head || null)
      showTip('已加载', true)
    } catch (e: any) { showTip('加载失败：' + (e?.message || ''), false) }
  }

  // 带 ?sid= 进来直接加载（问答页"还有其它分支"链接）
  useEffect(() => {
    const id = params.get('sid')
    if (id) { setSid(id); void load(id) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function rewind(id: string) {
    try {
      await http.post('/api/chat/rewind?sessionId=' + encodeURIComponent(sid.trim()) + '&nodeId=' + encodeURIComponent(id))
      setSelected(id)
      await load()
      message.success('已回到该节点')
    } catch (e: any) { showTip('回退失败：' + (e?.message || ''), false) }
  }

  async function forkBranch() {
    if (!selected) { showTip('请先在树中选中一个节点', false); return }
    if (!branchName.trim()) { showTip('请填写分支名', false); return }
    try {
      await http.post('/api/chat/fork?sessionId=' + encodeURIComponent(sid.trim()) +
        '&nodeId=' + encodeURIComponent(selected) + '&name=' + encodeURIComponent(branchName.trim()))
      setBranchName('')
      await load()
      message.success('分支已标记')
    } catch (e: any) { showTip('新建分支失败：' + (e?.message || ''), false) }
  }

  async function ask() {
    const query = q.trim()
    if (!query) { showTip('请输入问题', false); return }
    if (!sid.trim()) { showTip('请先填写会话ID', false); return }
    try {
      const r = await fetch('/api/chat/stream', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: sid.trim(), question: query, parentId: selected || null })
      })
      if (!r.ok) throw new Error('HTTP ' + r.status)
      await r.text()
      setQ('')
      await load()
      message.success('已生成，新节点挂到选中节点下')
    } catch (e: any) { showTip('提问失败：' + (e?.message || ''), false) }
  }

  if (auth.loaded && !auth.has('tree:use')) return <LockedCard />

  /** 递归渲染树节点，与旧版 buildTree 同逻辑 */
  function buildTree(parentId: string, depth: number): React.ReactNode[] {
    if (!tree) return []
    const out: React.ReactNode[] = []
    for (const n of tree.nodes.filter(x => x.parentId === parentId)) {
      const isHead = n.id === tree.head
      const text = String(n.content || '').replace(/\n/g, ' ')
      out.push(
        <div key={n.id}
          className={`node ${n.role}${isHead ? ' head' : ''}${n.id === selected ? ' selected' : ''}`}
          style={{ ['--indent' as string]: `${depth * 22}px` }}
          onClick={() => setSelected(n.id)}>
          <div className="node-head">
            <span className="role-tag">{n.role === 'user' ? '用户' : '助手'}</span>
            {isHead && <span className="head-tag">当前</span>}
            {n.branch && <span className="branch-tag">{n.branch}</span>}
            <span style={{ marginLeft: 'auto' }}>{n.ts ? n.ts.slice(11, 19) : ''}</span>
          </div>
          <div className="node-text">{text.length > 150 ? text.slice(0, 150) + '…' : text}</div>
          <div className="node-actions">
            <button className="btn btn-sm" onClick={e => { e.stopPropagation(); void rewind(n.id) }}>回到这里</button>
          </div>
        </div>
      )
      out.push(...buildTree(n.id, depth + 1))
    }
    return out
  }

  const branches = Object.entries(tree?.branches || {})
  const selHint = selected
    ? `选中节点：${selected.slice(0, 8)}${selected === tree?.head ? '（当前）' : ''}`
    : `当前位置：${tree?.head ? tree.head.slice(0, 8) : '根'}`

  return (
    <>
      <header className="topbar">
        <div className="topbar-left"><TopbarNav /><div className="topbar-title">会话结构</div><span className="topbar-sub">消息树与分支</span></div>
        <div className="topbar-nav"><AdminEntry /></div>
      </header>
      <div className="page">
        <div className="page-head">
          <h1 className="page-title">会话结构</h1>
          <p className="page-desc">从任意一条回答继续追问，或回到之前的问题重新提问，原来的记录都会保留。</p>
        </div>
        <div className="intro">
          回到之前的回答重新提问时，原来的问答记录不会丢失，而是保留为另一条路线。切换路线后，系统会沿当前这条路线继续回答，不会混入其他路线的内容。
        </div>

        <div className="toolbar">
          <label>会话ID</label>
          <input type="text" id="sid" placeholder="问答页左侧栏「复制会话ID」按钮" value={sid} onChange={e => setSid(e.target.value)} />
          <button className="btn" onClick={() => void load()}>加载会话树</button>
          <button className="btn" onClick={() => location.reload()}>刷新</button>
          <span id="stat">{tree ? `${tree.nodes.length} 个节点 · ${branches.length} 个命名分支` : ''}</span>
        </div>

        <div className={`msg${tip ? ' show' : ''} ${tipOk ? 'ok' : 'err'}`} id="msg">{tip}</div>

        <div className="layout2">
          <div className="card">
            <div className="card-head"><h3>消息分支图</h3></div>
            <div id="tree">
              {tree
                ? (tree.nodes.length ? buildTree('root', 0) : <div className="empty">该会话还没有消息</div>)
                : <div className="empty">输入会话ID后加载</div>}
            </div>
            <div className="legend">
              <i style={{ background: '#60a5fa' }} />用户消息　<i style={{ background: 'var(--violet)' }} />助手回答　
              <i style={{ background: 'var(--primary)' }} />当前位置（head）　<i style={{ background: '#fcd34d' }} />命名分支起点
            </div>
          </div>
          <div className="card">
            <div className="card-head"><h3>从选中节点继续提问</h3></div>
            <div className="ask-box">
              <textarea id="q" placeholder="输入问题，将从树下选中的节点分叉…" value={q} onChange={e => setQ(e.target.value)} />
              <div className="row">
                <span className="hint" id="selHint">{selHint}</span>
                <button className="btn btn-primary" onClick={() => void ask()}>发送并分叉</button>
              </div>
            </div>
            <div className="legend">点击树中节点可选中它；再点「发送并分叉」，新回答会挂在该节点下，原分支不受影响。若只想切换当前位置而不发消息，点节点上的「回到这里」。</div>
            <div style={{ borderTop: '1px solid var(--line)', marginTop: 16, paddingTop: 16 }}>
              <div className="card-head"><h3 style={{ fontSize: 13 }}>命名分支</h3></div>
              <div style={{ display: 'flex', gap: 8, marginBottom: 8 }}>
                <input type="text" id="branchName" placeholder="分支名，如：换一种方案" style={{ flex: 1 }}
                  value={branchName} onChange={e => setBranchName(e.target.value)} />
                <button className="btn" onClick={() => void forkBranch()}>标记</button>
              </div>
              <div id="branchList" className="hint">
                {branches.length
                  ? branches.map(([name, id], i) => <span key={name}>{i ? '　' : ''}{name}（{id.slice(0, 8)}）</span>)
                  : '（暂无命名分支）'}
              </div>
            </div>
          </div>
        </div>
      </div>
    </>
  )
}
