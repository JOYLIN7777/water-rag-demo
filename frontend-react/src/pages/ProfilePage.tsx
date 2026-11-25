/** 个人中心：DOM/class 与旧版 profile.html 1:1，JWT 有效期/修改密码逻辑完整 */
import { useEffect, useState } from 'react'
import { Modal, message } from 'antd'
import { http } from '@/api/http'
import { getAccess, encryptPassword, logout } from '@/api/auth'
import { useAuthStore } from '@/store/auth'
import { useChatStore } from '@/store/chat'
import { useNavigate } from 'react-router-dom'
import { Avatar } from '@/components/Avatar'
import '@/styles/profile.css'

interface MeResponse {
  user: string
  displayName?: string
  groups?: string[]
  tenant: string
}

function parseJwt(tok: string): { exp?: number; iat?: number } | null {
  try {
    return JSON.parse(atob(tok.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))) as { exp?: number; iat?: number }
  } catch { return null }
}

function confirmLogout(onOk: () => void) {
  Modal.confirm({
    title: '确认退出登录？',
    content: <div>
      <p style={{ margin: '0 0 8px' }}>退出后需要重新登录才能继续使用，确定现在退出吗？</p>
      <p style={{ margin: 0, color: 'var(--muted)', fontSize: 12 }}>下次用同一账号登录，会回到你上次使用的页面。</p>
    </div>,
    okText: '退出登录', cancelText: '继续留在当前页面', onOk
  })
}

export default function ProfilePage() {
  const auth = useAuthStore()
  const chat = useChatStore()
  const navigate = useNavigate()
  const [me, setMe] = useState<MeResponse | null>(null)
  const [orgLabels, setOrgLabels] = useState<Record<string, string>>({})
  const [token, setToken] = useState<{ remain: number; pct: number } | null>(null)
  const [loadErr, setLoadErr] = useState(false)
  const [pwdOpen, setPwdOpen] = useState(false)
  const [oldPwd, setOldPwd] = useState('')
  const [newPwd, setNewPwd] = useState('')
  const [newPwd2, setNewPwd2] = useState('')
  const [pwdMsg, setPwdMsg] = useState('')
  const [pwdBusy, setPwdBusy] = useState(false)

  useEffect(() => {
    void (async () => {
      try {
        // 组织名映射：管理员改名后显示新名字
        try {
          const orgs = await http.get<Array<Record<string, unknown>>>('/api/orgs')
          const map: Record<string, string> = {}
          for (const o of orgs || []) {
            const k = String(o.key || o.code || '')
            if (k) map[k] = String(o.name || o.label || k)
          }
          setOrgLabels(map)
        } catch { /* 组织接口失败不影响主信息 */ }

        const j = await http.get<MeResponse>('/api/auth/me')
        setMe(j)
        updateToken()
      } catch { setLoadErr(true) }
    })()
    const timer = setInterval(updateToken, 1000)
    return () => clearInterval(timer)
  }, [])

  function updateToken() {
    const c = parseJwt(getAccess())
    if (c?.exp) {
      const remain = Math.max(0, c.exp - Math.floor(Date.now() / 1000))
      const iat = c.iat || (c.exp - remain)
      const total = Math.max(1, c.exp - iat)
      setToken({ remain, pct: Math.min(100, Math.round(remain / total * 100)) })
    } else setToken(null)
  }

  const tl = (t?: string) => (t ? (orgLabels[t] || t) : '顶层组织')
  const gl = (g: string) => (orgLabels[g] || g)
  const name = me?.displayName || me?.user || '演示用户'
  const groups = me?.groups || []

  async function changePassword() {
    setPwdMsg('')
    if (!oldPwd) { setPwdMsg('请输入当前密码'); return false }
    if (newPwd !== newPwd2) { setPwdMsg('两次输入的新密码不一致'); return false }
    if (newPwd.length < 8) { setPwdMsg('新密码长度至少 8 位'); return false }
    setPwdBusy(true)
    try {
      await http.post('/api/auth/change-password', {
        oldPassword: await encryptPassword(oldPwd),
        newPassword: await encryptPassword(newPwd),
        encrypted: true
      })
      message.success('密码已修改，下次登录请使用新密码')
      setOldPwd(''); setNewPwd(''); setNewPwd2('')
      return true
    } catch (e: any) {
      setPwdMsg(e?.message || '网络异常，请稍后重试')
      return false
    } finally { setPwdBusy(false) }
  }

  function doLogout() {
    void (async () => {
      await logout()
      auth.reset(); chat.reset()
      navigate('/login.html', { replace: true })
    })()
  }

  return (
    <>
      <header className="topbar">
        <div className="topbar-left"><div className="topbar-title">个人中心</div><span className="topbar-sub">账号与安全</span></div>
      </header>
      <div className="page">
        <div className="page-head">
          <h1 className="page-title">个人中心</h1>
          <p className="page-desc">查看账号信息与登录状态，并可修改登录密码。</p>
        </div>

        <div className="profile-head">
          <div className="profile-avatar">{name.slice(0, 1)}</div>
          <div>
            <div className="profile-name">{name}</div>
            <div className="profile-sub">{tl(me?.tenant)} · {(groups.map(gl).join('、')) || '普通用户'}</div>
          </div>
        </div>

        <div className="card">
          <div className="card-head"><h3>账号信息</h3></div>
          <div className="info-grid">
            {loadErr
              ? <div className="empty">加载失败</div>
              : <>
                <div className="info-cell"><div className="k">登录账号</div><div className="v">{me?.user || '—'}</div></div>
                <div className="info-cell"><div className="k">角色</div><div className="v">{groups.length ? groups.map(gl).join('、') : '—'}</div></div>
                <div className="info-cell"><div className="k">所属组织</div><div className="v">{tl(me?.tenant)}</div></div>
                <div className="info-cell"><div className="k">登录方式</div><div className="v"><span className="tag info">账号密码</span></div></div>
              </>}
          </div>
        </div>

        <div className="card">
          <div className="card-head"><h3>登录状态</h3><button className="btn btn-sm" type="button" onClick={() => confirmLogout(doLogout)}>退出登录</button></div>
          <div className="sub">登录有效期内无需重复登录，到期前系统会自动续期；退出后本次登录立即失效。</div>
          <div className="info-grid">
            <div className="info-cell" style={{ gridColumn: '1 / -1' }}>
              {token
                ? <>
                  <div className="k">本次登录剩余时间</div>
                  <div className="v">{Math.floor(token.remain / 60)} 分 {String(token.remain % 60).padStart(2, '0')} 秒</div>
                  <div className="token-bar"><i style={{ width: `${token.pct}%` }} /></div>
                  <div className="k" style={{ marginTop: 8 }}>到期前会自动续期，无需重新登录</div>
                </>
                : <div className="v">未获取到令牌信息</div>}
            </div>
          </div>
        </div>

        <div className="card">
          <div className="card-head"><h3>账号安全</h3></div>
          <div className="sub">修改后下次登录请使用新密码。密码经过加密存储，系统不会保存明文。</div>
          <button className="btn btn-primary" type="button" onClick={() => { setPwdMsg(''); setPwdOpen(true) }}>修改密码</button>
        </div>
      </div>

      <Modal title="修改密码" open={pwdOpen} okText="确认修改" cancelText="取消"
        confirmLoading={pwdBusy} onCancel={() => setPwdOpen(false)}
        onOk={async () => { const ok = await changePassword(); if (ok) setPwdOpen(false) }}>
        <div className="field"><label htmlFor="oldPwd">当前密码</label>
          <input id="oldPwd" type="password" autoComplete="current-password" placeholder="请输入当前密码"
            value={oldPwd} onChange={e => setOldPwd(e.target.value)} /></div>
        <div className="field"><label htmlFor="newPwd">新密码</label>
          <input id="newPwd" type="password" autoComplete="new-password" placeholder="至少 8 位"
            value={newPwd} onChange={e => setNewPwd(e.target.value)} /></div>
        <div className="field"><label htmlFor="newPwd2">确认新密码</label>
          <input id="newPwd2" type="password" autoComplete="new-password" placeholder="再次输入新密码"
            value={newPwd2} onChange={e => setNewPwd2(e.target.value)} /></div>
        <div className={pwdMsg ? 'err' : ''} style={{ minHeight: 20, fontSize: 12, marginTop: 4 }}>{pwdMsg}</div>
      </Modal>
    </>
  )
}
