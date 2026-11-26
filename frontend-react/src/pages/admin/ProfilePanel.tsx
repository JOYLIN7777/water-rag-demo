/** 后台-个人中心：与旧版 admin.html 的 data-panel="profile" 1:1（后台相对独立，不跳问答系统的个人中心） */
import { useEffect, useState } from 'react'
import { message, Modal } from 'antd'
import { http } from '@/api/http'
import { logout } from '@/api/auth'
import { useAuthStore } from '@/store/auth'
import { useChatStore } from '@/store/chat'
import { Avatar } from '@/components/Avatar'
import { useNavigate } from 'react-router-dom'
import type { PermissionNode } from '@/types'
import '@/styles/admin.css'

interface UserRow { username: string; displayName?: string; groups?: string[]; tenant?: string; enabled?: boolean }
interface OrgRow { key: string; name: string }

export default function ProfilePanel() {
  const auth = useAuthStore()
  const chat = useChatStore()
  const navigate = useNavigate()
  const [orgs, setOrgs] = useState<OrgRow[]>([])
  const [account, setAccount] = useState<UserRow | null>(null)
  const [nodes, setNodes] = useState<PermissionNode[]>([])

  useEffect(() => {
    void (async () => {
      const [o, u, p] = await Promise.all([
        http.get<OrgRow[]>('/api/orgs').catch(() => [] as OrgRow[]),
        http.get<UserRow[]>('/api/admin/users').catch(() => [] as UserRow[]),
        http.get<PermissionNode[]>('/api/admin/perms').catch(() => [] as PermissionNode[])
      ])
      setOrgs(Array.isArray(o) ? o : [])
      const me = (Array.isArray(u) ? u : []).find(x => x.username === (auth.user?.user || ''))
      setAccount(me || null)
      setNodes(Array.isArray(p) ? p : [])
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const me = auth.user
  const tl = (t?: string) => (t ? (orgs.find(o => o.key === t)?.name || t) : '顶层组织')
  const roles = account?.groups || me?.groups || []
  const name = account?.displayName || me?.displayName || me?.user || '—'
  const enabled = account ? account.enabled !== false : true
  const perms = auth.permissions || []
  const isAdmin = roles.includes('admin') || auth.isAdmin()

  const roleTags = roles.length
    ? roles.map(r => <span className={`tag-role ${r}`} key={r}>{auth.groupLabel(r)}</span>)
    : <span className="tag off">未分配</span>

  /** 权限按模块分组：与「菜单权限」页同一套模块名，方便对照 */
  const groups: Record<string, Array<{ code: string; name: string; desc: string }>> = {}
  for (const code of perms) {
    const hit = nodes.find(x => x.code === code)
    const mod = hit?.module || '其他'
    ;(groups[mod] = groups[mod] || []).push({ code, name: hit?.name || code, desc: hit?.description || '' })
  }
  const mods = Object.keys(groups).sort()

  async function doLogout() {
    const ok = await new Promise<boolean>(resolve => {
      Modal.confirm({
        title: '确认退出登录？',
        content: '退出后需要重新登录才能继续使用，确定现在退出吗？',
        okText: '退出登录', cancelText: '继续留在当前页面',
        onOk: () => resolve(true), onCancel: () => resolve(false)
      })
    })
    if (!ok) return
    await logout()
    auth.reset(); chat.reset()
    navigate('/login.html', { replace: true })
  }

  const infoRow = (k: string, v: React.ReactNode) => (
    <div className="info-row" key={k}><span className="ir-k">{k}</span><span className="ir-v">{v}</span></div>
  )

  return (
    <>
      <div className="page-head"><h1 className="page-title">个人中心</h1>
        <p className="page-desc">当前登录账号的身份、信息与权限范围。账号与角色由系统管理员在「用户管理」维护。</p></div>
      <div className="pf-grid">
        <aside className="pf-id">
          <div className="pf-cover" />
          <div className="pf-id-body">
            <Avatar name={name} size="xl" />
            <div className="pf-name">{name}</div>
            <div className="pf-account">@{me?.user || '—'}</div>
            <div className="pf-tags">{roleTags}</div>
            <div className="pf-meta">
              <div className="pf-meta-row"><span>所属组织</span><b>{tl(me?.tenant)}</b></div>
              <div className="pf-meta-row"><span>账号状态</span>
                <b style={{ color: enabled ? 'var(--ok)' : 'var(--danger)' }}>{enabled ? '正常' : '已停用'}</b></div>
              <div className="pf-meta-row"><span>权限数量</span><b>{perms.length} 项</b></div>
            </div>
            <button className="btn pf-logout" type="button" onClick={() => void doLogout()}>退出登录</button>
          </div>
        </aside>
        <div className="pf-main">
          <div className="card">
            <div className="card-head"><h3>账号信息</h3>
              <span className="pf-hint">密码以加盐哈希存储，任何接口都不返回原文</span></div>
            <div className="info-rows">
              {infoRow('登录账号', me?.user || '—')}
              {infoRow('显示名称', name)}
              {infoRow('所属组织', tl(me?.tenant))}
              {infoRow('角色 / 用户组', roleTags)}
              {infoRow('账号状态', <span className={`tag ${enabled ? 'ok' : 'off'}`}>{enabled ? '正常' : '已停用'}</span>)}
              {infoRow('数据范围', isAdmin ? '全部组织（租户内）' : '本组织 · 按角色可见')}
              {infoRow('权限数量', perms.length + ' 项')}
              {infoRow('认证方式', 'JWT · 访问令牌 30 分钟自动续期')}
            </div>
          </div>
          <div className="card">
            <div className="card-head"><h3>我的权限</h3><span className="tag">{perms.length} 项</span></div>
            <p className="sub" style={{ margin: '-6px 0 12px' }}>
              权限在服务端逐接口校验，前端只负责隐藏入口；未列出的操作一律拒绝。</p>
            {mods.length === 0
              ? <div className="empty-inline">暂无权限</div>
              : mods.map(m => (
                <div className="perm-mod" key={m}>
                  <div className="perm-mod-name">{m}</div>
                  <div className="perm-chips">
                    {groups[m].map(p => (
                      <span className="perm-chip" key={p.code} title={p.code + (p.desc ? ' — ' + p.desc : '')}>{p.name}</span>
                    ))}
                  </div>
                </div>
              ))}
          </div>
        </div>
      </div>
    </>
  )
}
