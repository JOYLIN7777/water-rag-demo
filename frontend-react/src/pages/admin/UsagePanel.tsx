/** 后台-用量统计：DOM/class 与旧版 admin.html usage 面板 1:1（4 张卡片 + 按模型表 + 钻取明细） */
import { useEffect, useState } from 'react'
import WbModal from '@/components/WbModal'
import { num, tsLocal } from '@/lib/ui'
import { http } from '@/api/http'
import '@/styles/admin.css'

interface Summary {
  totals?: { todayRequests?: number; todayTokens?: number; monthRequests?: number; monthTokens?: number }
  models?: Array<{ model: string; requests: number; inputTokens: number; outputTokens: number }>
  hint?: string
  localDrill?: boolean
}
interface Record0 {
  ts: string; user?: string; model?: string; promptTokens?: number
  completionTokens?: number; totalTokens?: number; durationMs?: number; question?: string
}

export default function UsagePanel() {
  const [sum, setSum] = useState<Summary | null>(null)
  const [hint, setHint] = useState('')
  const [err, setErr] = useState('')
  const [drill, setDrill] = useState<'today' | 'month' | null>(null)
  const [records, setRecords] = useState<Record0[]>([])
  const [loading, setLoading] = useState(false)

  async function load() {
    try {
      const j = await http.get<Summary>('/api/usage/summary')
      setSum(j)
      setHint(j?.hint || '')
      setErr('')
    } catch (e: any) {
      setErr('')
      setHint('暂未获取到用量数据：' + (e?.message || ''))
    }
  }
  useEffect(() => { void load() }, [])

  async function openDrill(scope: 'today' | 'month') {
    if (!sum?.localDrill) return
    setDrill(scope); setRecords([]); setLoading(true)
    try {
      const j = await http.get<{ records?: Record0[] }>(`/api/usage/records?scope=${encodeURIComponent(scope)}&limit=500`)
      setRecords(Array.isArray(j?.records) ? j.records : [])
    } catch (e: any) {
      setHint('明细读取失败：' + (e?.message || ''))
    } finally { setLoading(false) }
  }

  const t = sum?.totals || {}
  const drillable = !!sum?.localDrill
  const label = drill === 'month' ? '本月' : '今日'
  const sumTokens = records.reduce((s, r) => s + Number(r.totalTokens || 0), 0)
  const noQ = records.filter(r => !String(r.question || '').trim()).length

  function card(kind: 'todayReq' | 'todayTok' | 'monthReq' | 'monthTok') {
    if (kind === 'todayReq') return <div className="stat-card" key={kind}>
      <div className="stat-num">{sum ? num(t.todayRequests) : '—'}</div>
      <div className="stat-label">今日请求次数</div></div>
    if (kind === 'monthReq') return <div className="stat-card" key={kind}>
      <div className="stat-num">{sum ? num(t.monthRequests) : '—'}</div>
      <div className="stat-label">本月请求次数</div></div>
    const isToday = kind === 'todayTok'
    const scope = isToday ? 'today' : 'month'
    return <div key={kind}
      className={'stat-card drillable' + (drillable ? '' : ' disabled')}
      role="button" tabIndex={0}
      title={drillable ? `点击查看${isToday ? '今日' : '本月'}每一次消耗` : '官方用量接口只提供汇总，没有单次明细'}
      onClick={() => void openDrill(scope as 'today' | 'month')}
      onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') void openDrill(scope as 'today' | 'month') }}>
      <div className="stat-num">{sum ? num(isToday ? t.todayTokens : t.monthTokens) : '—'}</div>
      <div className="stat-label">{isToday ? '今日消耗 Token' : '本月消耗 Token'}<span className="drill-tip">查看明细 ›</span></div>
    </div>
  }

  return (
    <>
      <div className="page-head"><h1 className="page-title">用量统计</h1>
        <p className="page-desc">查看模型调用次数与用量；点消耗数字可查看每次调用对应的提问。</p></div>
      {err && <div className="msg err show">{err}</div>}
      <div className="stats" id="uStats">
        {card('todayReq')}{card('todayTok')}{card('monthReq')}{card('monthTok')}
      </div>

      <div className="card">
        <div className="card-head"><h3>按模型查看</h3>
          <button className="btn btn-sm" onClick={() => void load()}>刷新</button></div>
        <div className="scroll">
          <table className="data-table">
            <thead><tr><th>模型</th><th>请求次数</th><th>输入Token</th><th>输出Token</th></tr></thead>
            <tbody>
              {(sum?.models || []).length === 0
                ? <tr><td colSpan={4} className="empty">所选时间范围暂无调用记录</td></tr>
                : (sum?.models || []).map(m => <tr key={m.model}>
                  <td className="name">{m.model}</td>
                  <td>{num(m.requests)}</td>
                  <td>{num(m.inputTokens)}</td>
                  <td>{num(m.outputTokens)}</td>
                </tr>)}
            </tbody>
          </table>
        </div>
        <div className="hint" id="uUsageHint">{hint}</div>
      </div>

      <WbModal size="chunk" title={`${label}每一次消耗`} open={!!drill} okText="关闭" cancelText={null}
        onOk={() => setDrill(null)} onCancel={() => setDrill(null)}>
        {loading
          ? <div className="empty-inline">正在读取明细…</div>
          : <>
            <div className="usage-sum">
              <span>共 <b>{records.length} 次调用</b></span>
              <span>合计 <b>{num(sumTokens)} tokens</b></span>
              {records.length >= 500 && <span className="hint">仅显示最近 500 条</span>}
              {noQ > 0 && <span className="hint">{noQ} 条为升级前的历史调用，当时未保存提问</span>}
            </div>
            {records.length === 0
              ? <div className="empty-inline">{label}还没有调用记录</div>
              : <div className="table-wrap" style={{ maxHeight: '60vh' }}>
                <table className="data-table usage-detail">
                  <thead><tr><th>时间</th><th>提问</th><th>模型</th><th>Token</th><th>耗时</th><th>账号</th></tr></thead>
                  <tbody>
                    {records.map((r, i) => {
                      const q = String(r.question || '').trim()
                      return <tr key={i}>
                        <td><span className="mono">{tsLocal(r.ts)}</span></td>
                        <td className={q ? 'q' : 'q dim'} title={q}>{q ? (q.length > 46 ? q.slice(0, 46) + '…' : q) : '—'}</td>
                        <td><span className="mono" style={{ fontSize: 11 }}>{r.model || '—'}</span></td>
                        <td title={`输入 ${num(r.promptTokens)} / 输出 ${num(r.completionTokens)}`}>{num(r.totalTokens)}</td>
                        <td>{((r.durationMs || 0) / 1000).toFixed(1)}s</td>
                        <td>{r.user || '—'}</td>
                      </tr>
                    })}
                  </tbody>
                </table>
              </div>}
          </>}
      </WbModal>
    </>
  )
}
