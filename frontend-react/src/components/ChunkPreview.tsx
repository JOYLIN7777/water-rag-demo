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
    setList([]); setError(''); setOpen(new Set([0])); setFull(new Set())
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

  const toggle = (i: number) => setOpen(prev => { const s = new Set(prev); s.has(i) ? s.delete(i) : s.add(i); return s })
  const expandAll = () => {
    setOpen(new Set(list.map((_, i) => i)))
    setFull(new Set(list.map((_, i) => i)))
  }

  return (
    <WbModal size="chunk" title={'分片预览 · ' + (fileName || '')} open={!!fileName} okText="关闭" cancelText={null}
      onOk={onClose} onCancel={onClose}>
      {error
        ? <div className="chunk-empty">加载失败：{error}</div>
        : !list.length
          ? <div className="chunk-empty">正在读取分片…</div>
          : <div className="chunk-tree">
            <div className="chunk-root">
              <span className="cf-name" title={fileName || ''}>{fileName}</span>
              <span className="tag">共 {list.length} 片</span>
              <button className="btn btn-sm chunk-all" style={{ marginLeft: 'auto' }} onClick={expandAll}>全部展开</button>
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
                {txt.length > 240 && <button className="chunk-more"
                  onClick={() => setFull(prev => { const s = new Set(prev); s.has(i) ? s.delete(i) : s.add(i); return s })}>
                  展开全文（{txt.length} 字）
                </button>}
              </div>
            })}
          </div>}
    </WbModal>
  )
}
