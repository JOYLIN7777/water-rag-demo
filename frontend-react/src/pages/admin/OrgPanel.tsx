/** 后台-组织架构：DOM/class 与旧版 admin.html org 面板 1:1（单表树形 + 三档弹框） */
import { useEffect, useMemo, useState } from 'react'
import { message, TreeSelect } from 'antd'
import WbModal from '@/components/WbModal'
import { infoDialog } from '@/lib/ui'
import { http } from '@/api/http'
import { useAuthStore } from '@/store/auth'
import '@/styles/admin.css'

interface Org { key: string; name: string; parentKey?: string; orderNum?: number; members?: number; membersAll?: number }
interface OrgTreeNode { value: string; title: string; children?: OrgTreeNode[] }

/** 组织名转英文标识：含英文直接取英文，中文按常用字拼音拼，猜不出回落 org */
const PINYIN: Record<string, string> = {
  水: 'shui', 利: 'li', 中: 'zhong', 心: 'xin', 开: 'kai', 发: 'fa', 部: 'bu', 业: 'ye', 务: 'wu',
  财: 'cai', 应: 'ying', 急: 'ji', 处: 'chu', 审: 'shen', 计: 'ji', 管: 'guan', 理: 'li', 信: 'xin',
  息: 'xi', 技: 'ji', 调: 'diao', 度: 'du', 运: 'yun', 行: 'xing', 维: 'wei', 护: 'hu', 研: 'yan',
  究: 'jiu', 院: 'yuan', 分: 'fen', 局: 'ju', 科: 'ke', 大: 'da', 队: 'dui', 站: 'zhan', 所: 'suo',
  组: 'zu', 办: 'ban', 公: 'gong', 室: 'shi', 主: 'zhu', 任: 'ren', 工: 'gong', 程: 'cheng', 检: 'jian',
  测: 'ce', 监: 'jian', 控: 'kong', 数: 'shu', 据: 'ju', 安: 'an', 广: 'guang', 东: 'dong', 南: 'nan',
  海: 'hai', 湖: 'hu', 北: 'bei', 江: 'jiang', 山: 'shan', 河: 'he', 总: 'zong', 司: 'si', 集: 'ji',
  团: 'tuan', 事: 'shi', 闸: 'zha', 门: 'men', 库: 'ku', 汛: 'xun', 防: 'fang', 洪: 'hong', 蓄: 'xu',
  泄: 'xie', 董: 'dong', 规: 'gui', 划: 'hua', 设: 'she', 建: 'jian', 质: 'zhi', 量: 'liang', 保: 'bao'
}

function slug(name: string): string {
  const s = String(name || '').trim()
  const ascii = (s.match(/[A-Za-z][A-Za-z0-9]*/g) || []).join('-').toLowerCase()
  const raw = ascii || Array.from(s).map(c => PINYIN[c] || '').filter(Boolean).join('')
  return raw.replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 24).replace(/-+$/, '') || 'org'
}

export default function OrgPanel() {
  const auth = useAuthStore()
  const [rows, setRows] = useState<Org[]>([])
  const [users, setUsers] = useState<Array<{ tenant?: string }>>([])
  const [kw, setKw] = useState('')
  const [folded, setFolded] = useState<Record<string, boolean>>({})
  const [err, setErr] = useState('')

  // 新建/编辑弹框
  const [creating, setCreating] = useState<{ parentKey: string } | null>(null)
  const [editing, setEditing] = useState<Org | null>(null)
  const [form, setForm] = useState({ name: '', key: '', parentKey: '' })
  const [keyTouched, setKeyTouched] = useState(false)
  const [formErr, setFormErr] = useState('')
  // 删除弹框
  const [deleting, setDeleting] = useState<Org | null>(null)
  const [moveTo, setMoveTo] = useState('')

  async function load() {
    try {
      const [o, u] = await Promise.all([
        http.get<Org[]>('/api/orgs'),
        http.get<Array<{ tenant?: string }>>('/api/admin/users').catch(() => [])
      ])
      setRows(Array.isArray(o) ? o : [])
      setUsers(Array.isArray(u) ? u : [])
      setErr('')
    } catch (e: any) { setErr(e?.message || '加载失败') }
  }
  useEffect(() => { void load() }, [])

  const byKey = (k: string) => rows.find(o => o.key === k) || null
  const children = (p?: string) => rows.filter(o => String(o.parentKey || '') === String(p || ''))
  const label = (k: string) => byKey(k)?.name || k
  const pathKeys = (k: string) => {
    const out: string[] = []; const seen = new Set<string>()
    let cur = k
    while (cur && !seen.has(cur)) { seen.add(cur); out.unshift(cur); cur = String(byKey(cur)?.parentKey || '') }
    return out
  }
  const crumb = (k: string) => k ? pathKeys(k).map(label).join(' / ') : '全部组织'
  const subtree = (k: string) => {
    const out = new Set<string>(); const q = [k]
    while (q.length) {
      const cur = q.shift()!
      if (!cur || out.has(cur)) continue
      out.add(cur); children(cur).forEach(c => q.push(c.key))
    }
    return out
  }
  const directMembers = (k: string) => users.filter(u => (u.tenant || 'default') === k).length

  /** 表体：按层级展平，折叠的父节点不再往下走 */
  const list = useMemo(() => {
    const out: Array<{ o: Org; d: number; idx: number; kids: number; last: boolean }> = []
    const walk = (parent: string, depth: number) => {
      const siblings = children(parent)
      siblings.forEach((o, i) => {
        const kids = children(o.key).length
        out.push({ o, d: depth, idx: i + 1, kids, last: i === siblings.length - 1 })
        if (kids && !folded[o.key]) walk(o.key, depth + 1)
      })
    }
    walk('', 0)
    const k = kw.trim().toLowerCase()
    return k ? out.filter(r => r.o.name.toLowerCase().includes(k) || String(r.o.key).toLowerCase().includes(k)) : out
  }, [rows, folded, kw])

  /** 上级组织用 TreeSelect：树形展示、可展开收起；编辑时排除自己与子孙（防环） */
  function parentTreeData(selfKey?: string) {
    const skip = selfKey ? subtree(selfKey) : new Set<string>()
    const build = (parent: string): OrgTreeNode[] =>
      children(parent).filter(o => !skip.has(o.key)).map(o => ({
        value: o.key, title: o.name, children: build(o.key)
      }))
    return [{ value: '', title: '（顶层，没有上级）' }, ...build('')]
  }

  function suggestKey(name: string) {
    const base = 'org-' + slug(name)
    const used = rows.map(o => o.key)
    if (!used.includes(base)) return base
    let k = base
    do { k = base + Math.random().toString(36).slice(2, 4).replace(/[^a-z]/g, 'a') } while (used.includes(k))
    return k
  }

  function openCreate(parentKey: string) {
    setCreating({ parentKey })
    setForm({ name: '', key: '', parentKey })
    setKeyTouched(false); setFormErr('')
  }
  function openEdit(o: Org) {
    setEditing(o)
    setForm({ name: o.name, key: o.key, parentKey: String(o.parentKey || '') })
    setFormErr('')
  }

  async function saveCreate() {
    const name = form.name.trim()
    if (name.length < 2) { setFormErr('组织名称至少 2 个字'); return }
    const key = form.key.trim() || suggestKey(name)
    try {
      await http.post('/api/admin/orgs', { name, key, parentKey: form.parentKey })
      const pk = form.parentKey
      setCreating(null)
      if (pk) setFolded(f => ({ ...f, [pk]: false }))
      message.success('组织已创建')
      await load()
    } catch (e: any) { setFormErr(e?.message || '创建失败') }
  }

  async function saveEdit() {
    if (!editing) return
    const name = form.name.trim()
    if (name.length < 2) { setFormErr('组织名称至少 2 个字'); return }
    try {
      await http.put('/api/admin/orgs/' + encodeURIComponent(editing.key), { name, parentKey: form.parentKey })
      setEditing(null); message.success('组织已更新'); await load()
    } catch (e: any) { setFormErr(e?.message || '保存失败') }
  }

  function askDelete(o: Org) {
    const kids = children(o.key).length
    if (kids) {
      infoDialog({
        title: '不能删除',
        content: <p style={{ margin: 0 }}>「{o.name}」下还有 <b>{kids}</b> 个子组织。子组织不会跟着一起删（删了就成树上点不到的孤儿），请先把子组织挪到别处或删掉，再删本组织。</p>
      })
      return
    }
    if (rows.length <= 1) {
      infoDialog({ title: '不能删除', content: <p style={{ margin: 0 }}>这是最后一个组织。账号必须有归属单位，至少要保留一个。</p> })
      return
    }
    const targets = rows.filter(r => !subtree(o.key).has(r.key))
    setMoveTo(targets[0]?.key || '')
    setDeleting(o)
  }

  async function doDelete() {
    if (!deleting) return
    if (!moveTo) { message.error('请选择数据要迁往的组织'); return }
    try {
      const j = await http.delete<{ message?: string }>('/api/admin/orgs/' + encodeURIComponent(deleting.key) + '?moveTo=' + encodeURIComponent(moveTo))
      setDeleting(null)
      message.success(j?.message || '组织已删除')
      await load()
    } catch (e: any) { message.error(e?.message || '删除失败') }
  }

  function toggleAll() {
    const anyOpen = rows.some(o => children(o.key).length && !folded[o.key])
    const next: Record<string, boolean> = {}
    rows.forEach(o => { next[o.key] = anyOpen })
    setFolded(next)
  }

  const canWrite = auth.has('user:write')

  return (
    <>
      <div className="org-page-head">
        <div><h1 className="page-title">组织架构管理中心</h1>
          <p className="page-desc">以树形结构维护部门层级；可直接编辑任意层级，支持调整上级、排序与子部门。</p></div>
      </div>
      {err && <div className="msg err show">{err}</div>}
      <div className="org-toolbar">
        <input className="org-search" value={kw} onChange={e => setKw(e.target.value)} placeholder="搜索部门名称 / 编码" />
        <button className="btn btn-sm" onClick={toggleAll} title="展开或收起全部层级">展开/收起</button>
        <button className="btn btn-sm" onClick={() => void load()}>刷新</button>
        {canWrite && <button className="btn btn-sm btn-primary" onClick={() => openCreate('')}>＋ 新建部门</button>}
      </div>
      <div className="card org-table-card">
        <table className="data-table org-tree-table">
          <thead><tr>
            <th>部门 / 组织架构名称</th><th>部门编码（Key）</th><th>排序号</th><th>状态</th><th>操作</th>
          </tr></thead>
          <tbody>
            {list.length === 0
              ? <tr><td colSpan={5} className="empty">{rows.length ? '没有匹配的组织' : '暂无组织，先建一个吧'}</td></tr>
              : list.map(({ o, d, idx, kids, last }) => <tr key={o.key} className={`org-row-depth-${Math.min(d, 3)}`}>
                <td>
                  <span className={`org-node org-depth-${Math.min(d, 3)}`} style={{ paddingLeft: d * 22 }}>
                    <span className="org-branch">{last ? '└─' : '├─'}</span>
                    <span className={'org-caret' + (kids ? '' : ' leaf')}
                      onClick={() => kids && setFolded(f => ({ ...f, [o.key]: !f[o.key] }))}>
                      {kids ? (folded[o.key] ? '▸' : '▾') : '▸'}
                    </span>
                    <span className="org-ic">{d === 0 ? '▣' : d === 1 ? '▤' : '▪'}</span>
                    <span className="org-name">{o.name}</span>
                    <span className="org-level">L{d + 1}</span>
                  </span>
                </td>
                <td>{o.key}</td>
                <td>{idx}</td>
                <td><span className="tag ok">正常</span></td>
                <td>
                  {canWrite ? <>
                    <button className="op-btn" onClick={() => openCreate(o.key)}>＋子部门</button>
                    <button className="op-btn" onClick={() => openEdit(o)}>编辑</button>
                    <button className="op-btn danger" onClick={() => askDelete(o)}>删除</button>
                  </> : <span className="op-note">只读</span>}
                </td>
              </tr>)}
          </tbody>
        </table>
      </div>

      {/* 新建组织 */}
      <WbModal title={creating?.parentKey ? `新建子组织 · ${label(creating.parentKey)}` : '新建组织'} open={!!creating}
        okText="创建" cancelText="取消" onOk={() => void saveCreate()} onCancel={() => setCreating(null)}>
        {formErr && <div className="msg err show">{formErr}</div>}
        <div className="form-note">组织是数据隔离的边界：新组织下的成员只能看到本组织的文档、会话与统计。层级只决定归属与展示，不会让下级自动继承上级的文档。</div>
        <div className="field"><label>上级组织</label>
          <TreeSelect style={{ width: '100%' }} value={form.parentKey}
            treeData={parentTreeData()} treeDefaultExpandAll placeholder="选择上级组织"
            onChange={v => setForm(f => ({ ...f, parentKey: String(v || '') }))} /></div>
        <div className="field"><label>组织名称</label>
          <input value={form.name} autoComplete="off" placeholder="例如：水利业务部"
            onChange={e => {
              const name = e.target.value
              setForm(f => ({ ...f, name, key: keyTouched ? f.key : suggestKey(name) }))
            }} /></div>
        <div className="field"><label>组织标识</label>
          <input value={form.key} autoComplete="off" placeholder="按名称自动生成，可修改"
            onChange={e => { setKeyTouched(true); setForm(f => ({ ...f, key: e.target.value })) }} />
          <div className="form-hint">按组织名称的英文名生成（如 org-shuili），重名时自动追加两个随机字母。创建后不可修改。</div></div>
      </WbModal>

      {/* 编辑组织 */}
      <WbModal title={'编辑组织 · ' + (editing?.name || '')} open={!!editing}
        okText="保存" cancelText="取消" onOk={() => void saveEdit()} onCancel={() => setEditing(null)}>
        {formErr && <div className="msg err show">{formErr}</div>}
        <div className="field"><label>组织名称</label>
          <input value={form.name} autoComplete="off" onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />
          <div className="form-hint">只改显示名，组织标识「{editing?.key}」不变——它是文档与会话的归属键，改键等于把数据搬走。</div></div>
        <div className="field"><label>上级组织</label>
          <TreeSelect style={{ width: '100%' }} value={form.parentKey}
            treeData={parentTreeData(editing?.key)} treeDefaultExpandAll placeholder="选择上级组织"
            onChange={v => setForm(f => ({ ...f, parentKey: String(v || '') }))} />
          <div className="form-hint">调整上级等于把整棵子树挪到别处，已有数据不受影响；不能挪到自己的下级里。</div></div>
      </WbModal>

      {/* 删除组织：必须先选数据迁移目标 */}
      <WbModal title="删除组织" open={!!deleting} okText="迁移并删除" cancelText="取消"
        okButtonProps={{ danger: true }} onOk={() => void doDelete()} onCancel={() => setDeleting(null)}>
        <p style={{ margin: '0 0 12px' }}>删除「{deleting?.name}」前，把它名下的账号、知识库文档与会话一并迁到另一个组织。
          只删组织不迁数据，文档会留在已删除的组织上成为孤儿。</p>
        <p style={{ margin: '0 0 12px' }}>当前直属成员 <b>{deleting ? directMembers(deleting.key) : 0}</b> 个。</p>
        <div className="field"><label>把数据迁到</label>
          <TreeSelect style={{ width: '100%' }} value={moveTo || undefined}
            treeData={rows.filter(r => !subtree(deleting?.key || '').has(r.key))
              .map(r => ({ value: r.key, title: crumb(r.key) }))}
            treeDefaultExpandAll placeholder="选择迁移目标组织"
            onChange={v => setMoveTo(String(v || ''))} />
          <div className="form-hint">迁完即删除「{deleting?.name}」，组织标识 {deleting?.key} 作废。</div></div>
      </WbModal>
    </>
  )
}
