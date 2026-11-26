/** 全站侧栏：DOM/class 与旧版 renderSidebar 1:1 对齐 */
import { useEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Modal, message } from 'antd'
import { useAuthStore } from '@/store/auth'
import { useChatStore } from '@/store/chat'
import { isLoggedIn, logout, getUser } from '@/api/auth'
import { http } from '@/api/http'
import { Icon } from '@/components/Icon'
import { Avatar, fmtTime } from '@/components/Avatar'

/** 后台页路径：命中即切后台侧栏（与旧版 renderAdminEntry 的 inAdmin 判定一致） */
const ADMIN_PATHS = /admin\.html|admin-profile\.html|users\.html|org\.html|roles\.html|perms\.html|docs\.html|models\.html|usage\.html|loginlogs\.html|audit\.html/
const CHAT_NAV = [
  { key: 'index', href: 'index.html', label: '智能问答', icon: 'chat', perm: 'chat:use' },
  { key: 'manage', href: 'manage.html', label: '知识库文档', icon: 'doc', perm: 'doc:read' }
]

export default function Shell({ children }: { children: React.ReactNode }) {
  const auth = useAuthStore()
  const chat = useChatStore()
  const navigate = useNavigate()
  const location = useLocation()
  const inAdmin = ADMIN_PATHS.test(location.pathname)

  useEffect(() => {
    if (!isLoggedIn()) { navigate('/login.html', { replace: true }); return }
    if (!auth.loaded) void auth.loadPermissions()
  }, [auth.loaded, navigate])

  // 账号切换时先清空上一个账号的会话/草稿缓存，再按新账号重新拉取。
  // 之前 store 在模块加载时读了一次旧 scope 的 localStorage，切号后直接合并，
  // 就把上一账号的历史会话带到了新账号侧栏（实测 13560009876 只剩 1 条，界面却显示 admin 的 25 条）。
  const accountKey = auth.user?.user || ''
  const lastAccount = useRef('')
  useEffect(() => {
    if (!accountKey || !auth.loaded) return
    if (lastAccount.current && lastAccount.current !== accountKey) {
      chat.reset()
    }
    lastAccount.current = accountKey
  }, [accountKey, auth.loaded, chat])

  useEffect(() => {
    if (!isLoggedIn() || inAdmin) return
    void chat.syncSessions()
    // 只在进入问答域时同步一次，避免每次会话状态变化都请求后端
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [inAdmin, auth.loaded, accountKey])

  const user = auth.user
  const tenant = user?.tenant || 'default'
  const [orgLabels, setOrgLabels] = useState<Record<string, string>>({})
  const [displayOverride, setDisplayOverride] = useState('')
  /** 右下角服务状态胶囊：旧版就有，React 版漏了；点击可重新探测 */
  const [svcOk, setSvcOk] = useState<boolean | null>(null)
  function checkService() {
    if (!isLoggedIn()) return
    void http.get<{ healthy: boolean }>('/api/search/health')
      .then(j => setSvcOk(j?.healthy !== false)).catch(() => setSvcOk(false))
  }
  useEffect(() => { checkService() /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [])
  const name = displayOverride || (user ? (user.displayName || user.user) : '演示用户')
  useEffect(() => {
    if (!isLoggedIn()) return
    void import('@/api/http').then(async ({ http }) => {
      const orgs = await http.get<Array<Record<string, unknown>>>('/api/orgs').catch(() => [])
      const map: Record<string, string> = {}
      for (const o of Array.isArray(orgs) ? orgs : []) {
        const k = String(o.key || '')
        if (k) map[k] = String(o.name || k)
      }
      setOrgLabels(map)
      // /api/auth/me 不返回 displayName；有用户管理权限时从用户表取真实显示名
      if (auth.has('user:read')) {
        const users = await http.get<Array<Record<string, unknown>>>('/api/admin/users').catch(() => [])
        const me = (Array.isArray(users) ? users : []).find(x => x.username === getUser()?.user)
        if (me?.displayName) setDisplayOverride(String(me.displayName))
      }
    })
  }, [auth.loaded])
  const tenantLabel = (t: string) => orgLabels[t] || t || '顶层组织'

  function newChat() {
    // 空会话只作当前编辑态：本页直接切过去，跨页则跳回问答页由页面初始化接管
    const id = chat.newSession()
    if (!location.pathname.includes('index.html')) {
      navigate('/index.html')
      void id
    }
  }
  /** 从文档/设置页点击历史会话：先切会话，再回问答页，否则只改了状态看不到会话框 */
  function pickSession(id: string) {
    void chat.switchSession(id)
    if (!location.pathname.includes('index.html')) navigate('/index.html')
  }
  async function removeSession(id: string) {
    // 正在流式回答时删会话：先中止请求，否则回调还会往已删的会话里写消息
    chat.abortSession(id)
    const s = chat.sessions.find(x => x.id === id)
    const ok = await new Promise<boolean>(resolve => {
      Modal.confirm({
        title: '删除会话',
        content: `确定删除会话「${s?.title || '新会话'}」吗？其中所有对话记录都会被清除，无法恢复。`,
        okText: '删除', cancelText: '取消', okButtonProps: { danger: true },
        onOk: () => resolve(true), onCancel: () => resolve(false)
      })
    })
    if (ok) chat.removeSession(id)
  }

  async function confirmLogout() {
    const detail = auth.has('chat:use')
      ? '还没发出的提问会留在这台设备上，下次用同一账号登录还能接着写；之前的对话记录不受影响。'
      : '下次用同一账号登录，会回到你上次使用的页面。'
    const ok = await new Promise<boolean>(resolve => {
      Modal.confirm({
        title: '确认退出登录？',
        content: <div>
          <p style={{ margin: '0 0 8px' }}>退出后需要重新登录才能继续使用，确定现在退出吗？</p>
          <p style={{ margin: 0, color: 'var(--muted)', fontSize: 12 }}>{detail}</p>
        </div>,
        okText: '退出登录', cancelText: '继续留在当前页面',
        onOk: () => resolve(true), onCancel: () => resolve(false)
      })
    })
    if (!ok) return
    await logout()
    auth.reset()
    chat.reset()
    navigate('/login.html', { replace: true })
  }

  const sessions = chat.sessions.slice().sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))
  const activeKey = location.pathname.replace(/^\//, '').replace('.html', '')

  /** 后台侧栏：console.* 分组，菜单图标/名称/权限全来自服务端（与旧版 renderAdminSidebar 一致） */
  const adminGroups = auth.navGroups
    .filter(g => g.code.startsWith('console.'))
    .map(g => ({
      label: g.name,
      items: auth.menus.filter(m => m.allowed && m.group === g.code &&
        m.href !== 'admin.html' && m.href !== 'profile.html')
    })).filter(g => g.items.length)

  return (
    <div className="layout" id="sidebarHost">
      <aside className={inAdmin ? 'sidebar admin-sidebar' : 'sidebar'}>
        <div className="brand">
          <div className="brand-mark has-logo">
            <img className="brand-logo" src="/assets/logo.svg" alt="水利知识库"
              onError={e => { (e.currentTarget as HTMLImageElement).style.display = 'none' }} />
          </div>
          <div>
            <div className="brand-title">{inAdmin ? '管理后台' : '水利知识库'}</div>
            <div className="brand-sub">{inAdmin ? '水利知识库' : 'RAG 智能问答系统'}</div>
          </div>
        </div>

        {inAdmin ? (
          /* 后台侧栏：服务端菜单分组 */
          adminGroups.map(g => (
            <div className="admin-nav-group" key={g.label}>
              <div className="section-label" style={{ margin: '14px 0 6px' }}>{g.label}</div>
              {g.items.map(m => {
                const key = String(m.href).replace(/^\//, '').replace('.html', '')
                return (
                  <Link key={m.href} to={'/' + String(m.href).replace(/^\//, '')}
                    className={`admin-nav-item${activeKey === key ? ' active' : ''}`}
                    data-panel={key} title={m.name}>
                    <span className="ic">{m.icon || '▤'}</span>{m.name}
                  </Link>
                )
              })}
            </div>
          ))
        ) : (
          <>
        <div className="section-label" style={{ marginTop: 0 }}>工作台</div>
        <nav className="nav-list">
          {CHAT_NAV.filter(n => auth.has(n.perm)).map(n => (
            <Link key={n.key} to={`/${n.href}`} className={`nav-item${activeKey === n.key ? ' active' : ''}`}
              title={n.label}>
              <Icon name={n.icon} /><span>{n.label}</span>
            </Link>
          ))}
        </nav>

        {auth.has('chat:use') && <>
        <button className="new-chat" type="button" onClick={newChat}>＋ 新建会话</button>
        <div className="section-label">历史会话</div>
        <div className="session-list" id="sessionList">
          {sessions.length === 0
            ? <div className="session-empty">暂无会话，点击上方新建</div>
            : sessions.map(s => {
              const short = String(s.id || '').startsWith('session-') ? s.id.slice(8, 14) : String(s.id || '').slice(0, 8)
              return (
                <div key={s.id} className={`session-item${s.id === chat.currentId ? ' active' : ''}`}
                  data-sid={s.id} title={s.title || ''} onClick={() => pickSession(s.id)}>
                  <span className="session-name">{s.title || ('会话 ' + short)}</span>
                  <span className="session-time">{fmtTime(s.updatedAt)}</span>
                  <button className="session-delete" type="button" title="删除会话"
                    onClick={e => { e.stopPropagation(); removeSession(s.id) }}>×</button>
                </div>
              )
            })}
        </div>
        </>}
          </>
        )}

        {inAdmin ? (
          /* 后台底部：返回问答 + 用户卡（后台相对独立，头像进后台自己的个人中心） */
          <div className="admin-back">
            {auth.has('chat:use') && (
              <Link className="admin-nav-item" to="/index.html" title="返回问答">
                <span className="ic">⌂</span>返回问答
              </Link>
            )}
            <div className="user-block" id="adminUserBlock">
              <Link className="user-avatar" to="/admin-profile.html" title="个人中心"><Avatar name={name} size="md" src={auth.user?.avatar} /></Link>
              <Link className="user-meta" to="/admin-profile.html" title="个人中心">
                <div className="user-name">{name}</div>
                <div className="user-tenant">{tenantLabel(tenant)}</div>
              </Link>
              <button className="user-logout" type="button" title="退出登录" onClick={() => void confirmLogout()}>⏻</button>
            </div>
          </div>
        ) : (
          <>
            <div className="user-block" id="sidebarUserBlock">
              <Link className="user-avatar" to="/profile.html" title="个人中心"><Avatar name={name} size="md" src={auth.user?.avatar} /></Link>
              <Link className="user-meta" to="/profile.html" title="个人中心">
                <div className="user-name">{name}</div>
                <div className="user-tenant">{tenantLabel(tenant)}</div>
              </Link>
              <Link className="user-icon-btn" to="/settings.html" title="设置"><Icon name="settings" className="nav-icon" /></Link>
              <button className="user-logout" type="button" title="退出登录" onClick={() => void confirmLogout()}>⏻</button>
            </div>
            <div className="sidebar-footer">水利知识库 · 让知识更好用</div>
          </>
        )}
      </aside>
      <main className="main">{children}</main>
      <div className={'svc-pill' + (svcOk === false ? ' off' : '')} onClick={checkService}
        title="检索服务状态，点击重新检测">
        <span className="status-dot" />{svcOk === false ? '服务异常' : '服务正常'}
      </div>
    </div>
  )
}
