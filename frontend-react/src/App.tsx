/** 路由：与旧版 .html 路径一一对应；逐页移植，未移植页回退 static（全程不中断） */
import { useEffect } from 'react'
import { Routes, Route, Navigate, useNavigate } from 'react-router-dom'
import LoginPage from './pages/LoginPage'
import SettingsPage from './pages/SettingsPage'
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
      {/* 未移植页：暂回退 static，做一页换一页 */}
      <Route path="/index.html" element={<StaticFallback page="index.html" />} />
      <Route path="/profile.html" element={<StaticFallback page="profile.html" />} />
      <Route path="/manage.html" element={<StaticFallback page="manage.html" />} />
      <Route path="/search.html" element={<StaticFallback page="search.html" />} />
      <Route path="/tree.html" element={<StaticFallback page="tree.html" />} />
      <Route path="/forgot.html" element={<StaticFallback page="forgot.html" />} />
      <Route path="/admin.html" element={<StaticFallback page="admin.html" />} />
      <Route path="*" element={<Navigate to="/index.html" replace />} />
    </Routes>
  )
}
