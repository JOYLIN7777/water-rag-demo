/** 角色权限勾选树：若依风格三级（目录 → 菜单 → 按钮），父子联动 + 折叠，对齐旧版 permEditor */
import { useMemo, useState } from 'react'
import '@/styles/admin.css'

export interface PermNode {
  code: string
  name: string
  nodeType: string
  parentCode?: string
  orderNum?: number
  routePath?: string
  description?: string
  builtin?: boolean
}

const TYPE_CN: Record<string, string> = { directory: '目录', menu: '菜单', btn: '按钮' }
const TYPE_ICON: Record<string, string> = { directory: '▦', menu: '▤', btn: '◆' }
const lv = (t?: string) => 'perm-lv-' + (t || 'menu')

interface Props {
  perms: PermNode[]
  value: string[]
  onChange: (next: string[]) => void
}

export default function PermTreeEditor({ perms, value, onChange }: Props) {
  const [folded, setFolded] = useState<Set<string>>(new Set())
  const owned = useMemo(() => new Set(value), [value])

  const byParent = useMemo(() => {
    const map: Record<string, PermNode[]> = {}
    perms.forEach(p => { const k = p.parentCode || ''; (map[k] = map[k] || []).push(p) })
    Object.keys(map).forEach(k => map[k].sort((a, b) => (a.orderNum || 0) - (b.orderNum || 0)))
    return map
  }, [perms])

  const roots = perms.filter(p => !p.parentCode)

  /** 某节点下所有真正产生权限码的后代（目录只是分组，不进 rp） */
  function rpDescendants(node: PermNode): string[] {
    const out: string[] = []
    ;(byParent[node.code] || []).forEach(k => {
      if (k.nodeType !== 'directory') out.push(k.code)
      out.push(...rpDescendants(k))
    })
    return out
  }

  function toggle(node: PermNode, checked: boolean) {
    const next = new Set(owned)
    const targets = [...(node.nodeType === 'directory' ? [] : [node.code]), ...rpDescendants(node)]
    targets.forEach(c => checked ? next.add(c) : next.delete(c))
    onChange(Array.from(next))
  }

  function allOn(on: boolean) {
    if (!on) { onChange([]); return }
    onChange(perms.filter(p => p.nodeType !== 'directory').map(p => p.code))
  }

  function renderNode(node: PermNode) {
    const kids = byParent[node.code] || []
    const leaf = !kids.length
    const rpAll = rpDescendants(node)
    const groupChecked = rpAll.length > 0 && rpAll.every(c => owned.has(c))
    const checked = owned.has(node.code) || groupChecked
    const isDir = node.nodeType === 'directory'
    const isFolded = folded.has(node.code)
    return (
      <div key={node.code}>
        <div className={'pt-row' + (isDir ? ' pt-dir' : '')}>
          <span className={'pt-caret' + (leaf ? ' leaf' : '')}
            onClick={() => {
              if (leaf) return
              setFolded(prev => { const s = new Set(prev); s.has(node.code) ? s.delete(node.code) : s.add(node.code); return s })
            }}>{leaf ? '' : (isFolded ? '▸' : '▾')}</span>
          <label>
            <input type="checkbox" checked={checked} onChange={e => toggle(node, e.target.checked)} />
            <span className={'pt-name ' + lv(node.nodeType)}>
              {TYPE_ICON[node.nodeType] || '▤'} {node.name}
            </span>
          </label>
          <span className={'pt-type ' + lv(node.nodeType)}>{TYPE_CN[node.nodeType] || '菜单'}</span>
        </div>
        {!leaf && !isFolded && <div className="pt-children">{kids.map(renderNode)}</div>}
      </div>
    )
  }

  if (!perms.length) return <div className="form-hint">权限节点暂时无法加载，请刷新页面后重试。</div>

  return (
    <>
      <div className="pt-tools">
        <span>勾选父级会同步子级，取消父级会清除子级</span>
        <a onClick={() => allOn(true)}>全选</a>
        <a onClick={() => allOn(false)}>清空</a>
      </div>
      <div className="perm-tree">{roots.map(renderNode)}</div>
    </>
  )
}
