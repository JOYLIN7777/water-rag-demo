/** 后台布局：顶栏（面包屑+消息中心）+ 面板路由出口 */
import { useEffect, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { Modal } from 'antd'
import { http } from '@/api/http'
import { useAuthStore } from '@/store/auth'
import '@/styles/admin.css'

const TITLES: Record<string, string> = {
  users: '用户管理', org: '组织架构', roles: '角色定义', perms: '菜单权限',
  docs: '文档管理', models: '模型设置', usage: '用量统计',
  loginlogs: '登录日志', audit: '操作日志', profile: '个人中心'
}

export default function AdminLayout() {
  const location = useLocation()
  const auth = useAuthStore()
  const [pending, setPending] = useState<unknown[]>([])
  const [msgOpen, setMsgOpen] = useState(false)

  const key = location.pathname.split('/').pop()?.replace('.html', '') || 'users'
  const title = TITLES[key] || '用户管理'

  useEffect(() => {
    if (auth.has('admin:access')) {
      void http.get<unknown[]>('/api/admin/password-resets')
        .then(j => setPending(Array.isArray(j) ? j : []))
        .catch(() => setPending([]))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [auth.loaded])

  return (
    <>
      <header className="topbar">
        <div className="topbar-left">
          <div className="brand-crumb"><span>管理后台</span><span className="sep">/</span><span id="panelTitle">{title}</span></div>
          <span className="topbar-sub">仅管理员可见</span>
        </div>
        <div className="topbar-right">
          <button className="msg-bell" data-perm="admin:access" onClick={() => setMsgOpen(true)} title="消息中心">
            <span>✉</span><span>消息</span>
            {pending.length > 0 && <span className="dot">{pending.length}</span>}
          </button>
        </div>
      </header>
      <div className="page"><div className="admin-panel" data-panel={key}><MainPanel panel={key} /></div></div>
      <Modal title="消息中心" open={msgOpen} onCancel={() => setMsgOpen(false)} footer={null}>
        {pending.length === 0
          ? <div style={{ color: 'var(--muted)', textAlign: 'center', padding: 24 }}>暂无待处理消息</div>
          : <div>{pending.map((p: any, i) => <div key={i} style={{ padding: '8px 0', borderBottom: '1px solid var(--line)' }}>
            {p.username || p.user || '未知账号'} · {p.status || '待审批'}
          </div>)}</div>}
      </Modal>
    </>
  )
}

/** 面板内容分发（各面板单独组件） */
import UsersPanel from '@/pages/admin/UsersPanel'
import OrgPanel from '@/pages/admin/OrgPanel'
import RolesPanel from '@/pages/admin/RolesPanel'
import PermsPanel from '@/pages/admin/PermsPanel'
import DocsPanel from '@/pages/admin/DocsPanel'
import ModelsPanel from '@/pages/admin/ModelsPanel'
import UsagePanel from '@/pages/admin/UsagePanel'
import LogsPanel from '@/pages/admin/LogsPanel'

function MainPanel({ panel }: { panel: string }) {
  switch (panel) {
    case 'users': return <UsersPanel />
    case 'org': return <OrgPanel />
    case 'roles': return <RolesPanel />
    case 'perms': return <PermsPanel />
    case 'docs': return <DocsPanel />
    case 'models': return <ModelsPanel />
    case 'usage': return <UsagePanel />
    case 'loginlogs': return <LogsPanel type="login" />
    case 'audit': return <LogsPanel type="audit" />
    default: return <UsersPanel />
  }
}
