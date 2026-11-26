/** 后台布局：顶栏（面包屑 + 消息中心）+ 面板路由出口 */
import { useCallback, useEffect, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { message } from 'antd'
import WbModal from '@/components/WbModal'
import { tsLocal } from '@/lib/ui'
import { http } from '@/api/http'
import { useAuthStore } from '@/store/auth'
import '@/styles/admin.css'

const TITLES: Record<string, string> = {
  users: '用户管理', org: '组织架构', roles: '角色定义', perms: '菜单权限',
  docs: '文档管理', models: '模型设置', usage: '用量统计',
  loginlogs: '登录日志', audit: '操作日志', 'admin-profile': '个人中心', profile: '个人中心'
}

interface ResetRow {
  id: number; username: string; displayName?: string; status: string
  requestIp?: string; requestedAt?: number; approvedBy?: string; expiresAt?: number
}

export default function AdminLayout() {
  const location = useLocation()
  const auth = useAuthStore()
  const [pending, setPending] = useState<ResetRow[]>([])
  const [msgOpen, setMsgOpen] = useState(false)
  const [code, setCode] = useState<string | null>(null)

  const key = location.pathname.split('/').pop()?.replace('.html', '') || 'users'
  const title = TITLES[key] || '用户管理'

  const loadResets = useCallback(async () => {
    if (!auth.has('admin:access')) { setPending([]); return }
    try {
      const j = await http.get<ResetRow[]>('/api/admin/password-resets')
      setPending(Array.isArray(j) ? j : [])
    } catch { setPending([]) }
  }, [auth])

  useEffect(() => { void loadResets() /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [auth.loaded])

  const isPending = (r: ResetRow) => r.status === 'PENDING'
  /** 已过期（重置码作废）的不再列出，否则列表越用越长 */
  const visible = pending.filter(r => isPending(r) || !(r.expiresAt && r.expiresAt <= Date.now()))
  const pendingCount = visible.filter(isPending).length

  /** 账号的中文显示名：有显示名就写「显示名（账号）」，只有账号时才退回账号 */
  function userLabel(username: string, displayName?: string) {
    const acct = String(username || '')
    const pick = (dn?: string) => {
      const s = String(dn || '').trim()
      if (!s) return ''
      return s.toLowerCase() === acct.toLowerCase() ? s : `${s}（${acct}）`
    }
    return pick(displayName) || acct || '—'
  }

  async function approve(id: number) {
    try {
      const j = await http.post<{ code: string }>(`/api/admin/password-resets/${id}/approve`)
      setCode(j.code)
      await loadResets()
    } catch (e: any) { message.error(e?.message || '操作失败') }
  }

  async function reject(id: number) {
    try {
      await http.post(`/api/admin/password-resets/${id}/reject`)
      message.success('已驳回该申请')
      await loadResets()
    } catch (e: any) { message.error(e?.message || '操作失败') }
  }

  /** 复制重置码：clipboard 要求安全上下文，内网 http 部署时降级到 execCommand */
  async function copyCode() {
    const text = String(code || '').trim()
    if (!text) return
    let ok = false
    try { await navigator.clipboard.writeText(text); ok = true } catch {
      try {
        const ta = document.createElement('textarea')
        ta.value = text; ta.setAttribute('readonly', '')
        ta.style.cssText = 'position:fixed;top:-1000px;opacity:0'
        document.body.appendChild(ta); ta.select()
        ok = document.execCommand('copy'); ta.remove()
      } catch { ok = false }
    }
    if (ok) message.success('重置码已复制')
    else message.error('复制失败，请手动选中复制')
  }

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
            {pendingCount > 0 && <span className="dot">{pendingCount}</span>}
          </button>
        </div>
      </header>
      {/* admin-page：后台内容区铺满主区（旧版 .page 不带 1160 上限） */}
      <div className="page admin-page"><div className="admin-panel active" data-panel={key}><MainPanel panel={key} /></div></div>

      {/* 消息中心：待办通知的收件箱 */}
      <WbModal size="md" title="消息中心" open={msgOpen} footer={null} onCancel={() => setMsgOpen(false)}>
        <div className="form-note">待办都在这里，处理完就会消失。</div>
        {visible.length === 0
          ? <div className="empty-inline">暂无待办</div>
          : visible.map(r => {
            const isPend = isPending(r)
            return <div className="msg-row" key={r.id}>
              <div className="msg-main">
                <div className="msg-title">「{userLabel(r.username, r.displayName)}」申请重置密码</div>
                <div className="msg-sub">
                  {tsLocal(r.requestedAt)} · 来源 {r.requestIp || '—'} ·{' '}
                  {isPend
                    ? <span className="tag warn">待审批</span>
                    : <>
                      <span className="tag info">已发码 · 待使用</span>
                      {r.expiresAt ? ` · ${tsLocal(r.expiresAt)} 前有效` : ''}
                    </>}
                </div>
              </div>
              <div className="msg-ops">
                {isPend
                  ? <>
                    <button className="btn btn-sm btn-primary" onClick={() => void approve(r.id)}>批准并发码</button>
                    <button className="btn btn-sm" onClick={() => void reject(r.id)}>驳回</button>
                  </>
                  : <span className="msg-sub">由 {r.approvedBy || '—'} 发放</span>}
              </div>
            </div>
          })}
      </WbModal>

      {/* 重置码只显示这一次，关掉就再也看不到 */}
      <WbModal title="重置码已生成" open={!!code} okText="知道了" cancelText={null}
        onOk={() => { setCode(null); setMsgOpen(true) }} onCancel={() => setCode(null)}>
        <p style={{ margin: '0 0 12px' }}>请通过可靠渠道告知本人：<b>30 分钟内有效，用一次即作废</b>。</p>
        <div className="reset-code-wrap">
          <div className="reset-code">{code}</div>
          <button className="btn btn-sm copy-btn" type="button" onClick={() => void copyCode()}>复制</button>
        </div>
        <p className="hint" style={{ margin: '12px 0 0' }}>
          系统只保存重置码的哈希，关闭本弹框后无法再次查看。
          若已丢失，可让用户重新提交申请，或直接在账号列表里为他重置密码。
        </p>
      </WbModal>
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
import ProfilePanel from '@/pages/admin/ProfilePanel'

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
    case 'admin-profile': return <ProfilePanel />
    case 'profile': return <ProfilePanel />
    default: return <UsersPanel />
  }
}
