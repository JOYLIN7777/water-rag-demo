/** 后台-角色定义：DOM/class 与旧版 admin.html roles 面板 1:1（含若依风格权限勾选树） */
import { useEffect, useMemo, useState } from 'react'
import { message } from 'antd'
import WbModal from '@/components/WbModal'
import PermTreeEditor, { type PermNode } from '@/components/PermTreeEditor'
import { confirmDialog } from '@/lib/ui'
import { http } from '@/api/http'
import { useAuthStore } from '@/store/auth'
import '@/styles/admin.css'

interface Role { role: string; name: string; permissions: string[]; builtin?: boolean; description?: string }
interface RoleUser { username: string; displayName?: string; groups?: string[] }

export default function RolesPanel() {
  const auth = useAuthStore()
  const [roles, setRoles] = useState<Role[]>([])
  const [perms, setPerms] = useState<PermNode[]>([])
  const [users, setUsers] = useState<RoleUser[]>([])
  const [kw, setKw] = useState('')
  const [err, setErr] = useState('')

  // 新增角色
  const [creating, setCreating] = useState(false)
  const [roleName, setRoleName] = useState('')
  const [roleCode, setRoleCode] = useState('')
  const [picked, setPicked] = useState<string[]>([])
  // 编辑权限
  const [editing, setEditing] = useState<Role | null>(null)
  const [editPicked, setEditPicked] = useState<string[]>([])
  // 查看权限明细
  const [viewing, setViewing] = useState<Role | null>(null)
  const [formErr, setFormErr] = useState('')

  async function load() {
    try {
      const [r, p, u] = await Promise.all([
        http.get<Role[]>('/api/admin/roles'),
        http.get<PermNode[]>('/api/admin/perms'),
        http.get<RoleUser[]>('/api/admin/users').catch(() => [] as RoleUser[])
      ])
      setRoles(Array.isArray(r) ? r : [])
      setPerms(Array.isArray(p) ? p : [])
      setUsers(Array.isArray(u) ? u : [])
      setErr('')
    } catch (e: any) { setErr(e?.message || '加载失败') }
  }
  useEffect(() => { void load() }, [])

  const list = useMemo(() => {
    const k = kw.trim().toLowerCase()
    return roles.filter(r => !k || (r.role + ' ' + auth.groupLabel(r.role)).toLowerCase().includes(k))
      // 接口顺序做兜底：按角色码稳定排序，admin 固定放最后
      .sort((a, b) => a.role === 'admin' ? 1 : b.role === 'admin' ? -1 : a.role.localeCompare(b.role))
  }, [roles, kw, auth])

  const permDesc = (code: string) => perms.find(p => p.code === code)?.description || '系统内置权限'

  function openCreate() {
    setCreating(true); setRoleName(''); setRoleCode(''); setPicked([]); setFormErr('')
  }

  async function saveCreate() {
    const label = roleName.trim()
    if (!label) { setFormErr('请填写角色名称（中文显示名）'); return }
    const code = roleCode.trim().toLowerCase()
    if (!code) { setFormErr('请填写角色标识（英文，例如 operator）'); return }
    if (!/^[a-z][a-z0-9_-]{1,31}$/.test(code)) {
      setFormErr(`角色标识「${code}」不符合要求：需以英文小写字母开头，2-32 位，只能含小写字母、数字、下划线、短横线。`)
      return
    }
    if (!picked.length) { setFormErr('请至少勾选一项权限，否则该角色登录后什么都做不了'); return }
    try {
      await http.put('/api/admin/roles/' + encodeURIComponent(code) + '/permissions', { permissions: picked, roleName: label })
      setCreating(false)
      message.success('角色已创建，可在用户管理中分配')
      await load()
      void auth.loadPermissions()
    } catch (e: any) { setFormErr(e?.message || '创建失败') }
  }

  function openEdit(r: Role) {
    if (r.role === 'admin') { message.error('管理员拥有全部权限，不可修改'); return }
    setEditing(r); setEditPicked(r.permissions || []); setFormErr('')
  }

  async function saveEdit() {
    if (!editing) return
    try {
      await http.put('/api/admin/roles/' + encodeURIComponent(editing.role) + '/permissions', { permissions: editPicked })
      setEditing(null)
      message.success('角色权限已保存并实时生效')
      await load()
      void auth.loadPermissions()
    } catch (e: any) { setFormErr(e?.message || '保存失败') }
  }

  async function remove(r: Role) {
    if (r.role === 'admin') return
    const ok = await confirmDialog({
      title: '删除角色定义',
      danger: true,
      okText: '确认删除',
      content: <p style={{ margin: 0 }}>删除「{auth.groupLabel(r.role)}」的自定义权限配置吗？删除后：内置角色回落系统默认权限，自定义角色将不再拥有任何权限（已分配该角色的用户立即受限）。</p>
    })
    if (!ok) return
    try {
      const j = await http.delete<{ message?: string }>('/api/admin/roles/' + encodeURIComponent(r.role))
      message.success(j?.message || '已删除')
      await load()
      void auth.loadPermissions()
    } catch (e: any) { message.error(e?.message || '删除失败') }
  }

  return (
    <>
      <div className="page-head"><h1 className="page-title">角色定义</h1>
        <p className="page-desc">为不同岗位分配角色，角色决定可以使用哪些功能。</p></div>
      {err && <div className="msg err show">{err}</div>}
      <div className="card" style={{ padding: 0, border: 0, boxShadow: 'none' }}>
        <div className="toolbar" style={{ background: '#fff', border: '1px solid var(--line)', borderBottom: 0, borderRadius: 'var(--radius) var(--radius) 0 0' }}>
          <input className="kw" value={kw} onChange={e => setKw(e.target.value)} placeholder="搜索角色名称 / 标识…" />
          <span className="grow" />
          <button className="btn btn-sm" onClick={() => void load()}>刷新</button>
          <button className="btn btn-sm btn-primary" onClick={openCreate}>＋ 新增角色</button>
        </div>
        <div className="table-wrap" style={{ border: '1px solid var(--line)', borderRadius: '0 0 var(--radius) var(--radius)', background: '#fff' }}>
          <table className="data-table">
            <thead><tr>
              <th className="row-idx">序号</th><th>角色名称</th><th>角色标识</th><th>数据范围</th>
              <th>权限数</th><th>关联用户</th><th>角色权限明细</th><th>操作</th>
            </tr></thead>
            <tbody>
              {list.length === 0
                ? <tr><td colSpan={8} className="empty">{roles.length ? '没有匹配的角色' : '暂无角色定义'}</td></tr>
                : list.map((r, i) => {
                  const ps = r.permissions || []
                  const scope = ps.includes('admin:access') ? '全部组织' : '本组织'
                  const members = users.filter(u => (u.groups || []).includes(r.role))
                  return <tr key={r.role}>
                    <td className="row-idx">{i + 1}</td>
                    <td className="name">{auth.groupLabel(r.role)}</td>
                    <td><span className="mono" style={{ fontSize: 11 }}>{r.role}</span></td>
                    <td>{scope}</td>
                    <td>{ps.length}</td>
                    <td>
                      {members.length === 0 ? <span className="dim">—</span> : <>
                        {members.slice(0, 2).map(u => <span className="tag-role" key={u.username}
                          title={u.username}>{u.displayName || u.username}</span>)}
                        {members.length > 2 && <span className="tag"
                          title={members.map(u => u.displayName || u.username).join('、')}>+{members.length - 2}</span>}
                      </>}
                    </td>
                    <td>
                      {ps.slice(0, 3).map(p => <span className="tag" key={p} style={{ marginRight: 4 }}>{p}</span>)}
                      {ps.length > 3 && <span className="tag off">+{ps.length - 3}</span>}
                    </td>
                    <td>
                      <button className="op-btn" onClick={() => setViewing(r)}>查看</button>
                      {r.role !== 'admin' && <>
                        <button className="op-btn" onClick={() => openEdit(r)}>编辑权限</button>
                        <button className="op-btn danger" onClick={() => void remove(r)}>删除</button>
                      </>}
                    </td>
                  </tr>
                })}
            </tbody>
          </table>
        </div>
      </div>

      {/* 新增角色：宽弹框 + 权限树 */}
      <WbModal size="wide" title="新增角色" open={creating} okText="创建" cancelText="取消"
        onOk={() => void saveCreate()} onCancel={() => setCreating(false)}>
        {formErr && <div className="msg err show">{formErr}</div>}
        <div className="form-note">角色标识是权限系统的内部键，只能用<b>英文小写字母</b>开头，可含数字、下划线、短横线（例：<code>operator</code>、<code>flood_viewer</code>）。新建后在用户管理的「角色 / 用户组」里分配。</div>
        <div className="field"><label>角色名称（中文，显示在角色列表与用户管理）</label>
          <input value={roleName} maxLength={32} autoComplete="off" placeholder="例如：防汛值班员"
            onChange={e => setRoleName(e.target.value)} /></div>
        <div className="field"><label>角色标识（英文，赋给用户后生效）</label>
          <input value={roleCode} maxLength={32} autoComplete="off" placeholder="例如：operator"
            onChange={e => setRoleCode(e.target.value)} /></div>
        <div className="field"><label>默认权限</label>
          <div style={{ paddingTop: 4 }}>
            <PermTreeEditor perms={perms} value={picked} onChange={setPicked} />
          </div></div>
      </WbModal>

      {/* 编辑角色权限 */}
      <WbModal size="wide" title={'编辑角色权限 · ' + (editing ? auth.groupLabel(editing.role) : '')} open={!!editing}
        okText="保存并生效" cancelText="取消" onOk={() => void saveEdit()} onCancel={() => setEditing(null)}>
        {formErr && <div className="msg err show">{formErr}</div>}
        <p style={{ margin: '0 0 4px', color: 'var(--muted)', fontSize: 12.5 }}>保存后立即对新请求生效，无需重启。未勾选的权限一律拒绝。</p>
        <div style={{ paddingTop: 4 }}>
          <PermTreeEditor perms={perms} value={editPicked} onChange={setEditPicked} />
        </div>
      </WbModal>

      {/* 权限明细 */}
      <WbModal title={(viewing ? auth.groupLabel(viewing.role) : '') + ' · 权限明细'} open={!!viewing}
        okText="关闭" cancelText={null} onOk={() => setViewing(null)} onCancel={() => setViewing(null)}>
        {(() => {
          const members = users.filter(u => (u.groups || []).includes(viewing?.role || ''))
          return <div className="role-view-members">
            <div className="form-hint" style={{ marginBottom: 8 }}>关联用户（{members.length} 人）</div>
            <div className="perm-grid2">
              {members.length
                ? members.map(u => <div className="perm-pill" key={u.username}>
                  <b>{u.displayName || u.username}</b>{u.username}
                </div>)
                : <div className="dim">暂无用户分配该角色</div>}
            </div>
            <div className="form-hint" style={{ marginTop: 18, marginBottom: 8 }}>权限明细</div>
          </div>
        })()}
        <div className="perm-grid2">
          {(viewing?.permissions || []).map(p => <div className="perm-pill" key={p}>
            <b>{p}</b>{permDesc(p)}
          </div>)}
        </div>
      </WbModal>
    </>
  )
}
