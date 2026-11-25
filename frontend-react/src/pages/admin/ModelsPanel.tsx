import { useEffect, useState } from 'react'
import { Modal, message } from 'antd'
import { http } from '@/api/http'
import '@/styles/admin.css'
interface Model { id:number; name:string; type:string; baseUrl:string; model:string; active:boolean; enabled:boolean }
export default function ModelsPanel(){
 const [rows,setRows]=useState<Model[]>([]);const [edit,setEdit]=useState(false);const [form,setForm]=useState<any>({name:'',type:'CHAT',baseUrl:'',model:'',apiKey:''});const [err,setErr]=useState('')
 async function load(){try{const j=await http.get<Model[]>('/api/models');setRows(Array.isArray(j)?j:[])}catch(e:any){setErr(e?.message||'加载失败')}}
 useEffect(()=>{void load()},[])
 async function save(){try{await http.post('/api/models',form);message.success('已添加');setEdit(false);await load()}catch(e:any){setErr(e?.message||'保存失败')}}
 async function act(id:number,op:string){try{await http.post('/api/models/'+id+'/'+op);message.success(op==='activate'?'已激活':'测试完成');await load()}catch(e:any){message.error(e?.message||'操作失败')}}
 async function remove(id:number){if(!window.confirm('确定删除该模型配置吗？'))return;try{await http.delete('/api/models/'+id);message.success('已删除');await load()}catch(e:any){message.error(e?.message||'删除失败')}}
 return <>{err&&<div className="msg err show">{err}</div>}<div className="page-head"><h1 className="page-title">模型设置</h1><p className="page-desc">设置对话与检索模型。</p></div>
 <div className="card"><div className="toolbar"><button className="btn btn-sm btn-amber" onClick={()=>setEdit(true)}>＋ 添加配置</button><span className="grow"/><button className="btn btn-sm" onClick={()=>void load()}>刷新</button></div>
 <div className="table-wrap"><table className="data-table"><thead><tr><th>名称</th><th>类型</th><th>端点</th><th>模型</th><th>状态</th><th>操作</th></tr></thead><tbody>
 {rows.length?rows.map(m=><tr key={m.id}><td>{m.name}</td><td>{m.type}</td><td>{m.baseUrl}</td><td>{m.model}</td><td><span className={"tag "+(m.active?'ok':'')}>{m.active?'使用中':m.enabled?'待命':'已禁用'}</span></td><td>
 {!m.active&&<><button className="op-btn" onClick={()=>void act(m.id,'activate')}>激活</button><button className="op-btn" onClick={()=>void act(m.id,'test')}>测试</button><button className="op-btn danger" onClick={()=>void remove(m.id)}>删除</button></>}</td></tr>):<tr><td colSpan={6} className="empty">暂无模型配置</td></tr>}
 </tbody></table></div></div>
 <Modal title="添加模型配置" open={edit} onCancel={()=>setEdit(false)} onOk={()=>void save()} okText="创建">
 <div className="field"><label>名称</label><input value={form.name} onChange={e=>setForm({...form,name:e.target.value})}/></div>
 <div className="field"><label>类型</label><select value={form.type} onChange={e=>setForm({...form,type:e.target.value})}><option value="CHAT">CHAT 对话</option><option value="EMBEDDING">EMBEDDING 向量</option></select></div>
 <div className="field"><label>服务地址</label><input value={form.baseUrl} onChange={e=>setForm({...form,baseUrl:e.target.value})}/></div>
 <div className="field"><label>模型名</label><input value={form.model} onChange={e=>setForm({...form,model:e.target.value})}/></div>
 <div className="field"><label>访问密钥</label><input type="password" value={form.apiKey} onChange={e=>setForm({...form,apiKey:e.target.value})}/></div>
 </Modal></>}
