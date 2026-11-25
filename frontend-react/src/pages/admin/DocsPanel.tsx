/** 后台-文档管理：复用问答侧文档页功能，标题按后台面板口径显示 */
import { useEffect } from 'react'
import ManagePage from '@/pages/ManagePage'

export default function DocsPanel() {
  useEffect(() => {
    const t = document.querySelector('.page-title')
    if (t) t.textContent = '文档管理'
  }, [])
  return <ManagePage />
}
