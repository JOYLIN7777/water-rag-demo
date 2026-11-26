/** 登录页：DOM/class 与旧版 login.html 1:1 对齐，交互逻辑完整保留 */
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { authApi } from '@/api'
import { encryptPassword, saveSession, KEY_USER } from '@/api/auth'
import { useAuthStore } from '@/store/auth'
import '@/styles/login.css'

const KEY_REMEMBER = 'wrk_remember_user'

export default function LoginPage() {
  const navigate = useNavigate()
  const auth = useAuthStore()
  const [username, setUsername] = useState(() => {
    try { return localStorage.getItem(KEY_REMEMBER) || '' } catch { return '' }
  })
  const [password, setPassword] = useState('')
  const [remember, setRemember] = useState(() => {
    try { return !!localStorage.getItem(KEY_REMEMBER) } catch { return false }
  })
  const [showPw, setShowPw] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  /** 落地页完全按服务端菜单决定，不信任客户端 groups；失败落个人中心 */
  async function pickLandingPage(): Promise<string> {
    try {
      const j = await authApi.permissions()
      const menus = j.menus || []
      for (const m of menus) {
        if (!m.allowed) continue
        const href = String(m.href || '').replace(/^\//, '')
        if (!href || href === 'profile.html') continue
        return '/' + href
      }
    } catch { /* 接口失败走兜底 */ }
    return '/profile.html'
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    const u = username.trim()
    if (!u || !password) { setError('请输入用户名和密码'); return }
    setError('')
    setBusy(true)
    try {
      const r = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: u, password: await encryptPassword(password), encrypted: true })
      })
      const j = await r.json() as Record<string, any>
      if (!r.ok) { setError(j.message || `登录失败（HTTP ${r.status}）`); return }
      sessionStorage.setItem('wrk_access_token', j.accessToken)
      sessionStorage.setItem('wrk_refresh_token', j.refreshToken)
      sessionStorage.setItem(KEY_USER, JSON.stringify({
        user: j.user, displayName: j.displayName, groups: j.groups, tenant: j.tenant
      }))
      try {
        if (remember) localStorage.setItem(KEY_REMEMBER, u)
        else localStorage.removeItem(KEY_REMEMBER)
      } catch { /* 同上 */ }
      const landing = await pickLandingPage()
      navigate(landing, { replace: true })
    } catch (err: any) {
      setError('网络异常，请稍后重试：' + (err?.message || ''))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-page">
      <div className="login-wrap">
        <div className="login-brand">
          <div className="lb-mark">水</div>
          <h1 className="lb-title">水利知识库</h1>
          <p className="lb-sub">RAG 智能问答系统</p>
          <div className="lb-features">
            <div className="lb-features-title">能帮你做什么</div>
            <div className="lb-feature"><span className="tick">✓</span><span>把工作里的规程、报告和表格放进来，随时问、随时查</span></div>
            <div className="lb-feature"><span className="tick">✓</span><span>回答有出处，点开就能看原文，不用自己翻文件</span></div>
            <div className="lb-feature"><span className="tick">✓</span><span>各部门只看得到自己的资料，重要数据不串门</span></div>
          </div>
          <div className="lb-foot">让知识更好用 · 水利知识库</div>
        </div>

        <div className="login-form">
          <h1 className="lf-title">欢迎登录</h1>
          <p className="lf-desc">请使用您的账号登录以访问知识库工作台</p>
          <div className={`err-box${error ? ' show' : ''}`}>{error}</div>

          <form id="loginForm" onSubmit={submit} autoComplete="on">
            <div className="field">
              <label htmlFor="username">用户名</label>
              <input id="username" name="username" type="text" placeholder="请输入用户名"
                autoComplete="username" value={username} onChange={e => setUsername(e.target.value)} required />
            </div>
            <div className="field">
              <label htmlFor="password">密码</label>
              <div className="pw-wrap">
                <input id="password" name="password" type={showPw ? 'text' : 'password'}
                  placeholder="请输入密码" autoComplete="current-password"
                  value={password} onChange={e => setPassword(e.target.value)} required />
                <button type="button" className="pw-eye" onClick={() => setShowPw(!showPw)}
                  title="显示/隐藏密码">{showPw ? '隐藏' : '显示'}</button>
              </div>
            </div>
            <div className="row-between">
              <label className="checkbox">
                <input type="checkbox" id="remember" checked={remember} onChange={e => setRemember(e.target.checked)} />记住用户名
              </label>
              <a className="link" href="/forgot.html" title="通过管理员审批重置密码">忘记密码？</a>
            </div>
            <button className="login-btn" type="submit" disabled={busy}>{busy ? '登录中…' : '登 录'}</button>
          </form>
        </div>
      </div>
    </div>
  )
}
