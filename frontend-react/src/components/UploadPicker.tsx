/**
 * 批量上传清单：对齐旧版 static 页 openUploadPicker 的交互。
 * 选完文件先列清单让人逐个核对，默认全选，格式不支持/超限的自动排除并标注原因。
 */
import { useEffect, useMemo, useState } from 'react'
import WbModal from '@/components/WbModal'
import '@/styles/admin.css'

interface Props {
  open: boolean
  files: File[]
  /** 后端支持的不带点的扩展名（大写） */
  supportedExts: string[]
  maxBytes?: number
  onCancel: () => void
  /** 只回传用户勾选中的文件与可见范围选择 */
  onConfirm: (files: File[], includeSubtree: boolean) => void
}

function extOf(name: string): string {
  const i = String(name || '').lastIndexOf('.')
  return i < 0 ? '' : String(name).slice(i + 1).toUpperCase()
}

function fmtSize(n: number): string {
  if (!n) return '0B'
  if (n < 1024) return n + 'B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + 'KB'
  return (n / 1024 / 1024).toFixed(1) + 'MB'
}

export default function UploadPicker({ open, files, supportedExts, maxBytes, onCancel, onConfirm }: Props) {
  const items = useMemo(() => files.map(f => {
    const ext = extOf(f.name)
    // 调用方传来的扩展名可能是 .txt / TXT / txt 三种写法，统一成大写不带点比较
    const norm = (e: string) => String(e || '').toUpperCase().replace(/^\./, '')
    const supported = !supportedExts.length || supportedExts.some(e => norm(e) === ext)
    const oversize = !!(maxBytes && f.size > maxBytes)
    return { file: f, name: f.name, size: f.size, ext, supported, oversize, bad: !supported || oversize }
  }), [files, supportedExts, maxBytes])

  const usable = items.filter(x => !x.bad)
  const [checked, setChecked] = useState<Set<number>>(() => new Set(items.map((_, i) => i)))
  const [subtree, setSubtree] = useState(false)

  // 弹框重新打开（新一批文件）时重置为全选
  const key = files.map(f => f.name + ':' + f.size).join('|')
  useEffect(() => { setChecked(new Set(items.map((_, i) => i))) }, [key])

  const usableIdx = items.map((x, i) => x.bad ? -1 : i).filter(i => i >= 0)
  const chosen = usableIdx.filter(i => checked.has(i)).map(i => items[i])
  const chosenBytes = chosen.reduce((s, x) => s + x.size, 0)
  const allChecked = usableIdx.length > 0 && chosen.length === usableIdx.length

  function toggleAll(on: boolean) {
    const next = new Set(checked)
    usableIdx.forEach(i => on ? next.add(i) : next.delete(i))
    setChecked(next)
  }

  return <WbModal size="md" title="选择要上传的文件" open={open}
    okText={`上传所选（${chosen.length}）`} cancelText="取消"
    onCancel={onCancel}
    onOk={() => { if (chosen.length) onConfirm(chosen.map(x => x.file), subtree) }}>
    <div className="pick-summary">
      <label className="pick-all">
        <input type="checkbox" checked={allChecked} onChange={e => toggleAll(e.target.checked)} />全选
      </label>
      <span className="pick-count">已选 {chosen.length} / {usable.length} 个 · 合计 {fmtSize(chosenBytes)}</span>
      {items.length > usable.length &&
        <span className="pick-tip">{items.length - usable.length} 个已自动排除</span>}
    </div>
    <div className="pick-list">
      {items.map((it, i) => <label className={'pick-row' + (it.bad ? ' skip' : '')} key={i}>
        <input type="checkbox" className="pick-cb" disabled={it.bad}
          checked={!it.bad && checked.has(i)}
          onChange={e => {
            const next = new Set(checked)
            e.target.checked ? next.add(i) : next.delete(i)
            setChecked(next)
          }} />
        <span className="pick-name" title={it.name}>{it.name}</span>
        <span className="pick-size">{fmtSize(it.size)}</span>
        <span className={'pick-tag' + (it.bad ? ' err' : ' ok')}>
          {!it.supported ? '格式不支持' : it.oversize ? '超过单文件上限' : '就绪'}
        </span>
      </label>)}
    </div>
    <label className="pick-scope" title="勾选后，本部门及其下级组织都能检索到这些文档">
      <input type="checkbox" checked={subtree} onChange={e => setSubtree(e.target.checked)} />
      含下级部门可见（默认仅本部门 + 上级）
    </label>
  </WbModal>
}
