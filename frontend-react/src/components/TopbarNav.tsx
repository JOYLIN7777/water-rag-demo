/** 顶栏返回键 + 层级面包屑：与旧版 renderTopbarNav 1:1 */
import { useLocation } from 'react-router-dom'
import { useAuthStore } from '@/store/auth'

/** 页面声明：back=返回目标，parent=父级页面（有 parent 时显示两级面包屑） */
const PAGE_NAV: Record<string, { label: string; parent?: string; parentLabel?: string; back?: string; backText?: string }> = {
  'settings.html': { label: '设置', back: 'index.html' },
  'search.html': { label: '检索对比', parent: 'settings.html', parentLabel: '设置' },
  'tree.html': { label: '会话结构', parent: 'settings.html', parentLabel: '设置' },
  'profile.html': { label: '个人中心', back: 'index.html' },
  'manage.html': { label: '知识库文档', back: 'index.html' }
}

export default function TopbarNav() {
  const { pathname } = useLocation()
  const auth = useAuthStore()
  const file = pathname.split('/').pop() || 'index.html'
  const meta = PAGE_NAV[file]
  if (!meta) return null

  const canChat = auth.loaded && auth.has('chat:use')
  const backHref = meta.parent || (canChat ? (meta.back || 'index.html') : 'profile.html')
  const backText = meta.parent ? '返回设置' : (canChat ? (meta.backText || '返回') : '个人中心')

  return (
    <div className="topbar-nav-left">
      <a className="back-link" href={backHref}>{backText}</a>
      <div className="crumb">
        {meta.parent
          ? <><a href={'/' + meta.parent}>{meta.parentLabel}</a><span className="crumb-sep">/</span><span className="crumb-current">{meta.label}</span></>
          : <span className="crumb-current">{meta.label}</span>}
      </div>
    </div>
  )
}
