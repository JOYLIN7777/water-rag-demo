/** 后台-文档管理：DOM/class 与旧版 admin.html docs 面板 1:1（独立面板，不复用问答侧整页） */
import { useEffect, useRef, useState } from 'react'
import { message } from 'antd'
import WbModal from '@/components/WbModal'
import Pager from '@/components/Pager'
import ChunkPreview from '@/components/ChunkPreview'
import { confirmDialog } from '@/lib/ui'
import { http } from '@/api/http'
import { useAuthStore } from '@/store/auth'
import '@/styles/admin.css'
import '@/styles/manage.css'

interface Doc { fileName: string; displayName?: string; chunks: number; uploadedAt?: string; ownerUser?: string; tenantId?: string; visibleSubtree?: boolean }
interface Org { key: string; name: string }

const PAGE_SIZE = 10

export default function DocsPanel() {
  const auth = useAuthStore()
  const [docs, setDocs] = useState<Doc[]>([])
  const [orgs, setOrgs] = useState<Org[]>([])
  const [page, setPage] = useState(1)
  const [err, setErr] = useState('')
  const [uploadMsg, setUploadMsg] = useState('可多选文件，也可直接拖到下方列表；上传时可指定可见范围')
  const [picked, setPicked] = useState<File[] | null>(null)
  const [subtree, setSubtree] = useState(false)
  const [chunkFile, setChunkFile] = useState<string | null>(null)
  const [dragging, setDragging] = useState(false)
  const fileRef = useRef<HTMLInputElement>(null)

  async function load() {
    try {
      const [d, o] = await Promise.all([
        http.get<Doc[]>('/api/docs/list'),
        http.get<Org[]>('/api/orgs').catch(() => [] as Org[])
      ])
      setDocs(Array.isArray(d) ? d.slice().sort((a, b) => String(b.uploadedAt || '').localeCompare(String(a.uploadedAt || ''))) : [])
      setOrgs(Array.isArray(o) ? o : [])
      setErr('')
    } catch (e: any) { setErr(e?.message || '加载失败') }
  }
  useEffect(() => { void load() }, [])

  const orgName = (k?: string) => orgs.find(o => o.key === (k || 'default'))?.name || k || 'default'
  const total = docs.length
  const rows = docs.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE)

  /** 选完文件先确认可见范围（与旧版 Shell.askVisibility 一致） */
  function askVisibility(files: File[]) {
    setSubtree(false)
    setPicked(files)
  }

  async function doUpload() {
    const files = picked || []
    setPicked(null)
    if (!files.length) return
    let ok = 0, fail = 0
    for (let i = 0; i < files.length; i++) {
      const fd = new FormData()
      fd.append('file', files[i])
      if (subtree) fd.append('includeSubtree', 'true')
      setUploadMsg(`正在入库 ${files[i].name}（${i + 1}/${files.length}）…`)
      try {
        const r = await fetch('/api/docs/upload', { method: 'POST', body: fd })
        const j = await r.json().catch(() => ({} as any))
        if (!r.ok) throw new Error(j.message || '上传失败')
        ok++
      } catch { fail++ }
    }
    setUploadMsg(`完成：成功 ${ok} 个${fail ? `，失败 ${fail} 个` : ''}`)
    if (fileRef.current) fileRef.current.value = ''
    if (ok) message.success('文档入库完成')
    await load()
  }

  async function remove(d: Doc) {
    const ok = await confirmDialog({
      title: '删除文档',
      danger: true,
      okText: '确认删除',
      content: <>
        <p style={{ margin: 0 }}>确定删除「{d.displayName || d.fileName}」及其全部 {d.chunks} 个片段吗？删除后无法恢复。</p>
        {d.tenantId && <p style={{ margin: '6px 0 0', color: 'var(--muted)', fontSize: 12 }}>
          仅删除「{orgName(d.tenantId)}」部门下的同名文档，不影响其他部门。
        </p>}
      </>
    })
    if (!ok) return
    try {
      const q = '?fileName=' + encodeURIComponent(d.fileName) + (d.tenantId ? '&tenantId=' + encodeURIComponent(d.tenantId) : '')
      await http.delete('/api/docs' + q)
      message.success('已删除文档')
      await load()
    } catch (e: any) { message.error(e?.message || '删除失败') }
  }

  return (
    <>
      <div className="page-head"><h1 className="page-title">文档管理</h1>
        <p className="page-desc">管理知识库中的文档，删除会同时移除其全部片段。</p></div>
      {err && <div className="msg err show">{err}</div>}
      <div className="card" style={{ padding: 0 }}>
        <div className="toolbar" style={{ background: '#fff', border: '1px solid var(--line)', borderBottom: 0, borderRadius: 'var(--radius) var(--radius) 0 0' }}>
          <input ref={fileRef} type="file" multiple hidden onChange={e => askVisibility(Array.from(e.target.files || []))} />
          {auth.has('doc:write_admin') &&
            <button className="btn btn-sm btn-primary" onClick={() => fileRef.current?.click()}>＋ 选择文件</button>}
          <span style={{ fontSize: 12, color: 'var(--muted)' }}>{uploadMsg}</span>
          <span className="grow" />
          <button className="btn btn-sm" onClick={() => void load()}>刷新</button>
        </div>
        <div className="table-wrap"
          style={{ border: '1px solid var(--line)', borderRadius: '0 0 var(--radius) var(--radius)', background: dragging ? '#f2f6ff' : '#fff' }}
          onDragOver={e => { e.preventDefault(); setDragging(true) }}
          onDragLeave={() => setDragging(false)}
          onDrop={e => {
            e.preventDefault(); setDragging(false)
            const fs = Array.from(e.dataTransfer?.files || [])
            if (fs.length) askVisibility(fs)
          }}>
          <table className="data-table">
            <thead><tr>
              <th>文档名称</th><th>所属部门 / 可见范围</th><th>片段数</th><th>上传人</th><th>上传时间</th><th>操作</th>
            </tr></thead>
            <tbody>
              {rows.length === 0
                ? <tr><td colSpan={6} className="empty">知识库暂无文档</td></tr>
                : rows.map(d => <tr key={(d.tenantId || '') + d.fileName}>
                  <td className="name">{d.displayName || d.fileName}</td>
                  <td><span className="tag" title="文档归属部门与可见范围">
                    {orgName(d.tenantId)}{d.visibleSubtree ? ' · 含下级' : ' · 仅本部门+上级'}
                  </span></td>
                  <td>{d.chunks}</td>
                  <td>{d.ownerUser || '—'}</td>
                  <td>{String(d.uploadedAt || '').slice(0, 19).replace('T', ' ')}</td>
                  <td>
                    {auth.has('doc:chunks') &&
                      <button className="op-btn" onClick={() => setChunkFile(d.fileName)}>查看分片</button>}
                    {auth.has('doc:delete') &&
                      <button className="btn btn-sm btn-danger" style={{ marginLeft: 8 }} onClick={() => void remove(d)}>删除</button>}
                  </td>
                </tr>)}
            </tbody>
          </table>
        </div>
        <Pager id="dPager" total={total} page={page} size={PAGE_SIZE} onChange={setPage} />
      </div>

      {/* 上传前确认可见范围 */}
      <WbModal size="md" title={`上传 ${picked?.length || 0} 个文件 · 选择可见范围`} open={!!picked}
        okText="确认上传" cancelText="取消" onOk={() => void doUpload()} onCancel={() => setPicked(null)}>
        <div className="form-note">可见范围决定哪些部门能在问答里检索到这些文档，上传后不易更改。</div>
        <label style={{ display: 'flex', gap: 8, alignItems: 'flex-start', cursor: 'pointer', marginBottom: 12 }}>
          <input type="radio" name="docScope" checked={!subtree} onChange={() => setSubtree(false)} />
          <span><b>仅本部门（含上级）</b>
            <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>只有本部门及其上级组织能检索到</div></span>
        </label>
        <label style={{ display: 'flex', gap: 8, alignItems: 'flex-start', cursor: 'pointer' }}>
          <input type="radio" name="docScope" checked={subtree} onChange={() => setSubtree(true)} />
          <span><b>含下级部门可见</b>
            <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>本部门及其下级组织都能看到、检索到这些文档</div></span>
        </label>
        <div className="form-hint">{picked?.map(f => f.name).join('、')}</div>
      </WbModal>

      <ChunkPreview fileName={chunkFile} onClose={() => setChunkFile(null)} />
    </>
  )
}
