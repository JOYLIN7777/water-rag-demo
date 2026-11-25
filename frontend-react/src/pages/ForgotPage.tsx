/** 找回密码：两步式流程，DOM/class 与旧版 forgot.html 1:1 */
import { useState } from 'react'
import { http } from '@/api/http'
import '@/styles/forgot.css'

export default function ForgotPage() {
  const [step, setStep] = useState(1)
  const [username, setUsername] = useState('')
  const [username2, setUsername2] = useState('')
  const [code, setCode] = useState('')
  const [pwd1, setPwd1] = useState('')
  const [pwd2, setPwd2] = useState('')
  const [err, setErr] = useState('')
  const [ok, setOk] = useState('')
  const [busy, setBusy] = useState(false)

  function showErr(msg: string) { setErr(msg); setOk('') }
  function showOk(msg: string) { setOk(msg); setErr('') }
  function goStep(n: number) {
    setStep(n); setErr(''); setOk('')
    setTimeout(() => document.getElementById(n === 2 ? (username2 ? 'code' : 'username2') : 'username')?.focus(), 60)
  }

  async function requestReset(e: React.FormEvent) {
    e.preventDefault()
    const u = username.trim()
    if (!u) { showErr('请输入登录账号'); return }
    setBusy(true); setErr(''); setOk('')
    try {
      const j = await http.post<{ message?: string }>('/api/auth/password-reset/request', { username: u })
      showOk(j.message || '申请已提交')
      setTimeout(() => goStep(2), 900)
    } catch (err: any) { showErr(err?.message || '网络异常，请稍后重试') }
    finally { setBusy(false) }
  }

  async function confirmReset(e: React.FormEvent) {
    e.preventDefault()
    const u = username2.trim()
    const c = code.trim().toUpperCase()
    if (!u) { showErr('请输入登录账号'); return }
    if (c.length !== 8) { showErr('重置码为 8 位字母数字，请核对后重输'); return }
    if (pwd1.length < 8) { showErr('新密码至少 8 位'); return }
    if (pwd1 !== pwd2) { showErr('两次输入的新密码不一致'); return }
    setBusy(true); setErr(''); setOk('')
    try {
      const j = await http.post<{ message?: string }>('/api/auth/password-reset/confirm', {
        username: u, code: c, newPassword: pwd1
      })
      showOk(j.message || '密码已重置，2 秒后返回登录页，请用新密码登录')
      setTimeout(() => { location.href = '/login.html' }, 2000)
    } catch (err: any) { showErr(err?.message || '网络异常，请稍后重试') }
    finally { setBusy(false) }
  }

  return (
    <div className="login-page">
      <div className="login-wrap">
        <div className="login-brand">
          <div className="lb-mark">水</div>
          <h1 className="lb-title">水利知识库</h1>
          <p className="lb-sub">RAG 智能问答系统</p>
          <div className="lb-features">
            <div className="lb-feature"><span className="tick">✓</span>上传规程、报告与表格，构建专属知识库</div>
            <div className="lb-feature"><span className="tick">✓</span>基于原文作答，每条回答都可溯源</div>
            <div className="lb-feature"><span className="tick">✓</span>数据按账号与部门隔离，安全可控</div>
          </div>
          <div className="lb-foot">让知识更好用 · 水利知识库</div>
        </div>

        <div className="login-form">
          <h1 className="lf-title">找回登录密码</h1>
          <p className="lf-desc">系统未开放邮箱短信通道，改为管理员审批：提交申请后联系管理员获取重置码。</p>
          <div className="steps">
            <div className={`step${step === 1 ? ' active' : ' done'}`} id="stepDot1"><span className="n">{step === 1 ? 1 : '✓'}</span>提交申请</div>
            <div className="step-line" />
            <div className={`step${step === 2 ? ' active' : ''}`} id="stepDot2"><span className="n">2</span>设置新密码</div>
          </div>
          <div className={`err-box${err ? ' show' : ''}`} id="errBox">{err}</div>
          <div className={`ok-box${ok ? ' show' : ''}`} id="okBox">{ok}</div>

          {step === 1 ? (
            <form id="form1" autoComplete="off" onSubmit={e => void requestReset(e)}>
              <div className="field"><label htmlFor="username">登录账号</label>
                <input id="username" type="text" placeholder="请输入需要找回密码的账号" autoComplete="username"
                  value={username} onChange={e => setUsername(e.target.value)} required /></div>
              <button className="login-btn" type="submit" disabled={busy}>{busy ? '提交中…' : '提交重置申请'}</button>
              <p className="tip">提交后请<b>联系系统管理员</b>核身。管理员会在「管理后台 → 用户管理 → 密码重置申请」中看到这条申请，并给您一个 8 位重置码（30 分钟内有效）。</p>
              <div className="alt"><a href="/login.html">返回登录</a><span className="dot">·</span>
                <a href="javascript:void(0)" onClick={() => goStep(2)}>我已有重置码</a></div>
            </form>
          ) : (
            <form id="form2" autoComplete="off" onSubmit={e => void confirmReset(e)}>
              <div className="field"><label htmlFor="username2">登录账号</label>
                <input id="username2" type="text" placeholder="请输入登录账号" autoComplete="username"
                  value={username2} onChange={e => setUsername2(e.target.value)} required /></div>
              <div className="field"><label htmlFor="code">重置码</label>
                <input id="code" type="text" maxLength={8} placeholder="8 位字母数字"
                  value={code} onChange={e => setCode(e.target.value)} required /></div>
              <div className="field"><label htmlFor="pwd1">新密码</label>
                <input id="pwd1" type="password" placeholder="至少 8 位" autoComplete="new-password"
                  value={pwd1} onChange={e => setPwd1(e.target.value)} required /></div>
              <div className="field"><label htmlFor="pwd2">确认新密码</label>
                <input id="pwd2" type="password" placeholder="再输入一次" autoComplete="new-password"
                  value={pwd2} onChange={e => setPwd2(e.target.value)} required /></div>
              <button className="login-btn" type="submit" disabled={busy}>{busy ? '提交中…' : '设置新密码'}</button>
              <p className="tip">重置码连续输错 5 次会作废，需要重新提交申请。重置成功后旧密码立即失效。</p>
              <div className="alt"><a href="javascript:void(0)" onClick={() => goStep(1)}>重新提交申请</a><span className="dot">·</span>
                <a href="/login.html">返回登录</a></div>
            </form>
          )}
        </div>
      </div>
    </div>
  )
}
