import { useEffect, useState } from 'react'
import { http } from '@/api/http'
import '@/styles/admin.css'
export default function LogsPanel({type}:{type:'login'|'audit'}){
 const [rows,setRows]=useState<any[]>([]);const [err,setErr]=useState('');const [page,setPage]=useState(1);const size=20
 const title=type==='login'?'登录日志':'操作日志'
 async function load(){try{const j=type==='login'?await http.get<any[]>('/api/admin/login-logs?limit=500'):await http.get<any[]>('/api/audit/logs?limit=500');setRows(Array.isArray(j)?j:[])}catch(e:any){setErr(e?.message||'加载失败')}}
 useEffect(()=>{void load()},[type])
 function exportCsv(){const keys=Object.keys(rows[0]||{}).slice(0,8);const csv='\uFEFF'+[keys.join(','),...rows.map(r=>keys.map(k=>String(r[k]??'').replace(/[,\n]/g,' ')).join(','))].join('\n');const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([csv],{type:'text/csv;charset=utf-8'}));a.download=title+'.csv';a.click()}
 const totalPages=Math.max(1,Math.ceil(rows.length/size))
 return <>{err&&<div className="msg err show">{err}</div>}<div className="page-head"><h1 className="page-title">{title}</h1><p className="page-desc">{type==='login'?'记录成功与失败的登录尝试。':'记录管理端的所有操作。'}</p></div>
 <div className="card"><div className="toolbar"><button className="btn btn-sm btn-green" onClick={exportCsv}>{type==='login'?'导出登录日志':'导出操作日志'}</button><span className="grow"/><button className="btn btn-sm" onClick={()=>void load()}>刷新</button></div>
 <div className="table-wrap"><table className="data-table"><thead><tr>{(Object.keys(rows[0]||{}).slice(0,8).length?Object.keys(rows[0]||{}).slice(0,8):['账号','操作','状态','结果','时间']).map(k=><th key={k}>{k}</th>)}</tr></thead><tbody>
 {rows.length?rows.slice((page-1)*size,page*size).map((r,i)=><tr key={i}>{Object.keys(rows[0]||{}).slice(0,8).map(k=><td key={k}>{String(r[k]??'')}</td>)}</tr>):<tr><td colSpan={5} className="empty">暂无记录</td></tr>}
 </tbody></table></div>
 <div className="pager"><span>共 {rows.length} 条</span><button className="pg-btn" disabled={page<=1} onClick={()=>setPage(1)}>首页</button><button className="pg-btn" disabled={page<=1} onClick={()=>setPage(page-1)}>上一页</button><button className="pg-btn on">{page}</button><button className="pg-btn" disabled={page>=totalPages} onClick={()=>setPage(page+1)}>下一页</button><button className="pg-btn" disabled={page>=totalPages} onClick={()=>setPage(totalPages)}>末页</button></div></div></>}
