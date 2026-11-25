/** 设置页：DOM/class 与旧版 settings.html 1:1 */
import { Link } from 'react-router-dom'
import { useAuthStore } from '@/store/auth'
import { Icon } from '@/components/Icon'
import '@/styles/settings.css'
import TopbarNav from '@/components/TopbarNav'

/** ShellSettings（与 app.js 一致），只显示有权限的 */
const SETTINGS = [
  { key: 'search', href: '/search.html', label: '检索对比', icon: 'search', perm: 'search:use',
    desc: '对比关键词检索、语义检索和融合检索的结果，帮助找到更准的答案' },
  { key: 'tree', href: '/tree.html', label: '会话结构', icon: 'tree', perm: 'tree:use',
    desc: '查看当前会话的消息树与命名分支，可切回任意历史节点' }
]

export default function SettingsPage() {
  const auth = useAuthStore()
  const visible = SETTINGS.filter(s => auth.has(s.perm))

  return (
    <>
      <header className="topbar">
        <div className="topbar-left"><TopbarNav /><div className="topbar-title">设置</div><span className="topbar-sub">更多功能</span></div>
      </header>
      <div className="page">
        <div className="page-head">
          <h1 className="page-title">设置</h1>
          <p className="page-desc">更多功能都放在这里，按需使用即可。只显示当前账号有权使用的功能。</p>
        </div>
        <div className="setting-grid">
          {visible.length === 0
            ? <div className="card"><div className="empty-inline">当前账号暂无可使用的设置项</div></div>
            : visible.map(s => (
              <Link key={s.key} to={s.href} className="setting-card">
                <span className="setting-icon"><Icon name={s.icon} className="" /></span>
                <span className="setting-text">
                  <span className="setting-name">{s.label}</span>
                  <span className="setting-desc">{s.desc}</span>
                  <span className="setting-go">进入功能</span>
                </span>
              </Link>
            ))}
        </div>
      </div>
    </>
  )
}
