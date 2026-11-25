/** 路由：与旧版 .html 路径一一对应；逐页移植，未移植页暂时回退到 static 版 */
import { useEffect } from 'react'
import { Routes, Route, Navigate, useLocation, useNavigate } from 'react-router-dom'
import LoginPage from './pages/LoginPage'
import { useAuthStore } from './store/auth'
import { isLoggedIn } from './api/auth'

/** 页面级守卫：未登录弹回登录页（对齐旧版 WrkAuth.guardPage） */
function Guard({ children, perm }: { children: React.ReactNode; perm?: string }) {
  const auth = useAuthStore()
  const navigate = useNavigate()
  const location = useLocation()
  useEffect(() => {
    if (!isLoggedIn()) { navigate('/login.html', { replace: true }); return }
    if (!auth.loaded) void auth.loadPermissions()
  }, [auth, navigate])
  if (!isLoggedIn()) return null
  if (perm && auth.loaded && !auth.has(perm)) return <Navigate to="/profile.html" replace />
  void location
  return <>{children}</>
}

/** 未移植的页面：直接跳到后端 static 版（保证全程不中断、功能不缺） */
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
      <Route path="/index.html" element={<Guard perm="chat:use"><StaticFallback page="index.html" /></Guard>} />
      <Route path="/profile.html" element={<Guard><StaticFallback page="profile.html" /></Guard>} />
      {/* 未移植页：暂回退 static，做一页换一页 */}
      <Route path="/manage.html" element={<StaticFallback page="manage.html" />} />
      <Route path="/search.html" element={<StaticFallback page="search.html" />} />
      <Route path="/tree.html" element={<StaticFallback page="tree.html" />} />
      <Route path="/settings.html" element={<StaticFallback page="settings.html" />} />
      <Route path="/forgot.html" element={<StaticFallback page="forgot.html" />} />
      <Route path="/admin.html" element={<StaticFallback page="admin.html" />} />
      <Route path="*" element={<Navigate to="/index.html" replace />} />
    </Routes>
  )
}
