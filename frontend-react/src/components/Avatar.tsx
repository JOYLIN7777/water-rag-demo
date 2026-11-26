/** 头像：按账号名稳定哈希取色，与旧版 app.js avatar() 一致 */
import type { CSSProperties } from 'react'

const AV_PALETTE: Array<[string, string]> = [
  ['#5b8bff', '#3b6ef5'], ['#f0a04b', '#e07a2f'], ['#48c6a5', '#0e9f6e'],
  ['#9b7ee8', '#7c5cd6'], ['#ef6b94', '#e0245e'], ['#4bb8d8', '#2a90b8']
]

function hashHue(name: string): number {
  let h = 0
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0
  return h
}

export function Avatar({ name, size = 'md', src }: { name: string; size?: 'sm' | 'md' | 'lg' | 'xl'; src?: string }) {
  const n = String(name == null || name === '' ? '?' : name).trim()
  const ch = n.slice(0, 1).toUpperCase()
  const pair = AV_PALETTE[hashHue(n) % AV_PALETTE.length]
  const style: CSSProperties = { background: `linear-gradient(135deg,${pair[0]},${pair[1]})` }
  // 用户自定义头像：上传图片(data URL) 或 预设 emoji，优先于首字母
  if (src && /^data:image\//.test(src)) {
    return <img className={`av av-${size} av-img`} src={src} alt={n} />
  }
  if (src) return <span className={`av av-${size} av-emoji`} style={{ background: style.background }}>{src}</span>
  return <span className={`av av-${size}`} style={style}>{ch}</span>
}

/** 相对时间：与旧版 fmtTime 一致 */
export function fmtTime(ts?: number): string {
  if (!ts) return ''
  const d = new Date(ts), now = new Date()
  if (isNaN(d.getTime())) return ''
  let diff = now.getTime() - d.getTime()
  if (diff < 0) diff = 0
  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min}分钟前`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour}小时前`
  const day = Math.floor(hour / 24)
  if (day < 30) return `${day}天前`
  const month = Math.floor(day / 30)
  if (month < 12) return `${month}个月前`
  return `${Math.floor(month / 12)}年前`
}
