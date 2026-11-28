/** 分片预览弹框：知识库文档页与后台文档管理页共用（旧版 Shell.showChunks） */
import { useEffect, useState } from 'react'
import WbModal from '@/components/WbModal'
import { http } from '@/api/http'
import '@/styles/manage.css'

interface Chunk { chunkIndex: number; content: string }

export default function ChunkPreview({ fileName, onClose }: { fileName: string | null; onClose: () => void }) {
  const [list, setList] = useState<Chunk[]>([])
  const [error, setError] = useState('')
  const [open, setOpen] = useState<Set<number>>(new Set())
  const [full, setFull] = useState<Set<number>>(new Set())

  useEffect(() => {
    if (!fileName) return
    // 默认展开的第一段也要直接显示全文，避免「已展开还得再点展开全文」
    setList([]); setError(''); setOpen(new Set([0])); setFull(new Set([0]))
    let alive = true
    http.get<{ chunks?: Chunk[]; message?: string }>('/api/docs/chunks?fileName=' + encodeURIComponent(fileName))
      .then(j => {
        if (!alive) return
        if (!j.chunks) throw new Error(j.message || '读取失败')
        setList(j.chunks)
      })
      .catch((e: any) => { if (alive) setError(e?.message || '读取失败') })
    return () => { alive = false }
  }, [fileName])

  /** 展开一段就直接显示该段全文，不再让用户点第二下「展开全文」 */
  const toggle = (i: number) => {
    const opening = !open.has(i)
    setOpen(prev => { const s = new Set(prev); opening ? s.add(i) : s.delete(i); return s })
    setFull(prev => { const s = new Set(prev); opening ? s.add(i) : s.delete(i); return s })
  }
  const allOpen = list.length > 0 && open.size === list.length
  const expandAll = () => {
    if (allOpen) { setOpen(new Set()); setFull(new Set()); return }
    setOpen(new Set(list.map((_, i) => i)))
    setFull(new Set(list.map((_, i) => i)))
  }

  // 显示只取文件名本身，目录路径不展示；接口查询仍用完整 fileName
  const displayName = fileName ? fileName.substring(fileName.lastIndexOf('/') + 1) : ''

  return (
    <WbModal size="chunk" title={'分片预览 · ' + displayName} open={!!fileName} okText="关闭" cancelText={null}
      onOk={onClose} onCancel={onClose}>
      {error
        ? <div className="chunk-empty">加载失败：{error}</div>
        : !list.length
          ? <div className="chunk-empty">正在读取分片…</div>
          : <div className="chunk-tree">
            <div className="chunk-root">
              <span className="cf-name" title={displayName}>{displayName}</span>
              <span className="tag">共 {list.length} 片</span>
              <button className="btn btn-sm chunk-all" style={{ marginLeft: 'auto' }} onClick={expandAll}>{allOpen ? '全部收起' : '全部展开'}</button>
            </div>
            {list.map((c, i) => {
              const txt = String(c.content || '')
              const isOpen = open.has(i)
              const isFull = full.has(i)
              return <div className={`chunk-item${isOpen ? ' open' : ''}${isFull ? ' full' : ''}`} key={i}>
                <div className="chunk-head" onClick={() => toggle(i)}>
                  <span className="chunk-caret">{isOpen ? '▾' : '▸'}</span>
                  <span className="chunk-no">第 {Number(c.chunkIndex || 0) + 1} 片</span>
                  <span className="chunk-preview">{txt.slice(0, 90).replace(/\s+/g, ' ')}</span>
                  <span className="chunk-len">{txt.length} 字</span>
                </div>
                <div className="chunk-body">{txt}</div>
              </div>
            })}
          </div>}
    </WbModal>
  )
}
