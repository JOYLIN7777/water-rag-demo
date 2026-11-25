/** 检索对比页：DOM/class 与旧版 search.html 1:1，三路检索 + 用户头 + 权限锁定页 */
import { useState } from 'react'
import { http } from '@/api/http'
import { getUser } from '@/api/auth'
import { useAuthStore } from '@/store/auth'
import { AppError } from '@/types'
import '@/styles/search.css'

interface Hit { file: string; chunk: number; score: number; text: string }
type CompareResult = { bm25?: Hit[]; knn?: Hit[]; hybrid?: Hit[] }

function headers(): Record<string, string> {
  const u = getUser()
  return u ? { 'X-User': u.user || '', 'X-Groups': (u.groups || []).join(',') } : {}
}

function renderHits(hits: Hit[] | undefined, label: string): React.ReactNode {
  if (!hits?.length) return <div className="empty">暂无匹配结果</div>
  return hits.map((h, i) => (
    <div className="hit" key={i}>
      <div className="meta">
        <span>{h.file} · 第{Number(h.chunk) + 1}段</span>
        <span className="score">{label} {(h.score || 0).toFixed(4)}</span>
      </div>
      <div className="text">{h.text}</div>
    </div>
  ))
}

export default function SearchPage() {
  const auth = useAuthStore()
  const [q, setQ] = useState('汛限水位是多少？')
  const [bm25, setBm25] = useState<React.ReactNode>(<div className="empty">等待检索…</div>)
  const [knn, setKnn] = useState<React.ReactNode>(<div className="empty">等待检索…</div>)
  const [hybrid, setHybrid] = useState<React.ReactNode>(<div className="empty">等待检索…</div>)

  // 页面级权限守卫：与旧版 guardPerm 一致，不跳走而是显示锁定卡片
  if (auth.loaded && !auth.has('search:use')) {
    return (
      <>
        <header className="topbar">
          <div className="topbar-left"><div className="topbar-title">检索对比</div><span className="topbar-sub">找到更准确的答案</span></div>
        </header>
        <div className="page">
          <div className="card" style={{ margin: 'auto', maxWidth: 520, padding: 36, textAlign: 'center' }}>
            <div style={{ fontSize: 34, marginBottom: 12 }}>🔒</div>
            <div style={{ fontSize: 15, fontWeight: 650, marginBottom: 8 }}>没有使用权限</div>
            <div style={{ fontSize: 13, color: '#6b7a99', lineHeight: 1.7 }}>
              当前账号未被授予「检索对比」的使用权限。<br />
              如需使用，请联系管理员在「菜单权限」中为你的角色勾选该功能。
            </div>
            <a href="/index.html" style={{ display: 'inline-block', marginTop: 18, color: 'var(--primary,#3b6ef5)', textDecoration: 'none', fontSize: 13 }}>← 返回智能问答</a>
          </div>
        </div>
      </>
    )
  }

  async function run() {
    const query = q.trim()
    if (!query) return
    const searching = <div className="empty">检索中…</div>
    setBm25(searching); setKnn(searching); setHybrid(searching)
    try {
      const j = await http.get<CompareResult>('/api/search/compare?q=' + encodeURIComponent(query), {
        headers: headers()
      })
      setBm25(renderHits(j.bm25, '关键词'))
      setKnn(renderHits(j.knn, '语义'))
      setHybrid(renderHits(j.hybrid, '融合'))
    } catch (e: any) {
      const msg = e instanceof AppError ? e.message : (e?.message || '未知错误')
      const failed = <div className="empty">失败：{msg}</div>
      setBm25(failed); setKnn(failed); setHybrid(failed)
    }
  }

  return (
    <>
      <header className="topbar">
        <div className="topbar-left"><div className="topbar-title">检索对比</div><span className="topbar-sub">找到更准确的答案</span></div>
      </header>
      <div className="page">
        <div className="page-head">
          <h1 className="page-title">检索对比</h1>
          <p className="page-desc">同一个问题用不同方式检索，看看哪种结果更符合你的需要。</p>
        </div>
        <div className="intro">
          关键词检索擅长精确匹配的数值与术语，语义检索擅长意思相近的说法，系统会把两种方式的结果合并排序，得到更全面的答案。
          检索范围会自动按你的账号权限过滤，你只能看到有权访问的内容。
        </div>
        <div className="querybar">
          <input id="q" type="text" placeholder="输入问题，如：汛限水位是多少？" value={q}
            onChange={e => setQ(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') void run() }} />
          <button className="primary-btn" onClick={() => void run()}>检索对比</button>
        </div>
        <div className="cols">
          <div className="col"><h3>① 关键词检索</h3><div id="bm25" className="hits">{bm25}</div></div>
          <div className="col"><h3>② 语义检索</h3><div id="knn" className="hits">{knn}</div></div>
          <div className="col hybrid"><h3>③ 智能融合（推荐）</h3><div id="hybrid" className="hits">{hybrid}</div></div>
        </div>
      </div>
    </>
  )
}
