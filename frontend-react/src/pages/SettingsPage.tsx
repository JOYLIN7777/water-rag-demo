/** 设置页：DOM/class 与旧版 settings.html 1:1 */
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuthStore } from '@/store/auth'
import { Icon } from '@/components/Icon'
import '@/styles/settings.css'
import TopbarNav from '@/components/TopbarNav'
import AdminEntry from '@/components/AdminEntry'

/** ShellSettings（与 app.js 一致），只显示有权限的 */
const SETTINGS = [
  { key: 'search', href: '/search.html', label: '检索对比', icon: 'search', perm: 'search:use',
    desc: '对比关键词检索、语义检索和融合检索的结果，帮助找到更准的答案' },
  { key: 'tree', href: '/tree.html', label: '会话结构', icon: 'tree', perm: 'tree:use',
    desc: '查看当前会话的消息树与命名分支，可切回任意历史节点' }
]

/** 问答偏好：本地生效，刷新/切页后保留；对应 IndexPage 里的读取点 */
const PREFS = [
  { key: 'wrk_pref_autoscroll', label: '回答时自动跟随最新内容', desc: '流式输出时自动滚到底部；关闭后可自己滚动查看。' },
  { key: 'wrk_pref_usage', label: '显示本次 Token 消耗', desc: '在回答卡片头部显示本次调用的 token 与耗时。' }
]

export default function SettingsPage() {
  const auth = useAuthStore()
  const visible = SETTINGS.filter(s => auth.has(s.perm))
  const [prefs, setPrefs] = useState<Record<string, boolean>>(() => {
    const out: Record<string, boolean> = {}
    for (const p of PREFS) out[p.key] = localStorage.getItem(p.key) !== '0'
    return out
  })
  function togglePref(key: string) {
    const next = !prefs[key]
    localStorage.setItem(key, next ? '1' : '0')
    setPrefs(s => ({ ...s, [key]: next }))
  }

  return (
    <>
      <header className="topbar">
        <div className="topbar-left"><TopbarNav /><div className="topbar-title">设置</div><span className="topbar-sub">更多功能</span></div>
        <div className="topbar-nav"><AdminEntry /></div>
      </header>
      <div className="page">
        <div className="page-head">
          <h1 className="page-title">设置</h1>
          <p className="page-desc">更多功能都放在这里，按需使用即可。只显示当前账号有权使用的功能。</p>
        </div>
        {/* 没有可用设置项时整块不渲染，不再摆一个「暂无权限」空框给用户看 */}
        {visible.length > 0 && <div className="setting-grid">
          {visible.map(s => (
            <Link key={s.key} to={s.href} className="setting-card">
              <span className="setting-icon"><Icon name={s.icon} className="" /></span>
              <span className="setting-text">
                <span className="setting-name">{s.label}</span>
                <span className="setting-desc">{s.desc}</span>
                <span className="setting-go">进入功能</span>
              </span>
            </Link>
          ))}
        </div>}

        <div className="card setting-extra">
          <div className="card-head"><h3>问答偏好</h3><span className="hint">保存在本机，下次使用同一浏览器自动生效</span></div>
          <div className="pref-list">
            {PREFS.map(p => <label className="pref-row" key={p.key}>
              <span className="pref-text"><b>{p.label}</b><span className="sub">{p.desc}</span></span>
              <input type="checkbox" checked={!!prefs[p.key]} onChange={() => togglePref(p.key)} />
            </label>)}
          </div>
        </div>

        <div className="card setting-extra">
          <div className="card-head"><h3>使用帮助</h3></div>
          <div className="help-steps">
            <p><b>1 上传资料</b>：到「知识库文档」上传规程、报告或表格，系统会自动切片入库。</p>
            <p><b>2 开始提问</b>：在「智能问答」输入问题，回答会标注引用的原文片段，点角标可展开。</p>
            <p><b>3 追溯与对比</b>：用「会话结构」查看分支，用「检索对比」比较关键词/语义/融合检索效果。</p>
            <p><b>4 数据与账号</b>：个人中心可导出本机会话、修改密码；用量统计查看每次调用的真实消耗。</p>
          </div>
        </div>
      </div>
    </>
  )
}
