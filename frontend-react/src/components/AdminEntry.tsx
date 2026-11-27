/** 顶栏右上角管理后台入口：菜单与目标页全部由服务端下发，前端只做兜底 */
import { useLocation, useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/store/auth'
import type { MenuItem } from '@/types'

/** 后台入口目标页：只认真正的管理权限码。
 *  原来把 usage:read 也列进去，审计员/分析员只因为能看用量统计，
 *  就在问答页看到「管理后台」按钮，点进去侧栏几乎全是空白——属于越权露出入口。 */
/** 兜底：仅在 /api/auth/permissions 未返回菜单时启用，正常路径一律服务端优先 */
const FALLBACK_PAGES: Array<[string, string]> = [
  ['admin:access', 'users.html'], ['user:read', 'users.html'], ['org:read', 'org.html'],
  ['role:read', 'roles.html'], ['perm:read', 'perms.html'], ['admin:docs', 'docs.html'],
  ['model:read', 'models.html'], ['usage:read', 'usage.html'],
  ['loginlog:read', 'loginlogs.html'], ['audit:read', 'audit.html']
]
/** 后台页路径：由服务端菜单 group=console.* 推导，这里只留最小兜底 */
const ADMIN_PATHS = /admin\.html|admin-profile\.html|users\.html|org\.html|roles\.html|perms\.html|docs\.html|models\.html|usage\.html|loginlogs\.html|audit\.html/

/** 后台菜单项：group 属于 console.* 且非 admin.html / profile.html 的菜单 */
function consoleMenus(auth: { menus: MenuItem[] }): MenuItem[] {
  return auth.menus.filter((m: MenuItem) => m.allowed &&
    /^console\./.test(String(m.group || '')) &&
    !String(m.href).includes('admin.html') && !String(m.href).includes('profile.html'))
}

export default function AdminEntry() {
  const auth = useAuthStore()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const inAdmin = ADMIN_PATHS.test(pathname)
  // 落地页跟左边栏一致：按服务端菜单顺序取第一个 allowed 的后台页，
  // 避免「侧栏第一项是用量统计、点进去却是登录日志」这种错位。
  const consoleItems = consoleMenus(auth)
  const firstAdmin = consoleItems[0]
  // 只有 console.* 分组里有可用页面才露入口；纯业务权限（usage:read 等）不再触发入口
  const target = auth.loaded && auth.has('chat:use')
    ? (firstAdmin ? String(firstAdmin.href).replace(/^\//, '')
       : auth.menus.length ? undefined : (FALLBACK_PAGES.find(p => auth.has(p[0])) || [])[1])
    : undefined

  if (inAdmin || !target) return <span id="adminEntrySlot" className="admin-slot" />
  return (
    <span id="adminEntrySlot" className="admin-slot">
      <a className="admin-entry-btn" href={'/' + target} title="进入管理后台"
        onClick={e => { e.preventDefault(); navigate('/' + target) }}>⚙ 管理后台</a>
    </span>
  )
}
