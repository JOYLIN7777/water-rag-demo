/** 知识库文档管理：DOM/class 与旧版 manage.html 1:1，上传/分片/删除/分页全功能 */
import { useEffect, useRef, useState } from 'react'
import { message } from 'antd'
import WbModal from '@/components/WbModal'
import ChunkPreview from '@/components/ChunkPreview'
import { confirmDialog } from '@/lib/ui'
import { http } from '@/api/http'
import { uploadDocument } from '@/api'
import { getUser } from '@/api/auth'
import { useAuthStore } from '@/store/auth'
import '@/styles/manage.css'
import TopbarNav from '@/components/TopbarNav'
import AdminEntry from '@/components/AdminEntry'

interface DocItem {
  fileName: string
  chunks: number
  chars?: number
  tenantId?: string
  visibleSubtree?: boolean
  uploadedAt?: string
}

const FMT_GROUPS = [
  { name: '文本与网页', exts: ['.txt', '.md', '.html', '.htm'] },
  { name: 'Office 文档', exts: ['.pdf', '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx'] },
  { name: '数据表格', exts: ['.csv', '.tsv', '.json'] }
]
const PAGE_SIZE = 10

function fmtTime(s?: string): string {
  if (!s) return '-'
  const d = new Date(String(s).replace(/(\.\d{3})\d+/, '$1'))
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}

export default function ManagePage() {
  const auth = useAuthStore()
  const fileInput = useRef<HTMLInputElement>(null)
  const folderInput = useRef<HTMLInputElement>(null)
  const [docs, setDocs] = useState<DocItem[]>([])
  const [formats, setFormats] = useState<string[]>([])
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(1)
  const [jump, setJump] = useState('')
  const [msg, setMsg] = useState<{ type: string; text: string } | null>(null)
  const [dragging, setDragging] = useState(false)
  const [chunkFile, setChunkFile] = useState<string | null>(null)
  const [scopeOpen, setScopeOpen] = useState(false)
  const [pendingFiles, setPendingFiles] = useState<File[]>([])
  const [includeSubtree, setIncludeSubtree] = useState(false)

  function showMsg(type: string, text: string) { setMsg({ type, text }) }

  async function loadDocs() {
    try {
      const j = await http.get<DocItem[]>('/api/docs/list')
      if (!Array.isArray(j)) throw new Error((j as any)?.message || '登录状态已失效，请重新登录后再试')
      const sorted = j.slice().sort((a, b) => String(b.uploadedAt || '').localeCompare(String(a.uploadedAt || '')))
      setDocs(sorted)
    } catch (e: any) { showMsg('err', '加载失败：' + (e?.message || '')) }
  }

  useEffect(() => {
    void loadDocs()
    void http.get<{ extensions: string[] }>('/api/docs/formats')
      .then(j => setFormats(j.extensions || []))
      .catch(() => showMsg('err', '格式列表加载失败'))
  }, [])

  const filtered = docs.filter(d => !search.trim() || d.fileName.toLowerCase().includes(search.trim().toLowerCase()))
  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE))
  const cur = Math.min(page, totalPages)
  const slice = filtered.slice((cur - 1) * PAGE_SIZE, cur * PAGE_SIZE)
  const myOrg = getUser()?.tenant || ''
  // 后端 /api/docs/formats 返回的是不带点的大写扩展名（CSS/CSV/DOC…），
  // 这里统一成小写带点再与分组比较，否则所有格式都会落到「其他」。
  const normExt = (e: string) => '.' + String(e || '').trim().toLowerCase().replace(/^\./, '')
  const actualExts = formats.map(normExt)
  const usedExts = new Set<string>()
  const fmtGroups: Array<{ name: string; list: string[] }> = FMT_GROUPS.map(g => {
    const list = g.exts.filter(e => actualExts.includes(e))
    list.forEach(e => usedExts.add(e))
    return { ...g, list }
  }).filter(g => g.list.length)
  const rest = actualExts.filter(e => !usedExts.has(e))
  if (rest.length) fmtGroups.push({ name: '其他', list: rest })

  function scopeBadge(d: DocItem): React.ReactNode {
    const t = d.tenantId || 'default'
    if (!myOrg || t === myOrg) {
      return d.visibleSubtree
        ? <span className="scope sub" title="本部门上传，且已勾选含下级部门下发">{myOrg} · 含下级</span>
        : <span className="scope" title="本部门上传：本部门与上级可见">本部门</span>
    }
    return <span className="scope from" title="上级部门下发或下级部门上传（上级默认可见）">来自 {t}</span>
  }

  async function removeDoc(d: DocItem) {
    const ok = await confirmDialog({
      title: '删除文档',
      content: <p style={{ margin: 0 }}>确定删除「{d.fileName}」及其全部 {d.chunks} 个片段吗？删除后相关内容将无法再被问答引用，且无法恢复。</p>,
      okText: '确认删除',
      danger: true
    })
    if (!ok) return
    showMsg('', `正在删除 ${d.fileName} ...`)
    try {
      const q = '?fileName=' + encodeURIComponent(d.fileName) + (d.tenantId ? '&tenantId=' + encodeURIComponent(d.tenantId) : '')
      const j = await http.delete<{ deletedChunks?: number; message?: string }>('/api/docs' + q)
      if (j.deletedChunks === undefined) throw new Error(j.message || '删除失败')
      showMsg('ok', `✓ 已删除「${d.fileName}」（${j.deletedChunks} 个切片）`)
      message.success('文档已删除')
      await loadDocs()
    } catch (e: any) { showMsg('err', '✗ 删除失败：' + (e?.message || '')) }
  }


  /** 选完文件弹框确认可见范围，取消则不上传 */
  function pickFiles(files: FileList | File[] | null) {
    const list = Array.from(files || [])
    if (!list.length) return
    setPendingFiles(list)
    setIncludeSubtree(false)
    setScopeOpen(true)
  }

  async function doUpload() {
    const list = pendingFiles
    setScopeOpen(false)
    setPendingFiles([])
    let ok = 0, fail = 0, totalChunks = 0
    for (let i = 0; i < list.length; i++) {
      const f = list[i]
      showMsg('', `[${i + 1}/${list.length}] 正在解析 ${f.name} …`)
      const started = Date.now()
      try {
        const j = await uploadDocument(f, undefined, includeSubtree)
        ok++; totalChunks += j.chunks
        const secs = ((Date.now() - started) / 1000).toFixed(1)
        showMsg('', `[${i + 1}/${list.length}] ✓ ${f.name}：${j.chunks} 片（${j.chars} 字，${secs} 秒）`)
      } catch (e: any) { fail++; showMsg('err', `[${i + 1}/${list.length}] ✗ ${f.name}：${e?.message || ''}`) }
    }
    showMsg(fail ? 'err' : 'ok', `✓ 完成：成功 ${ok} 份 / 失败 ${fail} 份，共 ${totalChunks} 个切片，已可提问`)
    message.success(fail ? `完成，${fail} 份失败` : `${ok} 份文档已入库`)
    await loadDocs()
  }

  const pagerNums: Array<number | '…'> = []
  for (let p = 1; p <= totalPages; p++) {
    if (p === 1 || p === totalPages || Math.abs(p - cur) <= 1) pagerNums.push(p)
    else if (pagerNums[pagerNums.length - 1] !== '…') pagerNums.push('…')
  }

  return (
    <>
      <header className="topbar">
        <div className="topbar-left"><TopbarNav /><div className="topbar-title">知识库文档管理</div><span className="topbar-sub">/ 文档</span></div>
        <div className="topbar-nav"><AdminEntry /></div>
      </header>
      <div className="page">
        <div className="page-head">
          <h1 className="page-title">知识库文档管理</h1>
          <p className="page-desc">上传、查看与删除知识库文档。上传后系统会自动解析，稍等片刻即可开始提问。</p>
        </div>

        <div className="stats">
          <div className="stat-card"><div className="stat-num">{docs.length}</div><div className="stat-label">已入库文档数</div></div>
          <div className="stat-card"><div className="stat-num">{docs.reduce((a, d) => a + Number(d.chunks), 0)}</div><div className="stat-label">切片总数</div></div>
          <div className="stat-card">
            <div className="stat-num" style={{ fontSize: 15, lineHeight: 1.9 }}>{fmtTime(docs.map(d => d.uploadedAt || '').sort().pop())}</div>
            <div className="stat-label">最近更新时间</div>
          </div>
        </div>

        <div className="upload-card">
          <div className={`dropzone${dragging ? ' dragging' : ''}`}
            onClick={() => fileInput.current?.click()}
            onDragOver={e => { e.preventDefault(); setDragging(true) }}
            onDragLeave={e => { e.preventDefault(); setDragging(false) }}
            onDrop={e => { e.preventDefault(); setDragging(false); pickFiles(e.dataTransfer.files) }}>
            <div className="dz-icon">⇪</div>
            <div className="dz-title">点击选择，或将文件拖拽到此处</div>
            <div className="dz-sub">解析后自动切片并写入检索库，支持多格式</div>
          </div>
          <div className="upload-side">
            <h4>支持格式 <span className="fmt-total">共 {formats.length} 种</span></h4>
            <div className="fmt-groups">
              {fmtGroups.map(g => (
                <div key={g.name}>
                  <div className="fmt-group-title">{g.name}</div>
                  <div className="fmt-list">{g.list.map(e => <span className="fmt" key={e}>{e.replace(/^\./, '').toUpperCase()}</span>)}</div>
                </div>
              ))}
            </div>
            {auth.has('doc:write_batch') && (
              <div className="folder-row">
                <button className="folder-btn" onClick={() => folderInput.current?.click()}
                  title="同一页面内重复点击会直接读取上次那个文件夹">📁 选择文件夹批量上传</button>
              </div>
            )}
            <div className="hint" style={{ margin: 0 }}>单个文件最大 500MB，文件夹上传会自动遍历支持的类型。</div>
          </div>
          <input ref={fileInput} type="file" multiple hidden onChange={e => { pickFiles(e.target.files); e.target.value = '' }} />
          <input ref={folderInput} type="file" multiple hidden {...{ webkitdirectory: '' }} onChange={e => { pickFiles(e.target.files); e.target.value = '' }} />
        </div>

        {msg && <div className={`upload-msg ${msg.type}`}>{msg.text}</div>}

        <div className="toolbar">
          <input className="search" type="text" placeholder="按文件名过滤…" value={search}
            onChange={e => { setSearch(e.target.value); setPage(1) }} />
          <span className="hint" style={{ margin: 0 }}>共 {docs.length} 份文档</span>
        </div>

        <div className="table-card">
          <div className="scroll"><table>
            <thead><tr><th style={{ width: '38%' }}>文件名</th><th style={{ width: 130 }}>所属部门</th><th>切片数</th><th>最近上传时间</th><th style={{ width: 170 }}>操作</th></tr></thead>
            <tbody id="docTbody">
              {slice.length === 0
                ? <tr><td colSpan={5} className="empty">{search ? '无匹配文档' : (auth.has('doc:write') ? '知识库暂无文档，先上传一份吧' : '知识库暂无文档')}</td></tr>
                : slice.map(d => (
                  <tr key={d.fileName + (d.tenantId || '')}>
                    <td className="doc-name" title={d.fileName}>{d.fileName}</td>
                    <td>{scopeBadge(d)}</td>
                    <td><span className="tag">{d.chunks} 片</span></td>
                    <td>{fmtTime(d.uploadedAt)}</td>
                    <td>
                      <div className="doc-ops">
                        {auth.has('doc:chunks') && <button className="btn btn-sm btn-chunks" onClick={() => setChunkFile(d.fileName)}>查看分片</button>}
                        {auth.has('doc:delete') && <button className="btn btn-sm btn-danger" onClick={() => void removeDoc(d)}>删除</button>}
                      </div>
                    </td>
                  </tr>
                ))}
            </tbody>
          </table></div>
          {filtered.length > 0 && (
            <div className="pager">
              <span>共 {filtered.length} 份 · 第 {cur}/{totalPages} 页</span>
              <button onClick={() => setPage(1)} disabled={cur <= 1}>«</button>
              <button onClick={() => setPage(cur - 1)} disabled={cur <= 1}>‹</button>
              {pagerNums.map((p, i) => p === '…'
                ? <span key={'e' + i} style={{ padding: '0 2px' }}>…</span>
                : <button key={p} className={p === cur ? 'cur' : ''} onClick={() => setPage(p)}>{p}</button>)}
              <button onClick={() => setPage(cur + 1)} disabled={cur >= totalPages}>›</button>
              <button onClick={() => setPage(totalPages)} disabled={cur >= totalPages}>»</button>
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, marginLeft: 8 }}>
                跳至 <input type="number" min={1} max={totalPages} value={jump} onChange={e => setJump(e.target.value)}
                  onKeyDown={e => {
                    if (e.key !== 'Enter') return
                    let p = parseInt(jump, 10)
                    if (!p || p < 1) p = 1
                    if (p > totalPages) p = totalPages
                    setPage(p)
                  }} style={{ width: 46, padding: '4px 6px', fontSize: 12, border: '1px solid var(--line)', borderRadius: 6, textAlign: 'center' }} /> 页
              </span>
            </div>
          )}
        </div>
      </div>

      {/* 上传前确认可见范围（旧版 askVisibility 弹框） */}
      <WbModal size="md" title="文档可见范围" open={scopeOpen} okText="确认上传" cancelText="取消"
        onCancel={() => { setScopeOpen(false); setPendingFiles([]) }} onOk={() => { if (pendingFiles.length) void doUpload() }}>
        <p style={{ margin: '0 0 12px' }}>已选择 {pendingFiles.length} 个文件，请确认文档可见范围：</p>
        <label style={{ display: 'flex', gap: 8, alignItems: 'flex-start', marginBottom: 10, cursor: 'pointer' }}>
          <input type="radio" name="visScope" checked={!includeSubtree} onChange={() => setIncludeSubtree(false)} />
          <span><b>仅本部门 + 上级</b><div style={{ fontSize: 11.5, color: 'var(--muted)' }}>只有本部门及上级组织能看到、检索到这些文档</div></span>
        </label>
        <label style={{ display: 'flex', gap: 8, alignItems: 'flex-start', cursor: 'pointer' }}>
          <input type="radio" name="visScope" checked={includeSubtree} onChange={() => setIncludeSubtree(true)} />
          <span><b>含下级部门可见</b><div style={{ fontSize: 11.5, color: 'var(--muted)' }}>本部门及其下级组织都能看到、检索到这些文档</div></span>
        </label>
      </WbModal>

      <ChunkPreview fileName={chunkFile} onClose={() => setChunkFile(null)} />
    </>
  )
}
