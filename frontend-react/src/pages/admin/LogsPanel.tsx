/** 后台-日志：登录日志 / 操作日志，DOM/class 与旧版 admin.html 1:1（中文列 + 分页 15 + 导出） */
import { useEffect, useState } from 'react'
import { message } from 'antd'
import Pager from '@/components/Pager'
import { tsLocal } from '@/lib/ui'
import { http } from '@/api/http'
import '@/styles/admin.css'

interface LogRow {
  ts: string; user_id?: string; action?: string; status?: number
  duration_ms?: number; duration?: number; ip?: string; result?: string
}

const PAGE_SIZE = 15
/** 首次只取最近 100 条：实测 500 条约 125KB / 0.8 秒，切页时明显卡一下；
    需要更多时点「加载更多」按需追加，上限与后端一致 500 条。 */
const INIT_LIMIT = 100
const MAX_LIMIT = 500
/** 后端记录的是接口路径与英文结果码，界面统一转中文 */
const ACTION_CN: Record<string, string> = { '/api/auth/login': '账号登录', '/api/auth/refresh': '令牌刷新' }
const RESULT_CN: Record<string, string> = {
  SUCCESS: '成功', OK: '成功', ALLOW: '成功', DENIED: '已拒绝', ERROR: '异常', FAIL: '失败', FAILED: '失败'
}

function actionCn(a?: string) {
  const s = String(a || '')
  for (const k of Object.keys(ACTION_CN)) if (s.endsWith(k)) return ACTION_CN[k]
  return '其他操作'
}
function resultCn(r: string | undefined, status?: number) {
  const key = String(r || '').toUpperCase()
  if (RESULT_CN[key]) return RESULT_CN[key]
  const code = Number(status)
  if (code >= 400) return '失败'
  if (code >= 200 && code < 400) return '成功'
  return '—'
}

export default function LogsPanel({ type }: { type: 'login' | 'audit' }) {
  const [rows, setRows] = useState<LogRow[]>([])
  const [page, setPage] = useState(1)
  const [limit, setLimit] = useState(INIT_LIMIT)
  const [err, setErr] = useState('')

  const isLogin = type === 'login'
  const title = isLogin ? '登录日志' : '操作日志'
  const desc = isLogin ? '登录成功与失败的尝试都留有记录，失败原因可追溯。' : '查看账号在系统中的操作记录。'

  async function load() {
    try {
      if (isLogin) {
        const j = await http.get<LogRow[]>('/api/admin/login-logs?limit=' + limit)
        setRows(Array.isArray(j) ? j : ((j as any)?.logs || []))
      } else {
        const j = await http.get<{ logs?: LogRow[] }>('/api/audit/logs?limit=' + limit)
        setRows(Array.isArray(j?.logs) ? j.logs : [])
      }
      setErr('')
    } catch (e: any) { setErr(e?.message || '加载失败'); setRows([]) }
  }
  useEffect(() => {
    setPage(1)
    void load()
    // limit 变化 = 用户点「加载更多」，要按新条数重取
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [type, limit])

  async function exportCsv() {
    try {
      const r = await fetch('/api/audit/logs/export?type=' + (isLogin ? 'login' : 'operation'))
      if (!r.ok) { message.error('导出失败，请稍后重试'); return }
      const blob = await r.blob()
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = (isLogin ? '登录日志' : '操作日志') + '-' + new Date().toISOString().slice(0, 10) + '.csv'
      a.click()
      setTimeout(() => URL.revokeObjectURL(url), 2000)
    } catch { message.error('导出失败，请检查网络') }
  }

  const slice = rows.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE)

  return (
    <>
      <div className="page-head"><h1 className="page-title">{title}</h1>
        <p className="page-desc">{desc}</p></div>
      {err && <div className="msg err show">{err}</div>}
      <div className="card">
        <div className="card-head"><h3>{isLogin ? '登录记录' : '操作记录'}</h3>
          <span>
            <button className="btn btn-sm" onClick={() => void load()}>刷新</button>
            <button className="btn btn-sm btn-primary" style={{ marginLeft: 6 }} onClick={() => void exportCsv()}>
              导出{title}
            </button>
          </span></div>
        <div className="table-wrap">
          <table className="data-table">
            <thead><tr>
              {isLogin
                ? <><th>时间</th><th>账号</th><th>操作</th><th>状态</th><th>结果</th><th>耗时</th><th>来源 IP</th></>
                : <><th>时间</th><th>账号</th><th>动作</th><th>状态</th><th>耗时</th><th>结果</th></>}
            </tr></thead>
            <tbody>
              {slice.length === 0
                ? <tr><td colSpan={isLogin ? 7 : 6} className="empty">{err ? '暂时无法显示 · ' + err : (isLogin ? '暂无登录记录' : '暂无记录')}</td></tr>
                : slice.map((l, i) => isLogin
                  ? <tr key={i}>
                    <td>{tsLocal(l.ts)}</td>
                    <td className="name">{l.user_id || ''}</td>
                    <td>{actionCn(l.action)}</td>
                    <td><span className={'tag ' + (Number(l.status) >= 400 ? 'no' : 'ok')}>{String(l.status)}</span></td>
                    <td>{resultCn(l.result, l.status)}</td>
                    <td>{l.duration_ms != null ? l.duration_ms + ' ms' : '—'}</td>
                    <td><span className="mono" style={{ fontSize: 11.5 }}>{l.ip || '—'}</span></td>
                  </tr>
                  : <tr key={i}>
                    <td>{tsLocal(l.ts)}</td>
                    <td>{l.user_id || ''}</td>
                    <td>{l.action || ''}</td>
                    <td><span className={'tag ' + (Number(l.status) >= 400 ? 'no' : 'ok')}>{String(l.status)}</span></td>
                    <td>{String(l.duration_ms ?? l.duration ?? '—')}</td>
                    <td>{resultCn(l.result, l.status)}</td>
                  </tr>)}
            </tbody>
          </table>
        </div>
        <div className="logs-more">
          {rows.length > 0 && <span className="hint" style={{ margin: 0 }}>当前显示最近 {rows.length} 条</span>}
          {rows.length >= limit && limit < MAX_LIMIT &&
            <button className="btn btn-sm" onClick={() => setLimit(x => Math.min(x + 200, MAX_LIMIT))}>
              加载更多（最多 {MAX_LIMIT} 条）
            </button>}
        </div>
        <Pager id={isLogin ? 'lPager' : 'aPager'} total={rows.length} page={page} size={PAGE_SIZE} onChange={setPage} />
      </div>
    </>
  )
}
