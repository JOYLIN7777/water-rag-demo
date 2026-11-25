import { useEffect, useState } from 'react'
import { Modal, message } from 'antd'
import { http } from '@/api/http'
import '@/styles/admin.css'
interface Org { key:string; name:string; parentKey?:string; orderNum?:number; members?:number; membersAll?:number }
export default function OrgPanel(){
 const [rows,setRows]=useState<Org[]>([]);const [folded,setFolded]=useState<Set<string>>(new Set());
 const [edit,setEdit]=useState<Org|null>(null);const [form,setForm]=useState({key:'',name:'',parentKey:'',orderNum:0});const [err,setErr]=useState('')
 async function load(){try{const j=await http.get<Org[]>('/api/orgs');setRows(Array.isArray(j)?j:[])}catch(e:any){setErr(e?.message||'加载失败')}}
 useEffect(()=>{void load()},[])
 async function save(){try{if(edit?.key)await http.put('/api/admin/orgs/'+encodeURIComponent(edit.key),form);else await http.post('/api/admin/orgs',form);message.success('已保存');setEdit(null);await load()}catch(e:any){setErr(e?.message||'保存失败')}}
 async function remove(o:Org){if(!window.confirm('确定删除组织「'+o.name+'」吗？'))return;try{await http.delete('/api/admin/orgs/'+encodeURIComponent(o.key));message.success('已删除');await load()}catch(e:any){message.error(e?.message||'删除失败')}}
 function nodes(){const out:React.ReactNode[]=[];const walk=(parent:string,d:number)=>{for(const o of rows.filter(x=>(x.parentKey||'')===parent)){const kids=rows.filter(x=>x.parentKey===o.key);const isF=folded.has(o.key);out.push(<tr key={o.key} className={isF?'folded':''}>
 <td style={{paddingLeft:10+d*13}}><span className={`caret${kids.length?'':' ghost'}`} onClick={()=>kids.length&&setFolded(p=>{const s=new Set(p);s.has(o.key)?s.delete(o.key):s.add(o.key);return s})}>{kids.length?(isF?'▸':'▾'):''}</span>{o.name}</td>
 <td>{o.key}</td><td>{o.orderNum??0}</td><td>{o.members??0}</td><td>{o.membersAll??0}</td>
 <td><button className="op-btn" onClick={()=>{setEdit(o);setForm({key:o.key,name:o.name,parentKey:o.parentKey||'',orderNum:o.orderNum||0});setErr('')}}>编辑</button>
 <button className="op-btn warn" onClick={()=>{setEdit({key:'',name:'',parentKey:o.key});setForm({key:'',name:'',parentKey:o.key,orderNum:0});setErr('')}}>＋下级</button>
 <button className="op-btn danger" onClick={()=>void remove(o)}>删除</button></td></tr>);if(!isF)walk(o.key,d+1)}};walk('',0);return out}
 return <>{err&&<div className="msg err show">{err}</div>}<div className="page-head"><h1 className="page-title">组织架构</h1><p className="page-desc">以树形结构维护部门层级。点击行首三角可折叠下级组织。</p></div>
 <div className="card"><div className="toolbar"><button className="btn btn-sm btn-amber" onClick={()=>{setEdit({key:'',name:'',parentKey:''});setForm({key:'',name:'',parentKey:'',orderNum:0});setErr('')}}>＋ 新建部门</button><span className="grow"/><button className="btn btn-sm" onClick={()=>void load()}>刷新</button></div>
 <div className="table-wrap"><table className="data-table"><thead><tr><th>部门</th><th>部门编码</th><th>排序</th><th>直属成员</th><th>含下级成员</th><th>操作</th></tr></thead><tbody>{nodes()}</tbody></table></div></div>
 <Modal title={edit?.key?'编辑组织':'新建组织'} open={!!edit} onCancel={()=>setEdit(null)} onOk={()=>void save()} okText="保存">
 {err&&<div className="msg err show">{err}</div>}
 <div className="field"><label>部门名称</label><input value={form.name} onChange={e=>setForm(f=>({...f,name:e.target.value}))}/></div>
 <div className="field"><label>编码Key</label><input value={form.key} disabled={!!edit?.key} onChange={e=>setForm(f=>({...f,key:e.target.value}))}/></div>
 <div className="field"><label>上级部门Key</label><input value={form.parentKey} onChange={e=>setForm(f=>({...f,parentKey:e.target.value}))}/></div>
 <div className="field"><label>排序号</label><input type="number" value={form.orderNum} onChange={e=>setForm(f=>({...f,orderNum:Number(e.target.value)}))}/></div>
 </Modal></>}
