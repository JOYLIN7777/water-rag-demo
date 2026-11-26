/** 后台通用 UI 助手：统一确认弹框、时间格式化、数字格式化 */
import { Modal } from 'antd'
import type { ReactNode } from 'react'

/**
 * 统一确认弹框。
 *
 * <p>全站禁止用 window.confirm（浏览器原生框丑且与后台风格割裂），一律走 AntD Modal。
 * 返回 Promise<boolean>：确认 true，取消/关闭 false。</p>
 */
export function confirmDialog(opts: {
  title: string
  content?: ReactNode
  okText?: string
  cancelText?: string
  danger?: boolean
}): Promise<boolean> {
  return new Promise<boolean>(resolve => {
    Modal.confirm({
      title: opts.title,
      content: opts.content,
      okText: opts.okText || '确定',
      cancelText: opts.cancelText || '取消',
      okButtonProps: opts.danger ? { danger: true } : undefined,
      onOk: () => resolve(true),
      onCancel: () => resolve(false)
    })
  })
}

/** 只有提示、没有取消按钮的信息弹框（对齐旧版 Shell.modal 的 cancelText:''） */
export function infoDialog(opts: { title: string; content?: ReactNode; okText?: string }): void {
  Modal.info({
    title: opts.title,
    content: opts.content,
    okText: opts.okText || '知道了'
  })
}

/** 后端时间戳统一转本地时间显示：MM-DD HH:mm:ss（与旧版 tsLocal 一致） */
export function tsLocal(ts?: string | number | null): string {
  if (!ts) return '—'
  const d = new Date(ts)
  if (isNaN(d.getTime())) return String(ts).slice(5, 19)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** 千分位数字（空值按 0 显示） */
export function num(v?: number | null): string {
  return Number(v || 0).toLocaleString('zh-CN')
}
