import { useEffect, useState } from 'react'
import { Modal, message } from 'antd'
import { http } from '@/api/http'
import '@/styles/admin.css'
interface Perm { code:string; name:string; nodeType:string; module:string; description:string; builtin:boolean; parentCode:string; orderNum:number; retired?:boolean }
export default function PermsPanel(){
 const [rows,setRows]=useState<Perm[]>([]);const [kw,setKw]=useState('');const [folded,setFolded]=useState<Set<string>>(new Set());const [edit,setEdit]=useState<Perm|null>(null)
 const [form,setForm]=useState({code:'',name:'',nodeType:'menu',module:'',description:'',parentCode:'',orderNum:0});const [err,setErr]=useState('')
 async function load(){try{const j=await http.get<Perm[]>('/api/admin/perms');setRows(Array.isArray(j)?j:[])}catch(e:any){setErr(e?.message||'加载失败')}}
 useEffect(()=>{void load()},[])
 async function save(){try{if(edit?.code)await http.put('/api/admin/perms/'+encodeURIComponent(edit.code),form);else await http.post('/api/admin/perms',form);message.success('已保存');setEdit(null);await load()}catch(e:any){setErr(e?.message||'保存失败')}}
 async function remove(p:Perm){if(!window.confirm('确定删除权限节点「'+p.name+'」吗？'))return;try{await http.delete('/api/admin/perms/'+encodeURIComponent(p.code));message.success('已删除');await load()}catch(e:any){message.error(e?.message||'删除失败')}}
 const list=rows.filter(r=>!kw||[r.name,r.code,r.module].some(v=>String(v||'').toLowerCase().includes(kw.toLowerCase())))
 function nodes(){const out:React.ReactNode[]=[];const walk=(parent:string,d:number)=>{for(const p of list.filter(x=>(x.parentCode||'')===parent).sort((a,b)=>a.orderNum-b.orderNum)){const kids=list.filter(x=>x.parentCode===p.code);const isF=folded.has(p.code);out.push(<tr key={p.code}>
 <td style={{paddingLeft:10+d*16}}><span className={`caret${kids.length?'':' ghost'}`} onClick={()=>kids.length&&setFolded(s=>{const n=new Set(s);n.has(p.code)?n.delete(p.code):n.add(p.code);return n})}>{kids.length?(isF?'▸':'▾'):''}</span>{p.name}</td>
 <td><span className="tag">{{directory:'目录',menu:'菜单',btn:'按钮'}[p.nodeType]||p.nodeType}</span></td><td className="perm-code">{p.code}</td><td>{p.module}</td><td>{p.orderNum}</td>
 <td>{!p.builtin&&<><button className="op-btn" onClick={()=>{setEdit(p);setForm({code:p.code,name:p.name,nodeType:p.nodeType,module:p.module,description:p.description,parentCode:p.parentCode,orderNum:p.orderNum});setErr('')}}>编辑</button><button className="op-btn danger" onClick={()=>void remove(p)}>删除</button></>}</td></tr>);if(!isF)walk(p.code,d+1)}};walk('',0);return out}
 return <>{err&&<div className="msg err show">{err}</div>}<div className="page-head"><h1 className="page-title">菜单权限</h1><p className="page-desc">维护权限节点与角色授权，树形展示目录/菜单/按钮三级。</p></div>
 <div className="card"><div className="toolbar"><input className="search" placeholder="搜索名称 / 权限标识" value={kw} onChange={e=>setKw(e.target.value)}/>
 <button className="btn btn-sm" onClick={()=>setFolded(new Set(rows.map(r=>r.code)))}>收起全部</button>
 <button className="btn btn-sm" onClick={()=>setFolded(new Set())}>展开全部</button><span className="grow"/>
 <button className="btn btn-sm btn-amber" onClick={()=>{setEdit({code:'',name:'',nodeType:'menu',module:'',description:'',builtin:false,parentCode:'',orderNum:0});setForm({code:'',name:'',nodeType:'menu',module:'',description:'',parentCode:'',orderNum:0});setErr('')}}>＋ 新增权限</button></div>
 <div className="table-wrap"><table className="data-table"><thead><tr><th>名称</th><th>类型</th><th>权限标识</th><th>模块</th><th>排序</th><th>操作</th></tr></thead><tbody>{nodes()}</tbody></table></div></div>
 <Modal title={edit?.code?'编辑权限':'新增权限'} open={!!edit} onCancel={()=>setEdit(null)} onOk={()=>void save()} okText="保存">
 {err&&<div className="msg err show">{err}</div>}
 <div className="field"><label>名称</label><input value={form.name} onChange={e=>setForm(f=>({...f,name:e.target.value}))}/></div>
 <div className="field"><label>权限标识</label><input value={form.code} disabled={!!edit?.code} onChange={e=>setForm(f=>({...f,code:e.target.value}))}/></div>
 <div className="field"><label>类型</label><select value={form.nodeType} onChange={e=>setForm(f=>({...f,nodeType:e.target.value}))}><option value="directory">目录</option><option value="menu">菜单</option><option value="btn">按钮</option></select></div>
 <div className="field"><label>上级编码</label><input value={form.parentCode} onChange={e=>setForm(f=>({...f,parentCode:e.target.value}))}/></div>
 </Modal></>}
