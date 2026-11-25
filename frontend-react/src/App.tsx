/** 路由：与旧版 .html 路径一一对应；逐页移植，未移植页回退 static（全程不中断） */
import { useEffect } from 'react'
import { Routes, Route, Navigate, useNavigate } from 'react-router-dom'
import LoginPage from './pages/LoginPage'
import SettingsPage from './pages/SettingsPage'
import ProfilePage from './pages/ProfilePage'
import SearchPage from './pages/SearchPage'
import TreePage from './pages/TreePage'
import ManagePage from './pages/ManagePage'
import IndexPage from './pages/IndexPage'
import AdminLayout from './layouts/AdminLayout'
import Shell from './layouts/Shell'
import { useAuthStore } from './store/auth'
import { isLoggedIn } from './api/auth'

/** 已移植页统一外壳；登录页独立 */
function WithShell({ children, perm }: { children: React.ReactNode; perm?: string }) {
  const auth = useAuthStore()
  const navigate = useNavigate()
  useEffect(() => {
    if (!isLoggedIn()) { navigate('/login.html', { replace: true }); return }
    if (!auth.loaded) void auth.loadPermissions()
  }, [auth, navigate])
  if (!isLoggedIn()) return null
  if (perm && auth.loaded && !auth.has(perm)) return <Navigate to="/profile.html" replace />
  return <Shell>{children}</Shell>
}

/** 未移植页：直接跳后端 static 版 */
function StaticFallback({ page }: { page: string }) {
  useEffect(() => { location.href = '/' + page }, [page])
  return <div style={{ padding: 40, color: '#7e8aa0' }}>正在加载 {page} …</div>
}

export default function App() {
  return (
    <Routes>
      <Route path="/login.html" element={<LoginPage />} />
      <Route path="/" element={<Navigate to="/index.html" replace />} />
      {/* 已移植页 */}
      <Route path="/settings.html" element={<WithShell><SettingsPage /></WithShell>} />
      <Route path="/profile.html" element={<WithShell><ProfilePage /></WithShell>} />
      <Route path="/search.html" element={<WithShell perm="search:use"><SearchPage /></WithShell>} />
      <Route path="/tree.html" element={<WithShell perm="tree:use"><TreePage /></WithShell>} />
      <Route path="/index.html" element={<WithShell perm="chat:use"><IndexPage /></WithShell>} />
      <Route path="/manage.html" element={<WithShell perm="doc:read"><ManagePage /></WithShell>} />
      {/* 后台 9 个面板 */}
      <Route path="/users.html" element={<WithShell><AdminLayout /></WithShell>} />
      <Route path="/org.html" element={<WithShell><AdminLayout /></WithShell>} />
      <Route path="/roles.html" element={<WithShell><AdminLayout /></WithShell>} />
      <Route path="/perms.html" element={<WithShell><AdminLayout /></WithShell>} />
      <Route path="/docs.html" element={<WithShell><AdminLayout /></WithShell>} />
      <Route path="/models.html" element={<WithShell><AdminLayout /></WithShell>} />
      <Route path="/usage.html" element={<WithShell><AdminLayout /></WithShell>} />
      <Route path="/loginlogs.html" element={<WithShell><AdminLayout /></WithShell>} />
      <Route path="/audit.html" element={<WithShell><AdminLayout /></WithShell>} />
      <Route path="/admin.html" element={<WithShell><AdminLayout /></WithShell>} />
      {/* 未移植页：暂回退 static */}
      <Route path="/forgot.html" element={<StaticFallback page="forgot.html" />} />
      <Route path="*" element={<Navigate to="/index.html" replace />} />
    </Routes>
  )
}
