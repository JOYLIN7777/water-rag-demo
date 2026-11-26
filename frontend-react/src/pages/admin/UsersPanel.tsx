/** 后台-用户管理：DOM/class 与旧版 admin.html users 面板 1:1 */
import { useEffect, useMemo, useState } from 'react'
import { Modal, message } from 'antd'
import { http } from '@/api/http'
import { adminApi } from '@/api'
import { useAuthStore } from '@/store/auth'
import { Avatar } from '@/components/Avatar'
import '@/styles/admin.css'

interface User { username: string; displayName?: string; groups?: string[]; tenant?: string; enabled?: boolean }
interface Org { key: string; name: string; parentKey?: string; membersAll?: number }

const PAGE_SIZE = 20

/** 页码窗口：首页/末页 + 当前页前后 2 页，中间用 0 表示省略号（对齐旧版 renderPager） */
function pageWindow(page: number, pages: number): number[] {
  const win = new Set<number>([1, pages])
  for (let p = Math.max(1, page - 2); p <= Math.min(pages, page + 2); p++) win.add(p)
  const out: number[] = []
  let prev = 0
  for (const p of [...win].sort((a, b) => a - b)) {
    if (prev && p - prev > 1) out.push(0)
    out.push(p)
    prev = p
  }
  return out
}

export default function UsersPanel() {
  const auth = useAuthStore()
  const [users, setUsers] = useState<User[]>([])
  const [orgs, setOrgs] = useState<Org[]>([])
  const [kw, setKw] = useState('')
  const [status, setStatus] = useState('')
  const [role, setRole] = useState('')
  const [orgKey, setOrgKey] = useState('')
  const [folded, setFolded] = useState<Set<string>>(new Set())
  const [page, setPage] = useState(1)
  const [jump, setJump] = useState('1')
  const [editUser, setEditUser] = useState<User | null>(null)
  const [pwdUser, setPwdUser] = useState<User | null>(null)
  const [form, setForm] = useState({ displayName: '', groups: '', enabled: true })
  const [newPwd, setNewPwd] = useState('')
  const [err, setErr] = useState('')

  async function load() {
    try {
      const [u, o] = await Promise.all([
        http.get<User[]>('/api/admin/users'),
        http.get<Org[]>('/api/orgs').catch(() => [] as Org[])
      ])
      setUsers(Array.isArray(u) ? u : [])
      setOrgs(Array.isArray(o) ? o : [])
    } catch (e: any) { setErr(e?.message || '加载失败') }
  }
  useEffect(() => { void load() }, [])

  const filtered = useMemo(() => {
    const k = kw.trim().toLowerCase()
    return users.filter(u => {
      if (k && ![u.username, u.displayName].some(v => String(v || '').toLowerCase().includes(k))) return false
      if (status === '1' && u.enabled === false) return false
      if (status === '0' && u.enabled !== false) return false
      if (role && !(u.groups || []).includes(role)) return false
      if (orgKey) {
        // 含下级：父链匹配
        const keys = new Set<string>()
        const queue = [orgKey]
        while (queue.length) {
          const cur = queue.shift()!
          if (keys.has(cur)) continue
          keys.add(cur)
          orgs.filter(o => o.parentKey === cur).forEach(o => queue.push(o.key))
        }
        if (!keys.has(u.tenant || 'default')) return false
      }
      return true
    })
  }, [users, kw, status, role, orgKey, orgs])

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE))
  const rows = filtered.slice((Math.min(page, totalPages) - 1) * PAGE_SIZE, Math.min(page, totalPages) * PAGE_SIZE)
  // 页码变化时把跳页输入框同步成当前页
  useEffect(() => { setJump(String(Math.min(page, totalPages))) }, [page, totalPages])

  function resetFilter() { setKw(''); setStatus(''); setRole(''); setOrgKey(''); setPage(1) }
  function goJump() {
    const n = parseInt(jump, 10)
    if (!isNaN(n)) setPage(Math.min(Math.max(n, 1), totalPages))
  }

  async function toggleEnabled(u: User, enabled: boolean) {
    try {
      await adminApi.updateUser(u.username, { enabled })
      setUsers(list => list.map(x => x.username === u.username ? { ...x, enabled } : x))
      message.success(enabled ? '已启用' : '已停用')
    } catch (e: any) { message.error(e?.message || '操作失败') }
  }

  function openEdit(u: User) {
    setEditUser(u)
    setForm({ displayName: u.displayName || '', groups: (u.groups || []).join(','), enabled: u.enabled !== false })
    setErr('')
  }
  async function saveEdit() {
    if (!editUser) return
    try {
      await adminApi.updateUser(editUser.username, {
        displayName: form.displayName,
        groups: form.groups.split(',').map(x => x.trim()).filter(Boolean),
        enabled: form.enabled
      })
      message.success('已保存'); setEditUser(null); await load()
    } catch (e: any) { setErr(e?.message || '保存失败') }
  }

  async function savePwd() {
    if (!pwdUser) return
    if (newPwd.length < 8) { setErr('新密码长度至少 8 位'); return }
    try {
      await adminApi.resetPassword(pwdUser.username, { password: newPwd })
      message.success('密码已重置'); setPwdUser(null); setNewPwd(''); setErr('')
    } catch (e: any) { setErr(e?.message || '重置失败') }
  }

  async function remove(u: User) {
    if (!window.confirm(`确定删除账号「${u.username}」吗？`)) return
    try { await adminApi.deleteUser(u.username); message.success('已删除'); await load() }
    catch (e: any) { message.error(e?.message || '删除失败') }
  }

  function exportUsers() {
    const header = ['账号', '显示名', '角色', '组织', '状态']
    const csv = '\uFEFF' + [header.join(','), ...filtered.map(u => [
      u.username, u.displayName || '', (u.groups || []).join('|'), u.tenant || '', u.enabled === false ? '停用' : '正常'
    ].join(','))].join('\n')
    const a = document.createElement('a')
    a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
    a.download = '用户清单.csv'; a.click(); URL.revokeObjectURL(a.href)
  }

  // 组织树（平铺 + padding，与旧版一致）
  function treeNodes() {
    const out: React.ReactNode[] = []
    const walk = (parent: string, depth: number) => {
      for (const o of orgs.filter(x => (x.parentKey || '') === parent)) {
        const kids = orgs.filter(x => x.parentKey === o.key)
        const isFolded = folded.has(o.key)
        out.push(<div key={o.key} className={`tree-node${orgKey === o.key ? ' active' : ''}`}
          data-k={o.key} style={{ paddingLeft: 10 + depth * 13 }} onClick={() => { setOrgKey(o.key); setPage(1) }}>
          <span className={`caret${kids.length ? '' : ' ghost'}`}
            onClick={e => {
              if (!kids.length) return
              e.stopPropagation()
              setFolded(prev => { const s = new Set(prev); s.has(o.key) ? s.delete(o.key) : s.add(o.key); return s })
            }}>{kids.length ? (isFolded ? '▸' : '▾') : ''}</span>
          <span className="tn" title={o.key}>{o.name}</span>
        </div>)
        if (!isFolded) walk(o.key, depth + 1)
      }
    }
    walk('', 0)
    return out
  }

  const allRoles = Array.from(new Set(users.flatMap(u => u.groups || [])))

  return (
    <>
      <div className="page-head"><h1 className="page-title">用户管理</h1>
        <p className="page-desc">按组织筛选和管理账号；左侧组织树仅作筛选，不在此处维护组织机构。</p></div>
      <div className="filter-bar">
        <div className="filter-item"><label htmlFor="uKw">关键字</label>
          <input id="uKw" type="text" value={kw} onChange={e => { setKw(e.target.value); setPage(1) }}
            placeholder="点击后输入账号 / 显示名" /></div>
        <div className="filter-item"><label htmlFor="uStatus">账号状态</label>
          <select id="uStatus" value={status} onChange={e => { setStatus(e.target.value); setPage(1) }}>
            <option value="">全部状态</option><option value="1">正常</option><option value="0">停用</option>
          </select></div>
        <div className="filter-item"><label htmlFor="uRole">所属角色</label>
          <select id="uRole" value={role} onChange={e => { setRole(e.target.value); setPage(1) }}>
            <option value="">全部角色</option>{allRoles.map(r => <option key={r} value={r}>{auth.groupLabel(r)}</option>)}
          </select></div>
        <div className="filter-actions">
          <button className="btn btn-sm btn-search" onClick={() => setPage(1)}>搜索</button>
          <button className="btn btn-sm" onClick={resetFilter}>重置</button>
          <button className="btn btn-sm btn-green" onClick={exportUsers}>导出用户</button>
          <button className="btn btn-sm btn-amber" onClick={() => {
            setEditUser({ username: '', displayName: '', groups: [], tenant: orgKey, enabled: true })
            setForm({ displayName: '', groups: '', enabled: true }); setErr('')
          }}>＋ 新增账号</button>
        </div>
      </div>
      {err && <div className="msg err show">{err}</div>}
      <div className="workbench" style={{ minHeight: 'auto' }}>
        <div className="tree-pane">
          <div className="tree-head">组织架构（仅用于筛选）</div>
          <div className="tree-node" onClick={() => { setOrgKey(''); setPage(1) }} style={{ paddingLeft: 10 }}>
            <span className="caret ghost" /><span className="tn">全部组织（{users.length}）</span>
          </div>
          <div id="uTree">{treeNodes()}</div>
        </div>
        <div className="data-pane">
          <div className="toolbar">
            <span style={{ fontSize: 12, color: 'var(--muted)' }} id="uFilterHint">
              共 {filtered.length} 个账号{orgKey ? `（组织：${orgs.find(o => o.key === orgKey)?.name || orgKey}）` : ''}
            </span><span className="grow" />
            <button className="btn btn-sm" onClick={() => void load()}>刷新</button>
          </div>
          <div className="table-wrap"><table className="data-table">
            <thead><tr><th className="row-idx">序号</th><th>账号</th><th>显示名</th><th>所属角色</th><th>所属组织</th><th>账号状态</th><th>操作</th></tr></thead>
            <tbody id="uTable">
              {rows.length === 0
                ? <tr><td colSpan={7} className="empty">没有符合条件的账号</td></tr>
                : rows.map((u, i) => {
                  const isAdmin = (u.groups || []).includes('admin')
                  return <tr key={u.username}>
                    <td className="row-idx">{(Math.min(page, totalPages) - 1) * PAGE_SIZE + i + 1}</td>
                    <td><span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
                      <Avatar name={u.displayName || u.username} size="sm" />
                      <a className="name" href="javascript:void(0)" style={{ color: 'var(--primary)', textDecoration: 'none' }}
                        onClick={() => openEdit(u)}>{u.username}</a>
                    </span></td>
                    <td>{u.displayName || ''}</td>
                    <td>{(u.groups || []).map(g => <span className="tag" key={g}>{auth.groupLabel(g)}</span>)}</td>
                    <td>{orgs.find(o => o.key === (u.tenant || 'default'))?.name || u.tenant}</td>
                    <td>{isAdmin
                      ? <><span className="tag ok">正常</span><span style={{ marginLeft: 8, fontSize: 11.5, color: 'var(--muted)' }}>系统管理员不可停用</span></>
                      : <><label className="switch" title={u.enabled === false ? '点击启用该账号' : '点击停用该账号'}>
                        <input type="checkbox" checked={u.enabled !== false} onChange={e => void toggleEnabled(u, e.target.checked)} />
                        <span className="track" /><span className="thumb" /></label>
                        <span style={{ marginLeft: 8, fontSize: 11.5, color: u.enabled === false ? 'var(--muted)' : 'var(--ok)' }}>
                          {u.enabled === false ? '停用' : '正常'}</span></>}
                    </td>
                    <td>{isAdmin
                      ? <span style={{ color: 'var(--muted)', fontSize: 11.5 }} title="系统管理员账号受保护">不可编辑</span>
                      : <>
                        <button className="op-btn" onClick={() => openEdit(u)}>编辑</button>
                        <button className="op-btn warn" onClick={() => { setPwdUser(u); setNewPwd(''); setErr('') }}>重置密码</button>
                        <button className="op-btn danger" onClick={() => void remove(u)}>删除</button>
                      </>}
                    </td>
                  </tr>
                })}
            </tbody>
          </table></div>
          <div className="pager" id="uPager">
            <span>共 {filtered.length} 条</span><span style={{ color: '#c3cddd' }}>|</span><span>每页 {PAGE_SIZE} 条</span>
            <button className="pg-btn" disabled={page <= 1} onClick={() => setPage(1)}>首页</button>
            <button className="pg-btn" disabled={page <= 1} onClick={() => setPage(page - 1)}>上一页</button>
            {pageWindow(page, totalPages).map((p, i) => p === 0
              ? <span className="pg-sep" key={'sep' + i}>…</span>
              : <button key={p} className={`pg-btn${p === page ? ' on' : ''}`} onClick={() => setPage(p)}>{p}</button>)}
            <button className="pg-btn" disabled={page >= totalPages} onClick={() => setPage(page + 1)}>下一页</button>
            <button className="pg-btn" disabled={page >= totalPages} onClick={() => setPage(totalPages)}>末页</button>
            <span className="pg-info">
              第 {Math.min(page, totalPages)} / {totalPages} 页 · 跳至
              <input type="number" min={1} max={totalPages} value={jump} aria-label="跳转到页码"
                onChange={e => setJump(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter') goJump() }} />
              <button className="pg-btn" onClick={goJump}>跳转</button>
            </span>
          </div>
        </div>
      </div>

      <Modal title={editUser?.username ? '编辑账号' : '新增账号'} open={!!editUser}
        onCancel={() => setEditUser(null)} onOk={() => void saveEdit()} okText="保存">
        {err && <div className="msg err show">{err}</div>}
        <div className="field"><label>用户名</label>
          <input value={editUser?.username || ''} disabled={!!editUser?.username}
            onChange={e => setEditUser(u => u ? { ...u, username: e.target.value } : u)} /></div>
        <div className="field"><label>显示名</label>
          <input value={form.displayName} onChange={e => setForm(f => ({ ...f, displayName: e.target.value }))} /></div>
        <div className="field"><label>角色（逗号分隔）</label>
          <input value={form.groups} onChange={e => setForm(f => ({ ...f, groups: e.target.value }))} /></div>
        <div className="field"><label className="checkbox"><input type="checkbox" checked={form.enabled}
          onChange={e => setForm(f => ({ ...f, enabled: e.target.checked }))} />启用</label></div>
      </Modal>

      <Modal title={'重置密码 · ' + (pwdUser?.username || '')} open={!!pwdUser}
        onCancel={() => setPwdUser(null)} onOk={() => void savePwd()} okText="确认重置">
        {err && <div className="msg err show">{err}</div>}
        <div className="field"><label>新密码</label>
          <input type="password" value={newPwd} onChange={e => setNewPwd(e.target.value)} placeholder="至少 8 位" /></div>
      </Modal>
    </>
  )
}
