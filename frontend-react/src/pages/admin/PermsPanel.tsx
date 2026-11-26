/** 后台-菜单权限：DOM/class 与旧版 admin.html perms 面板 1:1（三级树 + 增删改） */
import { useEffect, useMemo, useState } from 'react'
import { message } from 'antd'
import WbModal from '@/components/WbModal'
import type { PermNode } from '@/components/PermTreeEditor'
import { confirmDialog } from '@/lib/ui'
import { http } from '@/api/http'
import '@/styles/admin.css'

interface Role { role: string; name: string; permissions: string[] }

const TYPE_CN: Record<string, string> = { directory: '目录', menu: '菜单', btn: '按钮' }
const TYPE_ICON: Record<string, string> = { directory: '▦', menu: '▤', btn: '◆' }
const lv = (t?: string) => 'perm-lv-' + (t || 'menu')

/** 常用业务动作：不在代码清单里、但企业后台普遍需要；点一下按当前上级前缀生成新码 */
const COMMON_ACTIONS: Array<[string, string]> = [
  ['select', '查询'], ['create', '新增'], ['edit', '编辑'], ['delete', '删除'],
  ['export', '导出'], ['import', '导入'], ['approve', '审批'], ['review', '复核'],
  ['report', '上报'], ['issue', '下达'], ['archive', '归档'], ['stats', '统计'],
  ['print', '打印'], ['download', '下载'], ['config', '配置'], ['reset', '重置']
]

type Form = {
  code: string; name: string; type: string; module: string
  desc: string; parentCode: string; routePath: string; orderNum: number
}
const EMPTY: Form = { code: '', name: '', type: 'menu', module: '', desc: '', parentCode: '', routePath: '', orderNum: 0 }

export default function PermsPanel() {
  const [rows, setRows] = useState<PermNode[]>([])
  const [roles, setRoles] = useState<Role[]>([])
  const [unused, setUnused] = useState<Array<{ code: string; name?: string }>>([])
  const [used, setUsed] = useState<Set<string>>(new Set())
  const [kw, setKw] = useState('')
  const [folded, setFolded] = useState<Set<string>>(new Set())
  const [err, setErr] = useState('')

  const [creating, setCreating] = useState(false)
  const [editing, setEditing] = useState<PermNode | null>(null)
  const [form, setForm] = useState<Form>(EMPTY)
  const [formErr, setFormErr] = useState('')
  const [viewRoles, setViewRoles] = useState<PermNode | null>(null)

  async function load() {
    try {
      const [p, r, u, uc] = await Promise.all([
        http.get<PermNode[]>('/api/admin/perms'),
        http.get<Role[]>('/api/admin/roles').catch(() => [] as Role[]),
        http.get<Array<{ code: string; name?: string }>>('/api/admin/perms/unused-codes').catch(() => []),
        http.get<string[]>('/api/admin/perms/used-codes').catch(() => [])
      ])
      setRows(Array.isArray(p) ? p : [])
      setRoles(Array.isArray(r) ? r : [])
      setUnused(Array.isArray(u) ? u : [])
      setUsed(new Set(Array.isArray(uc) ? uc : []))
      setErr('')
    } catch (e: any) { setErr(e?.message || '加载失败') }
  }
  useEffect(() => { void load() }, [])

  const byParent = useMemo(() => {
    const m: Record<string, PermNode[]> = {}
    rows.forEach(p => { const k = p.parentCode || ''; (m[k] = m[k] || []).push(p) })
    Object.keys(m).forEach(k => m[k].sort((a, b) => (a.orderNum || 0) - (b.orderNum || 0)))
    return m
  }, [rows])

  const match = (x: PermNode) => {
    const k = kw.trim().toLowerCase()
    if (!k) return true
    return [x.name, x.code, x.description, x.routePath].some(v => String(v || '').toLowerCase().includes(k))
  }

  /** 展平树（折叠的父节点不展开），旧版按 parentCode 组树 + 父节点不存在也提到根层 */
  const tree = useMemo(() => {
    const out: Array<{ node: PermNode; depth: number; kids: number }> = []
    const roots: PermNode[] = []
    rows.filter(r => !r.parentCode).forEach(r => { if (!roots.includes(r)) roots.push(r) })
    rows.forEach(r => { if (r.parentCode && !byParent[r.parentCode] && !roots.includes(r)) roots.push(r) })
    const walk = (node: PermNode, depth: number) => {
      const kids = (byParent[node.code] || []).filter(match)
      out.push({ node, depth, kids: kids.length })
      if (!folded.has(node.code)) kids.forEach(k => walk(k, depth + 1))
    }
    roots.filter(match).forEach(r => walk(r, 0))
    return out
  }, [rows, byParent, folded, kw])

  const parentOptions = (selected: string) => [
    <option value="" key="top">（顶级）</option>,
    ...rows.filter(x => x.nodeType !== 'btn').map(x =>
      <option value={x.code} key={x.code}>{x.name}（{x.code}）</option>)
  ]

  function openCreate(presetParent = '') {
    const prefix = presetParent.includes(':') ? presetParent.slice(0, presetParent.indexOf(':') + 1) : ''
    setCreating(true)
    setForm({ ...EMPTY, parentCode: presetParent, code: prefix, module: prefix ? prefix.slice(0, -1) : '' })
    setFormErr('')
  }
  function openEdit(x: PermNode) {
    setEditing(x)
    setForm({
      code: x.code, name: x.name, type: x.nodeType || 'menu', module: '',
      desc: x.description || '', parentCode: x.parentCode || '',
      routePath: x.routePath || '', orderNum: x.orderNum || 0
    })
    setFormErr('')
  }

  /** 权限码实时提示：撞已有节点 / 代码里没人用 都要说清楚 */
  function codeHint(): { text: string; cls: string; clash?: PermNode } {
    const code = form.code.trim()
    if (!code) return { text: '格式：小写模块:动作，例如 report:export', cls: 'form-hint' }
    const hit = rows.find(x => x.code === code && x.code !== editing?.code)
    if (hit) return { text: `⚠ 该权限码已存在（${hit.builtin ? '系统清单节点' : '自定义节点'}：${hit.name}），请换一个动作词`, cls: 'form-hint err', clash: hit }
    if (used.size && !used.has(code)) return { text: '⚠ 此权限码当前没有任何按钮或接口使用，建了暂时不会控制任何功能；请先在前台按钮写 data-perm 或后端接口加 @RequirePermission', cls: 'form-hint err' }
    return { text: '✓ 该权限码已在代码中使用，创建后立即生效', cls: 'form-hint ok' }
  }

  async function saveCreate() {
    const f = form
    if (f.type !== 'directory' && !/^[a-z][a-z0-9_-]*:[a-z][a-z0-9_-]*$/.test(f.code.trim())) {
      setFormErr('权限标识需为小写「模块:动作」格式，例如 report:export'); return
    }
    if (f.type === 'directory' && !/^[a-z][a-z0-9_-]*:(root|dir)$/.test(f.code.trim())) {
      setFormErr('目录标识格式为「模块:root」，例如 system:root'); return
    }
    if (!f.name.trim()) { setFormErr('请填写节点名称'); return }
    const clash = codeHint().clash
    if (clash) { setFormErr(`权限码「${f.code}」已被占用（${clash.builtin ? '系统清单节点' : '自定义节点'}），请换一个动作词`); return }
    try {
      await http.post('/api/admin/perms', {
        code: f.code.trim(), name: f.name.trim(), type: f.type, module: f.module.trim(),
        desc: f.desc.trim(), parentCode: f.parentCode.trim(), routePath: f.routePath.trim(), orderNum: Number(f.orderNum || 0)
      })
      setCreating(false); message.success('节点已创建'); await load()
    } catch (e: any) { setFormErr(e?.message || '创建失败') }
  }

  async function saveEdit() {
    if (!editing) return
    if (!form.name.trim()) { setFormErr('请填写节点名称'); return }
    try {
      await http.put('/api/admin/perms/' + encodeURIComponent(editing.code), {
        name: form.name.trim(), type: form.type, module: form.module.trim(),
        desc: form.desc.trim(), parentCode: form.parentCode.trim(), routePath: form.routePath.trim(), orderNum: Number(form.orderNum || 0)
      })
      setEditing(null); message.success('已保存'); await load()
    } catch (e: any) { setFormErr(e?.message || '保存失败') }
  }

  async function remove(x: PermNode) {
    const ok = await confirmDialog({
      title: '删除权限节点', danger: true, okText: '确认删除',
      content: <p style={{ margin: 0 }}>确定删除权限节点「{x.name}」（{x.code}）吗？该权限会同时从所有角色的配置里移除。</p>
    })
    if (!ok) return
    try {
      const j = await http.delete<{ message?: string }>('/api/admin/perms/' + encodeURIComponent(x.code))
      message.success(j?.message || '已删除'); await load()
    } catch (e: any) { message.error(e?.message || '删除失败') }
  }

  function toggleAll(expand: boolean) {
    if (expand) { setFolded(new Set()); return }
    setFolded(new Set(rows.filter(x => rows.some(c => c.parentCode === x.code)).map(x => x.code)))
  }

  const hint = creating ? codeHint() : null
  const quick: Array<{ code: string; name?: string }> = unused.length
    ? unused.slice(0, 8)
    : Array.from(used).filter(c => !rows.some(r => r.code === c)).slice(0, 8).map(c => ({ code: c }))

  function formFields(isEdit: boolean) {
    return <>
      {formErr && <div className="msg err show">{formErr}</div>}
      {isEdit
        ? <div className="field"><label>权限标识（不可修改）</label><input value={form.code} disabled /></div>
        : null}
      <div className="field"><label>上级节点</label>
        <select value={form.parentCode} onChange={e => {
          const parentCode = e.target.value
          const prefix = parentCode.includes(':') ? parentCode.slice(0, parentCode.indexOf(':') + 1) : ''
          setForm(f => ({
            ...f, parentCode,
            module: f.module || (prefix ? prefix.slice(0, -1) : ''),
            code: isEdit ? f.code : (f.code || prefix)
          }))
        }}>{parentOptions(form.parentCode)}</select></div>
      <div className="field"><label>节点类型</label>
        <select value={form.type} onChange={e => setForm(f => ({ ...f, type: e.target.value }))}>
          <option value="directory">目录</option><option value="menu">菜单</option><option value="btn">按钮</option>
        </select></div>
      <div className="field"><label>节点名称</label>
        <input value={form.name} autoComplete="off" placeholder="例如：导出报表"
          onChange={e => setForm(f => ({ ...f, name: e.target.value }))} /></div>
      <div className="field"><label>所属模块</label>
        <input value={form.module} autoComplete="off" placeholder="例如：报表中心 / report"
          onChange={e => setForm(f => ({ ...f, module: e.target.value }))} /></div>
      {!isEdit && <>
        <div className="field"><label>权限标识（快捷生成）</label>
          {quick.length > 0 && <div className="type-quick">
            {quick.map(q => <button type="button" key={q.code}
              onClick={() => setForm(f => ({ ...f, code: q.code, name: q.name || f.name, module: q.code.split(':')[0] }))}>
              {q.code}<span className="mono" style={{ fontSize: 10, marginLeft: 4 }}>{q.name || '代码在用'}</span>
            </button>)}
          </div>}
          <input value={form.code} autoComplete="off" placeholder="例如：report:export" style={{ marginTop: 6 }}
            onChange={e => setForm(f => ({ ...f, code: e.target.value }))} />
          {hint && <div className={hint.cls}>{hint.text}</div>}</div>
      </>}
      {form.type !== 'btn' &&
        <div className="field"><label>路由路径</label>
          <input value={form.routePath} autoComplete="off" placeholder="例如：/admin/reports"
            onChange={e => setForm(f => ({ ...f, routePath: e.target.value }))} /></div>}
      <div className="field"><label>显示顺序</label>
        <input type="number" value={form.orderNum}
          onChange={e => setForm(f => ({ ...f, orderNum: Number(e.target.value) }))} /></div>
      <div className="field"><label>备注说明</label>
        <input value={form.desc} autoComplete="off" placeholder="一句话描述用途"
          onChange={e => setForm(f => ({ ...f, desc: e.target.value }))} /></div>
    </>
  }

  return (
    <>
      <div className="page-head"><h1 className="page-title">菜单权限</h1>
        <p className="page-desc">按模块展示权限节点与权限标识。支持新增自定义权限并分配给角色。</p></div>
      {err && <div className="msg err show">{err}</div>}
      <div className="card" style={{ padding: 0, border: 0, boxShadow: 'none' }}>
        <div className="toolbar" style={{ background: '#fff', border: '1px solid var(--line)', borderBottom: 0, borderRadius: 'var(--radius) var(--radius) 0 0' }}>
          <input className="kw" value={kw} onChange={e => setKw(e.target.value)} placeholder="查询菜单 / 按钮 / 权限标识…" />
          <span className="grow" />
          <button className="btn btn-sm" onClick={() => toggleAll(true)}>展开全部</button>
          <button className="btn btn-sm" onClick={() => toggleAll(false)}>收起全部</button>
          <button className="btn btn-sm btn-primary" onClick={() => openCreate()}>＋ 新增权限</button>
        </div>
        <div className="table-wrap" style={{ border: '1px solid var(--line)', borderRadius: '0 0 var(--radius) var(--radius)', background: '#fff' }}>
          <table className="data-table">
            <thead><tr>
              <th>菜单 / 权限名称</th><th>节点类型</th><th>权限标识</th><th>路由路径</th><th>排序</th><th>操作</th>
            </tr></thead>
            <tbody>
              {tree.length === 0
                ? <tr><td colSpan={6} className="empty">{rows.length ? '没有匹配的权限节点' : '暂无权限节点'}</td></tr>
                : tree.map(({ node, depth, kids }) => {
                  const typ = node.nodeType || 'menu'
                  return <tr key={node.code}>
                    <td className={'name perm-name ' + lv(typ)} style={{ paddingLeft: 16 + depth * 20 }}>
                      {kids
                        ? <span className="pg-caret" style={{ cursor: 'pointer' }}
                          onClick={() => setFolded(prev => {
                            const s = new Set(prev); s.has(node.code) ? s.delete(node.code) : s.add(node.code); return s
                          })}>{folded.has(node.code) ? '▸' : '▾'}</span>
                        : <span className="pg-caret" />}
                      <span className="pn-ico">{TYPE_ICON[typ] || '▤'}</span>{node.name}
                      {!node.builtin && <> <span className="tag">自定义</span></>}
                    </td>
                    <td><span className={'node-type ' + lv(typ)}>{TYPE_CN[typ] || '功能菜单'}</span></td>
                    <td><span className="mono" style={{ fontSize: 11 }}>{node.code}</span></td>
                    <td><span className="mono" style={{ fontSize: 11, color: 'var(--muted)' }}>{node.routePath || '—'}</span></td>
                    <td>{node.orderNum || 0}</td>
                    <td>
                      {typ !== 'btn' && <button className="op-btn" title="在此层级下新增子项" onClick={() => openCreate(node.code)}>＋子项</button>}
                      <button className="op-btn" onClick={() => setViewRoles(node)}>查看角色</button>
                      <button className="op-btn" onClick={() => openEdit(node)}>编辑</button>
                      {!node.builtin && <button className="op-btn danger" onClick={() => void remove(node)}>删除</button>}
                    </td>
                  </tr>
                })}
            </tbody>
          </table>
        </div>
      </div>

      <WbModal title={creating && form.parentCode ? `新增子项 · ${rows.find(x => x.code === form.parentCode)?.name || form.parentCode}` : '新增菜单 / 权限'}
        open={creating} okText="创建" cancelText="取消" onOk={() => void saveCreate()} onCancel={() => setCreating(false)}>
        {formFields(false)}
      </WbModal>

      <WbModal title={'编辑 · ' + (editing?.name || '')} open={!!editing} okText="保存" cancelText="取消"
        onOk={() => void saveEdit()} onCancel={() => setEditing(null)}>
        <p style={{ margin: '0 0 4px', color: 'var(--muted)', fontSize: 12.5 }}>
          {editing?.builtin ? '内置节点的权限标识不可修改。' : '保存后，已分配该权限的角色立即跟随更新。'}
        </p>
        {formFields(true)}
      </WbModal>

      <WbModal size="md" title={'拥有「' + (viewRoles?.name || '') + '」权限的角色'} open={!!viewRoles}
        okText="关闭" cancelText={null} onOk={() => setViewRoles(null)} onCancel={() => setViewRoles(null)}>
        {(() => {
          const hits = roles.filter(r => (r.permissions || []).includes(viewRoles?.code || ''))
          if (!hits.length) return <div className="empty-inline">还没有角色分配这个权限</div>
          return <div className="perm-grid2">
            {hits.map(r => <div className="perm-pill" key={r.role}><b>{r.name || r.role}</b>{r.role}</div>)}
          </div>
        })()}
      </WbModal>
    </>
  )
}
