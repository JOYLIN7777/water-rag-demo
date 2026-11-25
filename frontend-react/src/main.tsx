import React from 'react'
import ReactDOM from 'react-dom/client'
import { ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import { installFetchPatch } from './api/auth'
import { useAuthStore } from './store/auth'
import './styles/legacy.css'
import './styles/adapt.css'

// 401 刷新失败或刷新无效 → 回登录页（对齐旧版 goLogin）
installFetchPatch(() => {
  useAuthStore.getState().reset()
  if (!location.pathname.includes('login.html')) location.href = '/login.html'
})

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: '#3b6ef5',
          colorInfo: '#3b6ef5',
          colorSuccess: '#0e9f6e',
          colorError: '#e0245e',
          colorWarning: '#d97706',
          borderRadius: 8,
          fontSize: 13
        }
      }}
    >
      <BrowserRouter><App /></BrowserRouter>
    </ConfigProvider>
  </React.StrictMode>
)
