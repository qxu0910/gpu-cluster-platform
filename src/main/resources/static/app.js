'use strict';
const $ = id => document.getElementById(id);
let token = '', epoch = 0, loading = false, busy = false, workloads = [], images = [], selected;
const operations = new Map(), pendingKeys = new Map();
const labels = {ready:'已就绪', stopped:'已停止', scheduling:'调度中', pulling:'拉取中', starting:'启动中', blocked:'受阻', unknown:'未知', deleting:'删除中', deleted:'已删除', pending:'待执行', running:'执行中', succeeded:'成功', failed:'失败'};
function element(tag, text, cls) { const e = document.createElement(tag); e.textContent = text; if(cls) e.className = cls; return e; }
function notice(text, error = false) { $('notice').textContent = text; $('notice').className = error ? 'error' : ''; }
function badge(state) { return element('span', labels[state] || state, 'badge '+(['ready','succeeded','stopped'].includes(state)?'good':['failed','blocked','unknown'].includes(state)?'bad':'')); }
async function api(path, method='GET', body, headers={}) {
  const session = epoch;
  const fingerprint = method+' '+path+' '+JSON.stringify(body)+' '+JSON.stringify(headers);
  const h = {Authorization:'Bearer '+token, ...headers};
  if(method !== 'GET') { if(!pendingKeys.has(fingerprint)) pendingKeys.set(fingerprint, crypto.randomUUID()); h['Idempotency-Key'] = pendingKeys.get(fingerprint); }
  if(body !== undefined) h['Content-Type']='application/json';
  let response;
  try { response = await fetch(path, {method, headers:h, body:body===undefined?undefined:JSON.stringify(body), cache:'no-store', signal:AbortSignal.timeout(15000)}); }
  catch { throw new Error('连接中断或超时。提交结果可能尚未确定；重试相同请求会沿用幂等键。'); }
  if(session !== epoch) throw new Error('连接已切换，请重新操作。');
  const result = await response.json();
  if(!response.ok) throw new Error((response.status===401?'凭据无效或已过期':result.error?.code || '请求失败')+' · HTTP '+response.status);
  pendingKeys.delete(fingerprint);
  return result;
}
async function list(path) { let all=[]; for(let offset=0;;offset+=100) { const page=await api(path+'?limit=100&offset='+offset); all.push(...page.items); if(page.items.length<100) return all; } }
function emptyRows(id, columns, text) { const tr=element('tr',''), td=element('td',text,'empty'); td.colSpan=columns; tr.append(td); $(id).replaceChildren(tr); }
function render() {
  $('total').textContent=workloads.length; $('ready').textContent=workloads.filter(w=>w.observed?.phase==='ready'&&!w.observed?.stale).length; $('image-count').textContent=images.length;
  $('workload-rows').replaceChildren();
  for(const w of workloads) {
    const tr=element('tr',''), name=element('td',w.name); name.append(element('small',w.id));
    const state=element('td',''); state.append(badge(w.observed?.stale?'unknown':w.observed?.phase||'pending')); if(w.observed?.error_code) state.append(element('small',w.observed.error_code));
    const controls=element('td',''), buttons=element('div','','actions');
    for(const [action,label] of [['start','启动'],['stop','停止'],['scale','扩缩容'],['delete','删除']]) { const b=element('button',label,'secondary'); b.disabled=busy||(action==='start'&&w.replicas!==0); b.addEventListener('click',()=>showAction(w,action,label)); buttons.append(b); }
    controls.append(buttons); tr.append(name,state,element('td',w.replicas+' / v'+w.version),element('td',w.observed?.access?.host||'—'),controls); $('workload-rows').append(tr);
  }
  if(!workloads.length) emptyRows('workload-rows',5,'还没有服务，点击「创建服务」开始部署。');
  $('image-rows').replaceChildren(); $('image-select').replaceChildren();
  for(const image of images) { const tr=element('tr',''), name=element('td',image.tag||image.id); name.append(element('small',image.reference)); tr.append(name,element('td',image.os+'/'+image.architecture),element('td',image.digest)); $('image-rows').append(tr); const option=element('option',(image.tag||'镜像')+' · '+image.id); option.value=image.id; $('image-select').append(option); }
  if(!images.length) emptyRows('image-rows',3,'尚无已核验镜像，请先通过上传接口或验收脚本推送镜像。');
  $('new-workload').disabled=!token||!images.length||busy;
}
function renderOperations() { $('operation-list').replaceChildren(); for(const op of [...operations.values()].reverse()) { const row=element('article',''), info=element('div',op.action||'操作'); info.append(element('small',op.id),element('small',op.error?.code||op.stage||'')); row.append(info,badge(op.status)); $('operation-list').append(row); } if(!operations.size) $('operation-list').append(element('p','暂无操作记录','empty')); }
async function refresh() {
  if(!token||loading||$('create-dialog').open||$('action-dialog').open) return;
  loading=true; const session=epoch;
  try { const [ws,ims]=await Promise.all([list('/v1/workloads'),list('/v1/images')]);
    for(const [id,op] of operations) if(!['succeeded','failed'].includes(op.status)) operations.set(id,await api('/v1/operations/'+encodeURIComponent(id)));
    if(session!==epoch) return; workloads=ws; images=ims; render(); renderOperations(); notice('已同步 · '+new Date().toLocaleTimeString('zh-CN'));
  } catch(e) { if(session===epoch) notice(e.message,true); } finally { loading=false; }
}
function reset() { epoch++; token=''; workloads=[]; images=[]; operations.clear(); pendingKeys.clear(); $('token').value=''; $('connection-state').textContent='未连接'; for(const id of ['refresh','new-workload','disconnect']) $(id).disabled=true; $('lookup').querySelector('button').disabled=true; render(); renderOperations(); $('total').textContent=$('ready').textContent=$('image-count').textContent='—'; document.querySelectorAll('dialog').forEach(d=>d.close()); }
$('connect').addEventListener('submit',async e=>{ e.preventDefault(); const value=$('token').value.trim().replace(/^Bearer\s+/i,''); reset(); token=value; const session=epoch; try { await api('/v1/capabilities'); if(session!==epoch)return; $('connection-state').textContent='已连接'; $('disconnect').disabled=$('refresh').disabled=false; $('lookup').querySelector('button').disabled=false; await refresh(); } catch(err) { if(session===epoch){reset(); notice(err.message,true);} } });
$('disconnect').onclick=()=>{reset();notice('已断开，凭据已从页面内存清除。');};
$('refresh').onclick=refresh;
$('new-workload').onclick=()=>{$('create-error').textContent='';$('create-dialog').showModal();};
document.querySelectorAll('.close').forEach(b=>b.onclick=()=>b.closest('dialog').close());
async function submit(form, errorId, request) { if(busy)return; busy=true; const button=form.querySelector('[type=submit]'); button.disabled=true; $(errorId).textContent=''; const session=epoch; try { const op=await request(); if(session!==epoch)return; operations.set(op.operation_id,{id:op.operation_id,status:'pending',stage:'已接收'}); form.closest('dialog').close(); notice('操作已接收，正在等待集群执行。'); renderOperations(); } catch(e) { if(session===epoch) $(errorId).textContent=e.message; } finally { busy=false; button.disabled=false; if(session===epoch) await refresh(); } }
$('create-form').addEventListener('submit',e=>{e.preventDefault();const f=new FormData(e.target);const port=Number(f.get('port'));const body={name:f.get('name'),image_id:f.get('image_id'),cluster_id:f.get('cluster_id'),type:'service',replicas:Number(f.get('replicas')),resources:{gpu_per_replica:Number(f.get('gpu')),cpu_cores:Number(f.get('cpu')),memory_gib:Number(f.get('memory'))},ports:[{name:'http',port,protocol:'TCP'}],readiness:{type:'http',path:f.get('path'),port},termination_grace_seconds:10};submit(e.target,'create-error',()=>api('/v1/workloads','POST',body));});
function showAction(w,action,label) { selected={...w,action}; $('action-title').textContent=label+' · '+w.name; $('action-description').textContent=action==='delete'?'将删除此服务及所属集群资源。镜像保留。': '基于版本 v'+w.version+' 提交，完成状态以实际集群观察为准。'; $('replicas-label').hidden=action!=='scale'; $('replicas').disabled=action!=='scale'; $('replicas').value=w.replicas; $('action-error').textContent=''; $('action-dialog').showModal(); }
$('action-form').addEventListener('submit',e=>{e.preventDefault();const w=selected;submit(e.target,'action-error',()=>w.action==='delete'?api('/v1/workloads/'+w.id,'DELETE',undefined,{'If-Match':'"'+w.version+'"'}):api('/v1/workloads/'+w.id+'/'+w.action,'POST',{expected_version:w.version,...(w.action==='scale'?{replicas:Number($('replicas').value)}:{})}));});
$('lookup').addEventListener('submit',async e=>{e.preventDefault();try{const id=$('operation-id').value.trim();const op=await api('/v1/operations/'+encodeURIComponent(id));operations.set(id,op);renderOperations();}catch(err){notice(err.message,true);}});
fetch('/actuator/health',{signal:AbortSignal.timeout(10000)}).then(r=>r.json()).then(h=>{$('health').textContent=h.status==='UP'?'● 管理服务在线':'服务未就绪';$('health').className='badge '+(h.status==='UP'?'good':'bad');}).catch(()=>{$('health').textContent='服务连接失败';});
setInterval(refresh,5000);
