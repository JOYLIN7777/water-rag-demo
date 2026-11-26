/** 顶栏右上角管理后台入口：与旧版 renderAdminEntry 1:1（只有同时具备问答+后台权限才显示） */
import { useLocation, useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/store/auth'

/** 后台入口目标页：只认真正的管理权限码。
 *  原来把 usage:read 也列进去，审计员/分析员只因为能看用量统计，
 *  就在问答页看到「管理后台」按钮，点进去侧栏几乎全是空白——属于越权露出入口。 */
const ADMIN_PAGES: Array<[string, string]> = [
  ['admin:access', 'users.html'], ['user:read', 'users.html'], ['org:read', 'org.html'],
  ['role:read', 'roles.html'], ['perm:read', 'perms.html'], ['admin:docs', 'docs.html'],
  ['model:read', 'models.html'], ['loginlog:read', 'loginlogs.html'], ['audit:read', 'audit.html']
]
const ADMIN_PATHS = /admin\.html|admin-profile\.html|users\.html|org\.html|roles\.html|perms\.html|docs\.html|models\.html|usage\.html|loginlogs\.html|audit\.html/

export default function AdminEntry() {
  const auth = useAuthStore()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const inAdmin = ADMIN_PATHS.test(pathname)
  // 权限没加载完先不渲染，避免闪一下又消失
  const target = auth.loaded && auth.has('chat:use')
    ? (ADMIN_PAGES.find(p => auth.has(p[0])) || [])[1]
    : undefined

  if (inAdmin || !target) return <span id="adminEntrySlot" className="admin-slot" />
  return (
    <span id="adminEntrySlot" className="admin-slot">
      <a className="admin-entry-btn" href={'/' + target} title="进入管理后台"
        onClick={e => { e.preventDefault(); navigate('/' + target) }}>⚙ 管理后台</a>
    </span>
  )
}
