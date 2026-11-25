import { useEffect, useState } from 'react'
import { http } from '@/api/http'
import '@/styles/admin.css'
export default function UsagePanel(){
 const [sum,setSum]=useState<any>(null);const [records,setRecords]=useState<any[]>([]);const [detail,setDetail]=useState<any>(null);const [err,setErr]=useState('')
 async function load(){try{const [s,r]=await Promise.all([http.get<any>('/api/usage/summary'),http.get<any[]>('/api/usage/records?limit=500')]);setSum(s);setRecords(Array.isArray(r)?r:[])}catch(e:any){setErr(e?.message||'加载失败')}}
 useEffect(()=>{void load()},[])
 const cards=sum?[['今日调用',sum.today?.requests??sum.requestsToday??0],['今日Token',sum.today?.tokens??sum.tokensToday??0],['本月调用',sum.month?.requests??sum.requestsMonth??0],['本月Token',sum.month?.tokens??sum.tokensMonth??0]]:[]
 return <>{err&&<div className="msg err show">{err}</div>}<div className="page-head"><h1 className="page-title">用量统计</h1><p className="page-desc">查看模型调用与 Token 消耗。</p></div>
 <div className="stats">{cards.map(([k,v]:any)=><div className="stat-card" key={k}><div className="stat-num">{Number(v).toLocaleString()}</div><div className="stat-label">{k}</div></div>)}</div>
 <div className="card"><div className="card-head"><h3>按模型明细</h3></div><div className="table-wrap"><table className="data-table"><thead><tr><th>模型</th><th>请求次数</th><th>输入Token</th><th>输出Token</th></tr></thead><tbody>
 {(sum?.models||[]).length?(sum.models as any[]).map((m,i)=><tr key={i}><td>{m.model||m.name}</td><td>{m.requests||0}</td><td>{m.promptTokens||m.inputTokens||0}</td><td>{m.completionTokens||m.outputTokens||0}</td></tr>):<tr><td colSpan={4} className="empty">暂无数据</td></tr>}
 </tbody></table></div></div>
 <div className="card" style={{marginTop:16}}><div className="card-head"><h3>调用记录（最近 {records.length} 条）</h3></div><div className="table-wrap"><table className="data-table"><thead><tr><th>账号</th><th>模型</th><th>问题</th><th>耗时</th><th>操作</th></tr></thead><tbody>
 {records.slice(0,50).map((r,i)=><tr key={i}><td>{r.username||r.user||'—'}</td><td>{r.model||'—'}</td><td>{String(r.question||'').slice(0,50)}</td><td>{r.durationMs?((r.durationMs/1000).toFixed(1)+'s'):'—'}</td><td><button className="op-btn" onClick={()=>setDetail(r)}>详情</button></td></tr>)}
 </tbody></table></div></div>
 {detail&&<div className="card" style={{marginTop:12}}><div className="card-head"><h3>记录详情</h3></div><pre style={{fontSize:11,whiteSpace:'pre-wrap'}}>{JSON.stringify(detail,null,2)}</pre></div>}
 </>}
