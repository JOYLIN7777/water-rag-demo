/** 后台-用户管理：DOM/class 与旧版 admin.html users 面板 1:1（左树筛选 + 表格 + 账号表单） */
import { useEffect, useMemo, useState } from 'react'
import { message, TreeSelect } from 'antd'
import WbModal from '@/components/WbModal'
import Pager from '@/components/Pager'
import { confirmDialog } from '@/lib/ui'
import { http } from '@/api/http'
import { adminApi } from '@/api'
import { useAuthStore } from '@/store/auth'
import '@/styles/admin.css'

interface User { username: string; displayName?: string; groups?: string[]; tenant?: string; enabled?: boolean }
interface Org { key: string; name: string; parentKey?: string }
interface OrgTreeNode { value: string; title: string; children?: OrgTreeNode[] }

const PAGE_SIZE = 20

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
  const [err, setErr] = useState('')

  // 新增/编辑
  const [editing, setEditing] = useState<User | null>(null)
  const [isNew, setIsNew] = useState(false)
  const [form, setForm] = useState({ displayName: '', tenant: 'default', password: '', groups: [] as string[], enabled: true, gender: '', phone: '' })
  // 重置密码
  const [pwdUser, setPwdUser] = useState<User | null>(null)
  const [newPwd, setNewPwd] = useState('')
  const [formErr, setFormErr] = useState('')

  async function load() {
    try {
      const [u, o] = await Promise.all([
        http.get<User[]>('/api/admin/users'),
        http.get<Org[]>('/api/orgs').catch(() => [] as Org[])
      ])
      setUsers(Array.isArray(u) ? u : [])
      setOrgs(Array.isArray(o) ? o : [])
      setErr('')
    } catch (e: any) { setErr(e?.message || '加载失败') }
  }
  useEffect(() => { void load() }, [])

  const me = String(auth.user?.user || '').toLowerCase()
  const orgChildren = (p?: string) => orgs.filter(o => String(o.parentKey || '') === String(p || ''))
  const byKey = (k: string) => orgs.find(o => o.key === k) || null
  const orgPathKeys = (k: string) => {
    const out: string[] = []; const seen = new Set<string>()
    let cur = k
    while (cur && !seen.has(cur)) { seen.add(cur); out.unshift(cur); cur = String(byKey(cur)?.parentKey || '') }
    return out
  }
  /** 组织全路径：水利业务部 / 调度科 —— 有层级后同名科室才不会选错 */
  const orgCrumb = (k: string) => k ? orgPathKeys(k).map(x => byKey(x)?.name || x).join(' / ') : '全部组织'

  /** 组织节点旁显示该组织（含下级）的用户数，扫一眼就知道哪个部门有多少人 */
  const orgUserCount = (k: string) => {
    const keys = new Set<string>(), queue = [k]
    while (queue.length) {
      const cur = queue.shift()!
      if (keys.has(cur)) continue
      keys.add(cur)
      orgChildren(cur).forEach(o => queue.push(o.key))
    }
    return users.filter(u => keys.has(u.tenant || 'default')).length
  }

  /** 组织选择用 AntD TreeSelect：树形层级 + 可展开收起，不再是一排看不出关系的 option */
  const orgTreeData = (): OrgTreeNode[] => {
    const build = (parent: string): OrgTreeNode[] =>
      orgChildren(parent).map(o => ({ value: o.key, title: o.name, children: build(o.key) }))
    const nodes = build('')
    return nodes.length ? nodes : [{ value: 'default', title: '顶层组织' }]
  }

  const filtered = useMemo(() => {
    const k = kw.trim().toLowerCase()
    return users.filter(u => {
      if (k && ![u.username, u.displayName].some(v => String(v || '').toLowerCase().includes(k))) return false
      if (status === '1' && u.enabled === false) return false
      if (status === '0' && u.enabled !== false) return false
      if (role && !(u.groups || []).includes(role)) return false
      if (orgKey) {
        const keys = new Set<string>()
        const queue = [orgKey]
        while (queue.length) {
          const cur = queue.shift()!
          if (keys.has(cur)) continue
          keys.add(cur)
          orgChildren(cur).forEach(o => queue.push(o.key))
        }
        if (!keys.has(u.tenant || 'default')) return false
      }
      return true
    })
  }, [users, kw, status, role, orgKey, orgs])

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE))
  const curPage = Math.min(page, totalPages)
  const rows = filtered.slice((curPage - 1) * PAGE_SIZE, curPage * PAGE_SIZE)

  function resetFilter() { setKw(''); setStatus(''); setRole(''); setOrgKey(''); setPage(1) }

  async function toggleEnabled(u: User, enabled: boolean) {
    // 先本地乐观更新，失败再回滚：开关是高频操作，等接口回来才动会显得卡
    setUsers(list => list.map(x => x.username === u.username ? { ...x, enabled } : x))
    try {
      await adminApi.updateUser(u.username, { enabled })
      message.success(enabled ? '已启用' : '已停用')
    } catch (e: any) {
      setUsers(list => list.map(x => x.username === u.username ? { ...x, enabled: !enabled } : x))
      message.error(e?.message || '操作失败')
    }
  }

  function openCreate(presetTenant = '') {
    setIsNew(true)
    setEditing({ username: '', displayName: '', groups: ['viewer'], tenant: presetTenant || 'default', enabled: true })
    setForm({ displayName: '', tenant: presetTenant || 'default', password: '', groups: ['viewer'], enabled: true, gender: '', phone: '' })
    setFormErr('')
  }
  function openEdit(u: User) {
    setIsNew(false)
    setEditing(u)
    setForm({ displayName: u.displayName || '', tenant: u.tenant || 'default', password: '', groups: u.groups || [], enabled: u.enabled !== false, gender: (u as any).gender || '', phone: (u as any).phone || '' })
    setFormErr('')
  }

  const roleOptions = auth.roleMatrix.length
    ? auth.roleMatrix.map(r => [r.role, r.name || r.role] as [string, string])
    : Array.from(new Set(users.flatMap(u => u.groups || []))).map(g => [g, auth.groupLabel(g)] as [string, string])

  const self = !!editing && !isNew && String(editing.username).toLowerCase() === me

  async function save() {
    if (!editing) return
    if (isNew) {
      if (!editing.username) { setFormErr('请填写登录账号'); return }
      if (!/^[a-zA-Z0-9_-]{2,32}$/.test(editing.username)) { setFormErr('登录账号只能用 2-32 位英文字母、数字、下划线或短横线'); return }
      if (!form.password || form.password.length < 8) { setFormErr('初始密码至少 8 位'); return }
      try {
        await adminApi.createUser({
          username: editing.username, displayName: form.displayName, tenant: form.tenant,
          password: form.password, groups: form.groups, enabled: true,
          gender: form.gender, phone: form.phone
        })
        setEditing(null); message.success('账号已创建'); await load()
      } catch (e: any) { setFormErr(e?.message || '创建失败') }
      return
    }
    try {
      await adminApi.updateUser(editing.username, {
        displayName: form.displayName, groups: form.groups, tenant: form.tenant, enabled: form.enabled,
        gender: form.gender, phone: form.phone
      })
      setEditing(null); message.success('账号已更新'); await load()
    } catch (e: any) { setFormErr(e?.message || '保存失败') }
  }

  async function savePwd() {
    if (!pwdUser) return
    if (newPwd.length < 8) { setFormErr('新密码至少 8 位'); return }
    try {
      await adminApi.resetPassword(pwdUser.username, { password: newPwd })
      message.success('密码已重置'); setPwdUser(null); setNewPwd(''); setFormErr('')
    } catch (e: any) { setFormErr(e?.message || '重置失败') }
  }

  async function remove(u: User) {
    const ok = await confirmDialog({
      title: '删除账号', danger: true, okText: '确认删除',
      content: <p style={{ margin: 0 }}>确定删除账号「{u.displayName || u.username}」（{u.username}）吗？该账号将无法登录。</p>
    })
    if (!ok) return
    try { await adminApi.deleteUser(u.username); message.success('账号已删除'); await load() }
    catch (e: any) { message.error(e?.message || '删除失败') }
  }

  function exportUsers() {
    const header = ['账号', '显示名', '角色', '所属组织', '状态']
    const csv = '\uFEFF' + [header.join(','), ...filtered.map(u => [
      u.username, u.displayName || '', (u.groups || []).map(g => auth.groupLabel(g)).join(' / '),
      orgCrumb(u.tenant || 'default'), u.enabled === false ? '停用' : '正常'
    ].map(c => '"' + String(c).replace(/"/g, '""') + '"').join(','))].join('\n')
    const a = document.createElement('a')
    a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
    a.download = '用户列表.csv'; a.click(); URL.revokeObjectURL(a.href)
    message.success(`已导出 ${filtered.length} 个账号`)
  }

  function treeNodes() {
    const out: React.ReactNode[] = []
    const walk = (parent: string, depth: number) => {
      const siblings = orgChildren(parent)
      siblings.forEach((o, idx) => {
        const last = idx === siblings.length - 1
        const kids = orgChildren(o.key)
        const isFolded = folded.has(o.key)
        out.push(<div key={o.key}
          className={`tree-node tree-lv${Math.min(depth + 1, 3)}${orgKey === o.key ? ' active' : ''}`}
          data-depth={depth} style={{ paddingLeft: 10 + depth * 16, ['--d' as string]: depth }}
          onClick={() => { setOrgKey(o.key); setPage(1) }}>
          <span className={`caret${kids.length ? '' : ' ghost'}`}
            onClick={e => {
              if (!kids.length) return
              e.stopPropagation()
              setFolded(prev => { const s = new Set(prev); s.has(o.key) ? s.delete(o.key) : s.add(o.key); return s })
            }}>{kids.length ? (isFolded ? '▸' : '▾') : ''}</span>
          {/* 树形引导线：同级最后一个用 └─，其余 ├─，一眼看清从「全部组织」分出的层级 */}
          <span className="tree-branch">{last ? '└─' : '├─'}</span>
          <span className="tree-ic">{depth === 0 ? '▣' : depth === 1 ? '▤' : '▪'}</span>
          <span className="tn" title={orgPathKeys(o.key).join(' / ')}>
            <span className="tree-tier">L{depth + 1}</span>{o.name}
          </span>
          <span className="cnt" title={`${kids.length} 个下级组织 · ${orgUserCount(o.key)} 个用户`}>{orgUserCount(o.key)} 人</span>
        </div>)
        if (!isFolded) walk(o.key, depth + 1)
      })
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
          <button className="btn btn-sm btn-primary" onClick={() => openCreate(orgKey)}>＋ 新增账号</button>
        </div>
      </div>
      {err && <div className="msg err show">{err}</div>}
      <div className="workbench" style={{ minHeight: 'auto' }}>
        <div className="tree-pane">
          <div className="tree-head">组织架构</div>
          <div className={`tree-node${orgKey === '' ? ' active' : ''}`} style={{ paddingLeft: 10 }}
            onClick={() => { setOrgKey(''); setPage(1) }}>
            <span className="caret">▾</span><span className="tn">全部组织（{users.length}）</span>
          </div>
          <div id="uTree">{treeNodes()}</div>
        </div>
        <div className="data-pane">
          <div className="toolbar">
            <span style={{ fontSize: 12, color: 'var(--muted)' }} id="uFilterHint">
              共 {filtered.length} 个账号{orgKey ? `（组织：${orgCrumb(orgKey)}）` : ''}
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
                    <td className="row-idx">{(curPage - 1) * PAGE_SIZE + i + 1}</td>
                    <td><a className="name" href="javascript:void(0)" style={{ color: 'var(--primary)', textDecoration: 'none' }}
                      onClick={() => openEdit(u)}>{u.username}</a></td>
                    <td>{u.displayName || ''}</td>
                    <td>{(u.groups || []).map(g => <span className={'tag-role role-' + g} key={g}>{auth.groupLabel(g)}</span>)}</td>
                    <td>{orgCrumb(u.tenant || 'default')}</td>
                    <td>{isAdmin
                      ? <><span className="tag ok">正常</span></>
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
                        <button className="op-btn warn" onClick={() => { setPwdUser(u); setNewPwd(''); setFormErr('') }}>重置密码</button>
                        <button className="op-btn danger" onClick={() => void remove(u)}>删除</button>
                      </>}
                    </td>
                  </tr>
                })}
            </tbody>
          </table></div>
          <Pager id="uPager" total={filtered.length} page={curPage} size={PAGE_SIZE} onChange={setPage} />
        </div>
      </div>

      {/* 新增 / 编辑账号：字段与旧版 userForm 一致 */}
      <WbModal size="md" title={isNew ? (form.tenant && form.tenant !== 'default' ? `新增账号 · ${byKey(form.tenant)?.name || ''}` : '新增账号') : `编辑账号 · ${editing?.username || ''}`}
        open={!!editing} okText={isNew ? '创建' : '保存'} cancelText="取消"
        onOk={() => void save()} onCancel={() => setEditing(null)}>
        {formErr && <div className="msg err show">{formErr}</div>}
        {self && <div className="form-note">正在编辑当前登录的账号：为避免把自己锁在系统外，「账号正常」与「系统管理员」两项不可取消。显示名、所属组织与其它角色可正常修改。</div>}
        <div className="field"><label>登录账号</label>
          <input value={editing?.username || ''} disabled={!isNew} autoComplete="off" placeholder="英文字母或数字"
            onChange={e => setEditing(u => u ? { ...u, username: e.target.value } : u)} /></div>
        <div className="field"><label>显示名称</label>
          <input value={form.displayName} autoComplete="off" placeholder="例如：张工"
            onChange={e => setForm(f => ({ ...f, displayName: e.target.value }))} /></div>
        <div className="field"><label>所属组织</label>
          <TreeSelect style={{ width: '100%' }} value={form.tenant || undefined}
            treeData={orgTreeData()} treeDefaultExpandAll placeholder="选择所属组织"
            onChange={v => setForm(f => ({ ...f, tenant: String(v || 'default') }))} />
          <div className="form-hint">组织决定这个账号能看到哪些文档与会话：跨组织互相不可见。</div></div>
        {isNew && <div className="field"><label>初始密码</label>
          <input type="password" autoComplete="new-password" value={form.password} placeholder="至少 8 位，请自行设定"
            onChange={e => setForm(f => ({ ...f, password: e.target.value }))} /></div>}
        <div className="row">
          <div className="field"><label>手机号（可用于登录）</label>
            <input type="tel" value={form.phone} maxLength={11} placeholder="11 位手机号，可留空"
              onChange={e => setForm(f => ({ ...f, phone: e.target.value }))} /></div>
          <div className="field"><label>性别</label>
            <select value={form.gender} onChange={e => setForm(f => ({ ...f, gender: e.target.value }))}>
              <option value="">不公开</option><option value="男">男</option><option value="女">女</option>
            </select></div>
        </div>
        {isNew && <div className="field status-field">
          <label className="field-check"><input type="checkbox" checked={form.enabled}
            onChange={e => setForm(f => ({ ...f, enabled: e.target.checked }))} />账号正常</label>
          <div className="form-hint">取消勾选则创建后账号处于停用状态，无法登录。</div>
        </div>}
        <div className="field"><label>角色 / 用户组</label>
          <div className="check-grid">
            {roleOptions.map(([v, l]) => {
              const lock = self && v === 'admin'
              return <label key={v} title={lock ? '不能移除当前登录账号自己的管理员角色' : ''}>
                <input type="checkbox" checked={form.groups.includes(v)} disabled={lock}
                  onChange={e => setForm(f => ({
                    ...f,
                    groups: e.target.checked ? Array.from(new Set([...f.groups, v])) : f.groups.filter(x => x !== v)
                  }))} />
                <span>{l}</span></label>
            })}
          </div>
          {(() => {
            const legacy = form.groups.filter(g => !roleOptions.some(([v]) => v === g))
            return legacy.length
              ? <div className="form-hint">该账号仍保留已停用的角色：{legacy.map(g => auth.groupLabel(g)).join('、')}，如需移除请先到「角色定义」处理。</div>
              : null
          })()}
        </div>
        {!isNew && <div className="field status-field"><label>账号状态</label>
          <label className="field-check"><input type="checkbox" checked={form.enabled} disabled={self}
            onChange={e => setForm(f => ({ ...f, enabled: e.target.checked }))} />账号正常
</label>
          <div className="form-hint">停用后该账号无法登录。</div></div>}
      </WbModal>

      <WbModal title={'重置密码 · ' + (pwdUser?.username || '')} open={!!pwdUser}
        okText="确认重置" cancelText="取消" onOk={() => void savePwd()} onCancel={() => setPwdUser(null)}>
        {formErr && <div className="msg err show">{formErr}</div>}
        <p style={{ margin: '0 0 10px', color: 'var(--muted)', fontSize: 12.5 }}>重置后旧密码立即失效，新密码至少 8 位。</p>
        <div className="field"><label>新密码</label>
          <input type="password" autoComplete="new-password" value={newPwd} placeholder="至少 8 位"
            onChange={e => setNewPwd(e.target.value)} /></div>
      </WbModal>
    </>
  )
}
