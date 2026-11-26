/** 分页条：与旧版 renderPager 1:1（共 N 条 | 每页 N 条 | 首页 上一页 页码 下一页 末页 | 跳至 N） */
import { useEffect, useState } from 'react'

/** 页码窗口：首页/末页 + 当前页前后 2 页，中间空档用 0 表示省略号 */
function pageWindow(page: number, pages: number): number[] {
  const win = new Set<number>([1, pages])
  for (let p = Math.max(1, page - 2); p <= Math.min(pages, page + 2); p++) win.add(p)
  const out: number[] = []
  let prev = 0
  for (const p of [...win].sort((a, b) => a - b)) {
    if (prev && p - prev > 1) out.push(0)
    out.push(p)
    prev = p
  }
  return out
}

interface Props {
  total: number
  page: number
  size: number
  onChange: (p: number) => void
  /** 容器 id（仅用于 DOM 对照，可省） */
  id?: string
}

export default function Pager({ total, page, size, onChange, id }: Props) {
  const pages = Math.max(1, Math.ceil(total / size))
  const cur = Math.min(Math.max(page, 1), pages)
  const [jump, setJump] = useState(String(cur))
  useEffect(() => { setJump(String(cur)) }, [cur])

  function goJump() {
    const n = parseInt(jump, 10)
    onChange(!n || n < 1 ? 1 : Math.min(n, pages))
  }

  return (
    <div className="pager" id={id}>
      <span>共 {total} 条</span>
      <span style={{ color: '#c3cddd' }}>|</span>
      <span>每页 {size} 条</span>
      <button className="pg-btn" disabled={cur <= 1} onClick={() => onChange(1)}>首页</button>
      <button className="pg-btn" disabled={cur <= 1} onClick={() => onChange(cur - 1)}>上一页</button>
      {pageWindow(cur, pages).map((p, i) => p === 0
        ? <span className="pg-sep" key={'sep' + i}>…</span>
        : <button key={p} className={`pg-btn${p === cur ? ' on' : ''}`} onClick={() => onChange(p)}>{p}</button>)}
      <button className="pg-btn" disabled={cur >= pages} onClick={() => onChange(cur + 1)}>下一页</button>
      <button className="pg-btn" disabled={cur >= pages} onClick={() => onChange(pages)}>末页</button>
      <span className="pg-info">
        第 {cur} / {pages} 页 · 跳至
        <input type="number" min={1} max={pages} value={jump} aria-label="跳转到页码"
          onChange={e => setJump(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter') goJump() }} />
        <button className="pg-btn" onClick={goJump}>跳转</button>
      </span>
    </div>
  )
}
