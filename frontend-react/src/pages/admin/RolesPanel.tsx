import { useEffect, useState } from 'react'
import { Modal, message } from 'antd'
import { http } from '@/api/http'
import '@/styles/admin.css'
interface Role { role:string; name:string; permissions:string[]; builtin?:boolean; description?:string }
interface Perm { code:string; name:string; nodeType:string; parentCode:string; module:string; orderNum:number }
export default function RolesPanel(){
 const [rows,setRows]=useState<Role[]>([]);const [perms,setPerms]=useState<Perm[]>([]);const [edit,setEdit]=useState<Role|null>(null)
 const [name,setName]=useState('');const [picked,setPicked]=useState<Set<string>>(new Set());const [folded,setFolded]=useState<Set<string>>(new Set());const [err,setErr]=useState('')
 async function load(){try{const [r,p]=await Promise.all([http.get<Role[]>('/api/admin/roles'),http.get<Perm[]>('/api/admin/perms')]);setRows(Array.isArray(r)?r:[]);setPerms(Array.isArray(p)?p:[])}catch(e:any){setErr(e?.message||'加载失败')}}
 useEffect(()=>{void load()},[])
 function openEdit(r:Role){setEdit(r);setName(r.name||'');setPicked(new Set(r.permissions||[]));setFolded(new Set());setErr('')}
 function toggle(code:string,checked:boolean){setPicked(prev=>{const s=new Set(prev);const kids=perms.filter(p=>p.parentCode===code).map(p=>p.code);if(checked){s.add(code);kids.forEach(k=>s.add(k))}else{s.delete(code);kids.forEach(k=>s.delete(k))}return s})}
 async function save(){if(!edit)return;try{await http.put('/api/admin/roles/'+encodeURIComponent(edit.role)+'/permissions',{roleName:name,permissions:Array.from(picked)});message.success('已保存');setEdit(null);await load()}catch(e:any){setErr(e?.message||'保存失败')}}
 async function remove(r:Role){if(!window.confirm('确定删除角色「'+r.name+'」吗？'))return;try{await http.delete('/api/admin/roles/'+encodeURIComponent(r.role));message.success('已删除');await load()}catch(e:any){message.error(e?.message||'删除失败')}}
 function nodes(){const out:React.ReactNode[]=[];const walk=(parent:string,d:number)=>{for(const p of perms.filter(x=>(x.parentCode||'')===parent).sort((a,b)=>a.orderNum-b.orderNum)){const kids=perms.filter(x=>x.parentCode===p.code);const isF=folded.has(p.code);out.push(<div key={p.code} className="perm-row" style={{paddingLeft:6+d*16}}>
 <span className={`caret${kids.length?'':' ghost'}`} onClick={()=>kids.length&&setFolded(s=>{const n=new Set(s);n.has(p.code)?n.delete(p.code):n.add(p.code);return n})}>{kids.length?(isF?'▸':'▾'):''}</span>
 <label className="checkbox"><input type="checkbox" checked={picked.has(p.code)} onChange={e=>toggle(p.code,e.target.checked)}/>{p.name}</label>
 <span className="perm-code">{p.code}</span>{p.nodeType==='btn'&&<span className="tag">按钮</span>}</div>);if(!isF)walk(p.code,d+1)}};walk('',0);return out}
 return <>{err&&<div className="msg err show">{err}</div>}<div className="page-head"><h1 className="page-title">角色定义</h1><p className="page-desc">维护角色与权限配置，勾选权限后该角色立即生效。</p></div>
 <div className="card"><div className="toolbar"><button className="btn btn-sm btn-amber" onClick={()=>{setEdit({role:'',name:'',permissions:[]});setName('');setPicked(new Set());setErr('')}}>＋ 新增角色</button><span className="grow"/><button className="btn btn-sm" onClick={()=>void load()}>刷新</button></div>
 <div className="table-wrap"><table className="data-table"><thead><tr><th>角色</th><th>显示名</th><th>权限数</th><th>说明</th><th>操作</th></tr></thead><tbody>
 {rows.length?rows.map(r=><tr key={r.role}><td>{r.role}</td><td>{r.name}</td><td>{r.permissions?.length||0}</td><td>{r.description||''}</td><td>
 {!r.builtin&&<><button className="op-btn" onClick={()=>openEdit(r)}>编辑权限</button><button className="op-btn danger" onClick={()=>void remove(r)}>删除</button></>}</td></tr>):<tr><td colSpan={5} className="empty">暂无角色</td></tr>}
 </tbody></table></div></div>
 <Modal title={edit?.role?'编辑角色权限':'新增角色'} open={!!edit} onCancel={()=>setEdit(null)} onOk={()=>void save()} okText="保存" width={700}>
 {err&&<div className="msg err show">{err}</div>}
 <div className="field"><label>角色标识</label><input value={edit?.role||''} disabled={!!edit?.role} onChange={e=>setEdit(r=>r?{...r,role:e.target.value}:r)}/></div>
 <div className="field"><label>显示名</label><input value={name} onChange={e=>setName(e.target.value)}/></div>
 <div className="card" style={{maxHeight:400,overflowY:'auto',padding:12}}>{nodes()}</div>
 </Modal></>}
