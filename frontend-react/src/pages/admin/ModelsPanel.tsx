/** 后台-模型设置：DOM/class 与旧版 admin.html models 面板 1:1（新增表单 + 当前生效 + 列表三块） */
import { useEffect, useState } from 'react'
import { message } from 'antd'
import WbModal from '@/components/WbModal'
import { confirmDialog } from '@/lib/ui'
import { http } from '@/api/http'
import '@/styles/admin.css'

interface Model {
  id: number; name: string; type: string; baseUrl: string; model: string
  apiKey?: string; dimensions?: number | null; enabled?: boolean; active?: boolean; isDefault?: boolean
}

const EMPTY = { name: '', type: 'CHAT', baseUrl: '', model: '', apiKey: '', dimensions: '' }
const EDIT_EMPTY = { ...EMPTY, enabled: true }

export default function ModelsPanel() {
  const [models, setModels] = useState<Model[]>([])
  const [form, setForm] = useState({ ...EMPTY })
  const [editing, setEditing] = useState<Model | null>(null)
  const [editForm, setEditForm] = useState({ ...EDIT_EMPTY })
  const [err, setErr] = useState('')
  const [testing, setTesting] = useState<number | null>(null)
  const [tested, setTested] = useState<Record<number, boolean>>({})

  async function load() {
    try {
      const j = await http.get<Model[]>('/api/models')
      setModels(Array.isArray(j) ? j : [])
      setErr('')
    } catch (e: any) { setErr(e?.message || '加载失败') }
  }
  useEffect(() => { void load() }, [])

  async function create() {
    const body = {
      name: form.name.trim(), type: form.type, baseUrl: form.baseUrl.trim(), model: form.model.trim(),
      apiKey: form.apiKey.trim(), dimensions: form.type === 'EMBEDDING' ? Number(form.dimensions || 0) : null,
      enabled: true, active: false
    }
    if (!body.name || !body.baseUrl || !body.model) { message.error('名称、服务地址、模型名都要填写'); return }
    try {
      const j = await http.post<{ message?: string }>('/api/models', body)
      setForm({ ...EMPTY })
      message.success(j?.message || '配置已添加')
      await load()
    } catch (e: any) { message.error('添加失败：' + (e?.message || '')) }
  }

  /** 列表行「编辑」：回填当前配置，Key 不回显明文（留空即保持原值） */
  function openEdit(m: Model) {
    setEditing(m)
    setEditForm({
      name: m.name, type: m.type, baseUrl: m.baseUrl, model: m.model,
      apiKey: '', dimensions: m.dimensions ? String(m.dimensions) : '', enabled: m.enabled !== false
    })
  }

  async function saveEdit() {
    if (!editing) return
    if (!editForm.name.trim() || !editForm.baseUrl.trim() || !editForm.model.trim()) {
      message.error('名称、服务地址、模型名都要填写'); return
    }
    if (editForm.type === 'EMBEDDING' && !editForm.dimensions) {
      message.error('EMBEDDING 配置必须填写向量维度'); return
    }
    try {
      await http.put('/api/models/' + editing.id, {
        name: editForm.name.trim(), type: editForm.type, baseUrl: editForm.baseUrl.trim(),
        model: editForm.model.trim(), apiKey: editForm.apiKey.trim(),
        dimensions: editForm.type === 'EMBEDDING' ? Number(editForm.dimensions) : null,
        enabled: editForm.enabled
      })
      message.success('配置已更新')
      setEditing(null)
      await load()
    } catch (e: any) { message.error('更新失败：' + (e?.message || '')) }
  }

  async function activate(id: number) {
    try {
      const j = await http.post<{ message?: string }>('/api/models/' + id + '/activate')
      message.success((j?.message || '已激活') + '（已即时生效，无需重启）')
      await load()
    } catch (e: any) { message.error('激活失败：' + (e?.message || '')) }
  }

  async function setDefaultModel(id: number) {
    try {
      const j = await http.post<{ message?: string }>('/api/models/' + id + '/set-default')
      message.success(j?.message || '已设为全局默认')
      await load()
    } catch (e: any) { message.error('设置默认失败：' + (e?.message || '')) }
  }

  async function deactivate(id: number) {
    try {
      const j = await http.post<{ message?: string }>('/api/models/' + id + '/deactivate')
      message.success(j?.message || '已取消激活')
      await load()
    } catch (e: any) { message.error('取消激活失败：' + (e?.message || '')) }
  }

  async function test(id: number) {
    setTesting(id)
    try {
      const j = await http.post<{ result: string; name: string }>('/api/models/' + id + '/test')
      const ok = String(j.result || '').startsWith('OK')
      if (ok) { setTested(s => ({ ...s, [id]: true })); message.success(`${j.name}：${j.result}`) }
      else message.error(`${j.name}：${j.result}`)
    } catch (e: any) { message.error('测试失败：' + (e?.message || '')) }
    finally { setTesting(null) }
  }

  async function remove(m: Model) {
    const ok = await confirmDialog({
      title: '删除模型配置', danger: true, okText: '确认删除',
      content: <p style={{ margin: 0 }}>确定删除模型配置「{m.name}」吗？</p>
    })
    if (!ok) return
    try { await http.delete('/api/models/' + m.id); message.success('配置已删除'); await load() }
    catch (e: any) { message.error('删除失败：' + (e?.message || '')) }
  }

  const chat = models.find(m => m.type === 'CHAT' && m.active)
  const emb = models.find(m => m.type === 'EMBEDDING' && m.active)

  return (
    <>
      <div className="page-head"><h1 className="page-title">模型设置</h1>
        <p className="page-desc">设置对话模型和文档检索模型，支持公有云服务与内网私有部署。</p></div>
      <div className="intro">
        对话模型切换后立即生效；文档检索模型切换前会校验向量维度，与已入库数据不一致时会阻止切换，避免影响现有知识库。
        密钥只显示前 4 后 4 位，更新时不填写即保持原值。
      </div>
      {err && <div className="msg err show">{err}</div>}

      <div className="grid">
        <div className="card">
          <div className="card-head"><h3>新增模型配置</h3></div>
          <div className="row">
            <div className="field" style={{ flex: 2 }}><label><span className="req">*</span>名称</label>
              <input value={form.name} autoComplete="off" placeholder="如：内网-Qwen3.5-397B"
                onChange={e => setForm(f => ({ ...f, name: e.target.value }))} /></div>
            <div className="field"><label>类型</label>
              <select value={form.type} onChange={e => setForm(f => ({ ...f, type: e.target.value }))}>
                <option value="CHAT">CHAT 对话</option><option value="EMBEDDING">EMBEDDING 向量</option>
              </select></div>
          </div>
          <div className="field"><label><span className="req">*</span>服务地址</label>
            <input value={form.baseUrl} autoComplete="off" placeholder="云服务地址或内网模型地址"
              onChange={e => setForm(f => ({ ...f, baseUrl: e.target.value }))} /></div>
          <div className="row">
            <div className="field"><label><span className="req">*</span>模型名</label>
              <input value={form.model} autoComplete="off" placeholder="qwen-plus / text-embedding-v3"
                onChange={e => setForm(f => ({ ...f, model: e.target.value }))} /></div>
            <div className="field"><label>访问密钥（内网可留空）</label>
              <input type="password" autoComplete="new-password" value={form.apiKey} placeholder="留空表示保持原值"
                onChange={e => setForm(f => ({ ...f, apiKey: e.target.value }))} /></div>
          </div>
          {form.type === 'EMBEDDING' &&
            <div className="field"><label><span className="req">*</span>向量维度（EMBEDDING 必填）</label>
              <input type="number" value={form.dimensions} placeholder="1024"
                onChange={e => setForm(f => ({ ...f, dimensions: e.target.value }))} /></div>}
          <button className="primary-btn" onClick={() => void create()}>＋ 添加配置</button>
          <div className="hint">提示：切换文档检索模型前，建议先点「测试」确认服务可用；不同维度的模型不能共用同一个知识库。</div>
        </div>

        <div className="card">
          <div className="card-head"><h3>当前生效配置</h3></div>
          <div id="activeBox">
            <div className="active-item">
              <div className="active-label">对话模型（CHAT）</div>
              <div className="active-name">{chat ? chat.name : '未配置'}</div>
              <span className="mono">{chat ? chat.model : '—'}</span>
            </div>
            <div className="active-divider" />
            <div className="active-item">
              <div className="active-label">向量模型（EMBEDDING）</div>
              <div className="active-name">{emb ? emb.name : '未配置'}</div>
              <span className="mono">{emb ? `${emb.model} · ${emb.dimensions}维` : '—'}</span>
            </div>
          </div>
        </div>
      </div>

      <div className="table-card">
        <div className="scroll">
          <table className="data-table">
            <thead><tr>
              <th>名称 / 类型</th><th>端点 / 模型</th><th>Key</th><th>状态</th><th style={{ width: 300 }}>操作</th>
            </tr></thead>
            <tbody>
              {models.length === 0
                ? <tr><td colSpan={5} className="empty">暂无配置</td></tr>
                : models.map(m => <tr key={m.id}>
                  <td>
                    <span className="name">{m.name}</span><br />
                    <span className={'tag' + (m.type === 'EMBEDDING' ? ' emb' : '')}>{m.type}</span>
                    {m.dimensions ? <> <span className="tag">{m.dimensions}维</span></> : null}
                  </td>
                  <td>
                    <span className="mono">{m.baseUrl}</span>
                    <span className="mono">{m.model}</span>
                  </td>
                  <td><span className="mono">{m.apiKey}</span></td>
                  <td>
                    <span className={'tag ' + (m.active && m.enabled !== false ? 'active' : 'off')}>
                      {m.enabled === false ? '已禁用' : (m.active ? '使用中' : '待命')}
                    </span>
                    {m.isDefault && <span className="tag active" style={{ marginLeft: 6 }}>全局默认</span>}
                    {tested[m.id] && <span className="tag ok" style={{ marginLeft: 6 }}>服务正常</span>}
                  </td>
                  <td>
                    <div className="model-ops">
                      <button className="btn btn-sm" onClick={() => openEdit(m)}>编辑</button>
                      {m.enabled === false
                        ? <button className="btn btn-sm" disabled>已停用</button>
                        : m.active
                          ? <button className="btn btn-sm" title="点击取消激活" onClick={() => void deactivate(m.id)}>已激活</button>
                          : <button className="btn btn-sm" onClick={() => void activate(m.id)}>激活</button>}
                      {m.type === 'CHAT' && !m.isDefault && m.enabled !== false &&
                        <button className="btn btn-sm" title="所有用户未指定模型时都用这个"
                          onClick={() => void setDefaultModel(m.id)}>设为默认</button>}
                      <button className="btn btn-sm" disabled={testing === m.id} onClick={() => void test(m.id)}>
                        {testing === m.id ? '测试中…' : '测试'}
                      </button>
                      <button className="btn btn-sm btn-danger" disabled={m.active} onClick={() => void remove(m)}>删除</button>
                    </div>
                  </td>
                </tr>)}
            </tbody>
          </table>
        </div>
      </div>

      {/* 编辑模型配置：后端 PUT 已支持「Key 留空/脱敏串保持原值」 */}
      <WbModal size="md" title={'编辑模型配置 · ' + (editing?.name || '')} open={!!editing}
        okText="保存修改" cancelText="取消" onOk={() => void saveEdit()} onCancel={() => setEditing(null)}>
        <div className="row">
          <div className="field" style={{ flex: 2 }}><label><span className="req">*</span>名称</label>
            <input value={editForm.name} autoComplete="off"
              onChange={e => setEditForm(f => ({ ...f, name: e.target.value }))} /></div>
          <div className="field"><label>类型</label>
            <select value={editForm.type} onChange={e => setEditForm(f => ({ ...f, type: e.target.value }))}>
              <option value="CHAT">CHAT 对话</option><option value="EMBEDDING">EMBEDDING 向量</option>
            </select></div>
        </div>
        <div className="field"><label><span className="req">*</span>服务地址</label>
          <input value={editForm.baseUrl} autoComplete="off"
            onChange={e => setEditForm(f => ({ ...f, baseUrl: e.target.value }))} /></div>
        <div className="row">
          <div className="field"><label><span className="req">*</span>模型名</label>
            <input value={editForm.model} autoComplete="off"
              onChange={e => setEditForm(f => ({ ...f, model: e.target.value }))} /></div>
          <div className="field"><label>访问密钥</label>
            <input type="password" autoComplete="new-password" value={editForm.apiKey}
              placeholder="留空表示保持原值"
              onChange={e => setEditForm(f => ({ ...f, apiKey: e.target.value }))} /></div>
        </div>
        {editForm.type === 'EMBEDDING' &&
          <div className="field"><label><span className="req">*</span>向量维度</label>
            <input type="number" value={editForm.dimensions} placeholder="1024"
              onChange={e => setEditForm(f => ({ ...f, dimensions: e.target.value }))} /></div>}
        <div className="field status-field">
          <label className="field-check"><input type="checkbox" checked={editForm.enabled}
            onChange={e => setEditForm(f => ({ ...f, enabled: e.target.checked }))} />启用该配置</label>
          <div className="form-hint">
            当前：{editForm.enabled ? '启用（可激活使用）' : '停用（保存后立即下线并取消使用中）'}。
            禁用正在使用的配置会同时取消激活，并切换代理到同类型的其他可用模型。
          </div>
        </div>
      </WbModal>
    </>
  )
}
