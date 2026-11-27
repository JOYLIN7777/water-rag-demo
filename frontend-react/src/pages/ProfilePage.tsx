/** 个人中心：DOM/class 与旧版 profile.html 1:1，JWT 有效期/修改密码逻辑完整 */
import { useEffect, useRef, useState } from 'react'
import { Modal, message } from 'antd'
import { http } from '@/api/http'
import { getAccess, encryptPassword, logout, ensureFresh, scope } from '@/api/auth'
import { useAuthStore } from '@/store/auth'
import { useChatStore } from '@/store/chat'
import { useNavigate, Link } from 'react-router-dom'
import { Avatar } from '@/components/Avatar'
import '@/styles/profile.css'
import TopbarNav from '@/components/TopbarNav'
import AdminEntry from '@/components/AdminEntry'

interface MeResponse {
  user: string
  displayName?: string
  groups?: string[]
  tenant: string
  gender?: string
  phone?: string
  avatar?: string
}

/** 预设头像：不上传时用这些，存的是标识而不是图片 */
const PRESET_AVATARS = ['🦉', '🐟', '🌊', '💧', '🛡️', '📚']
const AVATAR_KEY = 'wrk_preset_avatar' 

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
  const [docsCount, setDocsCount] = useState(0)
  const [myUsage, setMyUsage] = useState<{ todayRequests: number; todayTokens: number } | null>(null)
  // 个人资料：头像（上传 data URL 或预设 emoji）、性别、手机号
  const [profileOpen, setProfileOpen] = useState(false)
  const [gender, setGender] = useState('')
  const [phone, setPhone] = useState('')
  const [avatarInput, setAvatarInput] = useState('')
  const [preset, setPreset] = useState('')
  const [profileErr, setProfileErr] = useState('')
  const renewing = useRef(false)

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
        setGender(j?.gender || '')
        setPhone(j?.phone || '')
        setAvatarInput(j?.avatar || '')
        setPreset(localStorage.getItem(AVATAR_KEY) || '')
        updateToken()
        // 文档数属于当前账号可见范围，取不到就显示 0，不影响页面其它信息
        void http.get<unknown[]>('/api/docs/list')
          .then(list => setDocsCount(Array.isArray(list) ? list.length : 0)).catch(() => setDocsCount(0))
      } catch { setLoadErr(true) }
    })()
    // 个人中心始终只看本人，与角色无关
    void http.get<{ totals?: { todayRequests?: number; todayTokens?: number } }>('/api/me/usage')
      .then(j => setMyUsage({ todayRequests: Number(j?.totals?.todayRequests || 0), todayTokens: Number(j?.totals?.todayTokens || 0) }))
      .catch(() => setMyUsage(null))
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
      // 令牌到期前静默续期：走项目统一的 ensureFresh（会带上 refreshToken 并写回会话），
      // 成功就留在本页继续用，只有 refreshToken 也失效才跳登录。
      if (remain <= 30 && !renewing.current) {
        renewing.current = true
        void ensureFresh().then(ok => {
          if (ok) { updateToken(); return }
          auth.reset(); chat.reset()
          navigate('/login.html', { replace: true })
        }).finally(() => { renewing.current = false })
      }
    } else setToken(null)
  }

  const tl = (t?: string) => (t ? (orgLabels[t] || t) : '顶层组织')
  const gl = (g: string) => (orgLabels[g] || g)
  const name = me?.displayName || me?.user || '演示用户'
  const groups = me?.groups || []
  const msgCount = Object.values(chat.bySession).reduce((n, arr) => n + (Array.isArray(arr) ? arr.length : 0), 0)

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

  /** 把本机会话与消息导出为 JSON：换机器/备份时能用，不依赖后端导出接口 */
  function exportSessions() {
    const payload = {
      exportedAt: new Date().toISOString(),
      account: me?.user || '',
      sessions: chat.sessions,
      messages: chat.bySession
    }
    const url = URL.createObjectURL(new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' }))
    const a = document.createElement('a')
    a.href = url
    a.download = `我的会话-${new Date().toISOString().slice(0, 10)}.json`
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 2000)
    message.success(`已导出 ${chat.sessions.length} 个会话`)
  }

  function clearDrafts() {
    const prefix = `draft:${scope()}:`
    const keys = Object.keys(localStorage).filter(k => k.startsWith(prefix))
    keys.forEach(k => localStorage.removeItem(k))
    message.success(keys.length ? `已清除 ${keys.length} 条未发送草稿` : '当前没有待清除的草稿')
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
        <div className="topbar-left"><TopbarNav /><div className="topbar-title">个人中心</div><span className="topbar-sub">账号与安全</span></div>
        <div className="topbar-nav"><AdminEntry /></div>
      </header>
      <div className="page">
        <div className="page-head">
          <h1 className="page-title">个人中心</h1>
          <p className="page-desc">查看账号信息与登录状态，并可修改登录密码。</p>
        </div>

        <div className="profile-head">
          <div className="profile-avatar">
            {me?.avatar && /^data:image\//.test(me.avatar)
              ? <img src={me.avatar} alt="头像" className="profile-avatar-img" />
              : (preset || name.slice(0, 1))}
          </div>
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
          <div className="card-head"><h3>我的知识库使用概览</h3><Link to="/settings.html?from=profile" className="op-btn">问答偏好设置 ›</Link></div>
          <div className="usage-mini">
            <div><b>{chat.sessions.length}</b><span>历史会话</span></div>
            <div><b>{msgCount}</b><span>本机消息</span></div>
            <div><b>{docsCount}</b><span>可见文档</span></div>
            <div><b>{myUsage?.todayRequests ?? '—'}</b><span>今日提问</span></div>
            <div><b>{myUsage?.todayTokens ?? '—'}</b><span>今日 Token</span></div>
          </div>
          <div className="form-hint" style={{ marginTop: 8 }}>此处仅统计你本人的用量；后台「用量统计」按角色显示本部门或全站。</div>
        </div>

        <div className="card">
          <div className="card-head"><h3>数据与快捷操作</h3></div>
          <div className="sub">导出本机会话做备份；清除草稿只影响这台设备，不会删除服务端记录。</div>
          <div className="quick-actions">
            <button className="btn btn-sm btn-primary" type="button" onClick={exportSessions}>导出我的会话</button>
            <button className="btn btn-sm" type="button" onClick={clearDrafts}>清除本地草稿</button>
            <Link className="btn btn-sm" to="/index.html">继续提问</Link>
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
          <div className="card-head"><h3>个人资料</h3>
            <button className="btn btn-sm btn-primary" type="button"
              onClick={() => { setProfileErr(''); setProfileOpen(true) }}>编辑资料</button></div>
          <div className="info-grid">
            <div className="info-cell"><div className="k">性别</div><div className="v">{gender || '不公开'}</div></div>
            <div className="info-cell"><div className="k">手机号</div><div className="v">{phone || '未设置'}</div></div>
            <div className="info-cell"><div className="k">头像</div><div className="v">{avatarInput.startsWith('data:image/') ? '已上传自定义头像' : (preset ? '预设头像 ' + preset : '默认首字母')}</div></div>
          </div>
        </div>

        <div className="card">
          <div className="card-head"><h3>账号安全</h3></div>
          <div className="sub">修改后下次登录请使用新密码。密码经过加密存储，系统不会保存明文。</div>
          <button className="btn btn-primary" type="button" onClick={() => { setPwdMsg(''); setPwdOpen(true) }}>修改密码</button>
        </div>
      </div>

      <Modal title="编辑个人资料" open={profileOpen} okText="保存" cancelText="取消"
        onCancel={() => setProfileOpen(false)} onOk={async () => {
          setProfileErr('')
          if (phone && !/^\d{11}$/.test(phone)) { setProfileErr('手机号必须是 11 位数字'); return }
          try {
            const avatar = avatarInput || preset
            await http.put('/api/auth/profile', { gender, phone, avatar })
            if (preset) localStorage.setItem(AVATAR_KEY, preset)
            else localStorage.removeItem(AVATAR_KEY)
            setMe(m => m ? { ...m, gender, phone, avatar } : m)
            auth.updateProfile({ gender, phone, avatar })   // 侧栏头像即时同步
            setProfileOpen(false)
            message.success('个人资料已保存')
          } catch (e: any) { setProfileErr(e?.message || '保存失败') }
        }}>
        <div className="field"><label>头像</label>
          <div className="avatar-picker">
            {PRESET_AVATARS.map(a => <button type="button" key={a}
              className={'avatar-preset' + (preset === a && !avatarInput.startsWith('data:image/') ? ' on' : '')}
              onClick={() => { setPreset(a); setAvatarInput('') }}>{a}</button>)}
            <label className="avatar-upload">上传图片
              <input type="file" accept="image/*" hidden onChange={e => {
                const f = e.target.files?.[0]
                if (!f) return
                if (f.size > 512 * 1024) { setProfileErr('头像图片不能超过 512KB'); return }
                const r = new FileReader()
                r.onload = () => { setAvatarInput(String(r.result || '')); setPreset('') }
                r.readAsDataURL(f)
              }} />
            </label>
          </div>
          {avatarInput.startsWith('data:image/') && <img src={avatarInput} alt="预览" className="avatar-preview" />}
          <div className="form-hint">可直接点选预设头像，或上传自己的图片（不超过 512KB）。</div></div>
        <div className="field"><label>性别</label>
          <select value={gender} onChange={e => setGender(e.target.value)}>
            <option value="">不公开</option><option value="男">男</option><option value="女">女</option>
          </select></div>
        <div className="field"><label>手机号（可用于登录）</label>
          <input type="tel" value={phone} maxLength={11} placeholder="11 位手机号"
            onChange={e => setPhone(e.target.value)} />
          <div className="form-hint">设置后可以用手机号代替用户名登录，同一手机号不能给多个账号使用。</div></div>
        {profileErr && <div className="msg err show">{profileErr}</div>}
      </Modal>

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
