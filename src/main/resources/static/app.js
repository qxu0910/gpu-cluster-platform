'use strict';
const $ = id => document.getElementById(id);
let token = '', connected = false, epoch = 0, loading = false, busy = false, workloads = [], images = [], selected;
let sampleMode = false, sampleState = null, capabilities = {};
let diagnosticWorkloadId = null;
const operations = new Map(), pendingKeys = new Map();
const labels = {ready:'已就绪', stopped:'已停止', scheduling:'调度中', pulling:'拉取中', starting:'启动中', blocked:'受阻', unknown:'未知', deleting:'删除中', deleted:'已删除', pending:'待执行', running:'执行中', succeeded:'成功', failed:'失败'};
function element(tag, text, cls) { const e = document.createElement(tag); e.textContent = text; if(cls) e.className = cls; return e; }
function notice(text, error = false) { $('notice').textContent = text; $('notice').className = error ? 'error' : ''; }
function badge(state) { return element('span', labels[state] || state, 'badge '+(['ready','succeeded','stopped'].includes(state)?'good':['failed','blocked','unknown'].includes(state)?'bad':'')); }
async function api(path, method='GET', body, headers={}) {
  if(sampleMode) throw new Error('模拟模式不会调用管理接口。');
  const session = epoch;
  const fingerprint = method+' '+path+' '+JSON.stringify(body)+' '+JSON.stringify(headers);
  const h = {...headers};
  if(token) h.Authorization='Bearer '+token;
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
    if(sampleMode) state.append(element('small','模拟 Pod：'+(w.observed?.instances||[]).map(i=>i.node).join(', ')));
    const controls=element('td',''), buttons=element('div','','actions');
    const diagnostic=element('button','诊断','secondary'); diagnostic.onclick=()=>showDiagnostics(w.id); buttons.append(diagnostic);
    for(const [action,label] of [['start','启动'],['stop','停止'],['scale','扩缩容'],['delete','删除']]) { const b=element('button',label,'secondary'); b.disabled=sampleMode||busy||(action==='start'&&w.replicas!==0); b.addEventListener('click',()=>showAction(w,action,label)); buttons.append(b); }
    controls.append(buttons); tr.append(name,state,element('td',w.replicas+' / v'+w.version),element('td',w.observed?.access?.host||'—'),controls); $('workload-rows').append(tr);
  }
  if(!workloads.length) emptyRows('workload-rows',5,'还没有服务，点击「创建服务」开始部署。');
  $('image-rows').replaceChildren(); $('image-select').replaceChildren();
  for(const image of images) { const tr=element('tr',''), name=element('td',image.tag||image.id); name.append(element('small',image.reference)); tr.append(name,element('td',(image.os||'linux')+'/'+(image.architectures||[image.architecture]).filter(Boolean).join(', ')),element('td',image.digest)); $('image-rows').append(tr); const option=element('option',(image.tag||'镜像')+' · '+image.id); option.value=image.id; $('image-select').append(option); }
  if(!images.length) emptyRows('image-rows',3,'尚无已核验镜像，请先通过上传接口或验收脚本推送镜像。');
  $('new-workload').disabled=sampleMode||!connected||!images.length||busy;
  if($('diagnostic-dialog').open) renderDiagnostics();
}
function showDiagnostics(id) { diagnosticWorkloadId=id; renderDiagnostics(); $('diagnostic-dialog').showModal(); }
function renderDiagnostics() {
  const w=workloads.find(item=>item.id===diagnosticWorkloadId);
  if(!w) { $('diagnostic-dialog').close(); diagnosticWorkloadId=null; return; }
  $('diagnostic-title').textContent='服务诊断 · '+w.name;
  const observed=w.observed||{}, details=observed.diagnostics||{};
  $('diagnostic-summary').textContent='状态：'+(labels[observed.phase]||observed.phase||'待观察')+(observed.error_code?' · 错误码：'+observed.error_code:'');
  $('diagnostic-freshness').textContent=sampleMode?'模拟数据 · 不来自集群':observed.stale?'观察已过期':w.observed_at?'采集于 '+new Date(w.observed_at).toLocaleString('zh-CN'):'尚未采集';
  $('diagnostic-list').replaceChildren();
  if(details.status==='forbidden'||details.status==='unavailable') {
    $('diagnostic-list').append(element('p',details.status==='forbidden'?'执行器没有读取事件的权限；服务状态仍可用。':'Kubernetes 事件暂不可用；服务状态仍可用。','empty')); return;
  }
  for(const event of details.events||[]) {
    const row=element('article',''), reason=element('strong',event.reason||'事件');
    row.append(reason,element('span',event.category||''),element('small',event.pod||'Pod 未知'));
    row.append(element('small',(event.last_seen_at?new Date(event.last_seen_at).toLocaleString('zh-CN'):'时间未知')+' · '+(event.count||1)+' 次'));
    $('diagnostic-list').append(row);
  }
  if(!details.events?.length) $('diagnostic-list').append(element('p',sampleMode?'模拟样例没有真实 Kubernetes 事件。':details.status==='idle'?'服务已就绪，无需读取异常事件。':'当前 Pod 没有可展示的异常事件。','empty'));
  if(details.status==='partial') $('diagnostic-list').append(element('small','Pod 或事件数量较多，仅展示部分记录。'));
}
function renderOperations() { $('operation-list').replaceChildren(); for(const op of [...operations.values()].reverse()) { const row=element('article',''), info=element('div',op.action||'操作'); info.append(element('small',op.id),element('small',op.error?.code||op.stage||'')); row.append(info,badge(op.status)); $('operation-list').append(row); } if(!operations.size) $('operation-list').append(element('p','暂无操作记录','empty')); }
async function refresh() {
  if(!connected||loading||$('create-dialog').open||$('action-dialog').open) return;
  if(sampleMode) { workloads=sampleState.workloads; images=sampleState.images; render(); renderOperations(); renderNodeSnapshot(sampleState.snapshot,sampleState.snapshot.items); notice('模拟数据 · 仅浏览器内存 · '+new Date().toLocaleTimeString('zh-CN')); return; }
  loading=true; const session=epoch;
  try { const [ws,ims]=await Promise.all([list('/v1/workloads'),list('/v1/images')]);
    for(const [id,op] of operations) if(!['succeeded','failed'].includes(op.status)) operations.set(id,await api('/v1/operations/'+encodeURIComponent(id)));
    if(session!==epoch) return; workloads=ws; images=ims; await refreshNodes(); if(session!==epoch) return; render(); renderOperations(); notice('已同步 · '+new Date().toLocaleTimeString('zh-CN'));
  } catch(e) { if(session===epoch) notice(e.message,true); } finally { loading=false; }
}
function reset() { resetNodes(); epoch++; token=''; connected=false; sampleMode=false; sampleState=null; capabilities={}; workloads=[]; images=[]; diagnosticWorkloadId=null; operations.clear(); pendingKeys.clear(); $('token').value=''; $('connection-state').textContent='未连接'; $('sample-banner').hidden=true; $('sample-exit').hidden=true; for(const id of ['refresh','new-workload','disconnect']) $(id).disabled=true; $('lookup').querySelector('button').disabled=true; render(); renderOperations(); $('total').textContent=$('ready').textContent=$('image-count').textContent='—'; document.querySelectorAll('dialog').forEach(d=>{if(d.open)d.close();}); }
function connectedAs(label, session) { capabilities=session; connected=true; $('connection-state').textContent=label; $('connection-panel').hidden=true; $('disconnect').disabled=$('refresh').disabled=false; $('lookup').querySelector('button').disabled=false; return refresh(); }
$('connect').addEventListener('submit',async e=>{ e.preventDefault(); const value=$('token').value.trim().replace(/^Bearer\s+/i,''); reset(); token=value; const session=epoch; try { const info=await api('/v1/capabilities'); if(session!==epoch)return; await connectedAs('已连接',info); } catch(err) { if(session===epoch){reset(); $('connection-panel').hidden=false; notice(err.message,true);} } });
$('disconnect').onclick=()=>{reset();$('connection-panel').hidden=false;notice('已断开，凭据已从页面内存清除。');};
$('sample-enter').onclick=()=>enterSample();
$('sample-exit').onclick=()=>{reset();$('connection-panel').hidden=false;$('health').textContent='未连接管理接口';$('health').className='badge';notice('已退出模拟样例。');};
$('sample-scenario').onchange=()=>{if(sampleMode)enterSample();};
function enterSample() { reset(); sampleMode=true; connected=true; sampleState=SampleData.create($('sample-scenario').value); $('connection-state').textContent='模拟数据'; $('connection-panel').hidden=true; $('sample-banner').hidden=false; $('sample-exit').hidden=false; $('refresh').disabled=false; $('lookup').querySelector('button').disabled=false; $('health').textContent='模拟数据 · 未连接集群'; $('health').className='badge sample'; refresh(); }
$('refresh').onclick=refresh;
$('new-workload').onclick=()=>{$('create-error').textContent='';$('create-dialog').showModal();};
document.querySelectorAll('.close').forEach(b=>b.onclick=()=>b.closest('dialog').close());
async function submit(form, errorId, request) { if(busy)return; busy=true; const button=form.querySelector('[type=submit]'); button.disabled=true; $(errorId).textContent=''; const session=epoch; try { const op=await request(); if(session!==epoch)return; operations.set(op.operation_id,{id:op.operation_id,status:'pending',stage:'已接收'}); form.closest('dialog').close(); notice('操作已接收，正在等待集群执行。'); renderOperations(); } catch(e) { if(session===epoch) $(errorId).textContent=e.message; } finally { busy=false; button.disabled=false; if(session===epoch) await refresh(); } }
$('create-form').addEventListener('submit',e=>{e.preventDefault();const f=new FormData(e.target);const port=Number(f.get('port'));const body={name:f.get('name'),image_id:f.get('image_id'),cluster_id:f.get('cluster_id'),type:'service',replicas:Number(f.get('replicas')),resources:{gpu_per_replica:Number(f.get('gpu')),cpu_cores:Number(f.get('cpu')),memory_gib:Number(f.get('memory'))},ports:[{name:'http',port,protocol:'TCP'}],readiness:{type:'http',path:f.get('path'),port},termination_grace_seconds:10};submit(e.target,'create-error',()=>api('/v1/workloads','POST',body));});
function showAction(w,action,label) { selected={...w,action}; $('action-title').textContent=label+' · '+w.name; $('action-description').textContent=action==='delete'?'将删除此服务及所属集群资源。镜像保留。': '基于版本 v'+w.version+' 提交，完成状态以实际集群观察为准。'; $('replicas-label').hidden=action!=='scale'; $('replicas').disabled=action!=='scale'; $('replicas').value=w.replicas; $('action-error').textContent=''; $('action-dialog').showModal(); }
$('action-form').addEventListener('submit',e=>{e.preventDefault();const w=selected;submit(e.target,'action-error',()=>w.action==='delete'?api('/v1/workloads/'+w.id,'DELETE',undefined,{'If-Match':'"'+w.version+'"'}):api('/v1/workloads/'+w.id+'/'+w.action,'POST',{expected_version:w.version,...(w.action==='scale'?{replicas:Number($('replicas').value)}:{})}));});
$('lookup').addEventListener('submit',async e=>{e.preventDefault();try{const id=$('operation-id').value.trim();const op=sampleMode?operations.get(id):await api('/v1/operations/'+encodeURIComponent(id));if(!op)throw new Error('模拟操作不存在');operations.set(id,op);renderOperations();}catch(err){notice(err.message,true);}});
function checkHealth() { fetch('/actuator/health',{signal:AbortSignal.timeout(10000)}).then(r=>r.json()).then(h=>{if(sampleMode)return;$('health').textContent=h.status==='UP'?'● 管理服务在线':'服务未就绪';$('health').className='badge '+(h.status==='UP'?'good':'bad');}).catch(()=>{if(!sampleMode)$('health').textContent='服务连接失败';}); }
setInterval(refresh,5000);
async function connectDemo() {
  const current=epoch;
  try { const session=await api('/v1/capabilities'); if(current===epoch)await connectedAs('演示用户 · '+session.subject,session); }
  catch(error) { if(current===epoch && !sampleMode) { $('connection-panel').hidden=false; notice('自动连接不可用，请使用已有凭据连接：'+error.message,true); } }
}
if(new URLSearchParams(location.search).get('mode')==='sample') enterSample();
else { checkHealth(); connectDemo(); }

function resetNodes() {
  emptyRows('node-rows',7,'连接后显示节点状态');
  $('node-freshness').textContent='等待连接'; $('node-freshness').className='badge'; $('node-error').textContent='';
}
async function refreshNodes() {
  const session=epoch;
  try {
    let items=[], snapshot;
    for(let offset=0;;offset+=100) {
      const page=await api('/v1/nodes?limit=100&offset='+offset); snapshot=page; items.push(...page.items);
      if(page.items.length<100) break;
    }
    if(session===epoch) renderNodeSnapshot(snapshot,items);
  } catch(error) { if(session!==epoch) return; emptyRows('node-rows',7,'节点状态暂不可用'); $('node-freshness').textContent='Unknown'; $('node-freshness').className='badge bad'; $('node-error').textContent=error.message; }
}
function renderNodeSnapshot(snapshot,items) {
  $('node-freshness').textContent=sampleMode?'模拟 · 浏览器内存':snapshot.stale?'Unknown · 观察已过期':'采集于 '+new Date(snapshot.observed_at).toLocaleTimeString('zh-CN');
  $('node-freshness').className='badge '+(sampleMode?'sample':snapshot.stale?'bad':'good');
  $('node-error').textContent=sampleMode?'样例节点不会反映真实 Kubernetes 状态。':snapshot.error_code|| (snapshot.stale?'执行器尚未取得新鲜观察，以下容量和条件为历史数据。':'');
  $('node-rows').replaceChildren();
  const quantity=(node,key)=> (node.capacity?.[key]??'未上报')+' / '+(node.allocatable?.[key]??'未上报');
  for(const node of items) {
    const row=element('tr',''), name=element('td',node.name);
    name.append(element('small',(node.architecture||'架构未上报')+' · '+(node.operating_system||'系统未上报')+' · '+(node.os_image||'发行版未上报')));
    name.append(element('small','GPU '+(node.gpu_product||'型号未上报')+' · 驱动 '+(node.driver_version||'未上报')));
    name.append(element('small','kubelet '+(node.kubelet_version||'—')+' · '+(node.runtime_version||'—')));
    if(sampleMode && node.prewarmed_images?.length) name.append(element('small','模拟预热：'+node.prewarmed_images.join(', ')));
    const state=element('td',''); state.append(badge(snapshot.stale?'unknown':node.ready==='True'?'ready':node.ready==='False'?'blocked':'unknown'));
    if(node.unschedulable) state.append(element('small','禁止新调度'));
    const pressure=(node.conditions||[]).filter(c=>c.type!=='Ready').map(c=>c.type+': '+c.status).join(' · ')||'未上报';
    const control=element('td','','node-actions');
    const button=element('button',node.unschedulable?'恢复调度':'停止调度','secondary');
    button.disabled=snapshot.stale||busy||!node.maintenance_allowed;
    button.onclick=()=>changeNode(node,node.unschedulable?'uncordon':'cordon');
    control.append(button);
    if(node.maintenance_allowed && (sampleMode||capabilities.node_drain)) {
      const drain=element('button',sampleMode?'模拟排空':'排空节点','secondary');
      drain.disabled=snapshot.stale||busy;
      drain.onclick=()=>changeNode(node,'drain');
      control.append(drain);
    }
    if(sampleMode && node.maintenance_allowed) {
      const prewarm=element('button','模拟预热 v2','secondary');
      prewarm.onclick=()=>changeNode(node,'prewarm');
      control.append(prewarm);
    }
    if(!node.maintenance_allowed) control.append(element('small',sampleMode?'此样例节点只读':'未授权维护'));
    row.append(name,state,element('td',quantity(node,'cpu')),element('td',quantity(node,'memory')),element('td',quantity(node,'nvidia.com/gpu')),element('td',pressure),control);
    $('node-rows').append(row);
  }
  if(!items.length) emptyRows('node-rows',7,snapshot.stale?'尚无可用节点观察，请检查执行器与集群连接。':'集群未返回节点。');
}
async function changeNode(node,action) {
  if(sampleMode) {
    try {
      const op=SampleData.applyNodeAction(sampleState,node.name,action,action==='prewarm'?'sample-image-v2':undefined);
      operations.set(op.id,op); refresh();
      notice(op.status==='blocked'?'模拟排空受阻：'+op.error.code:'模拟操作完成：'+op.stage,op.status==='blocked');
    } catch(error) { notice(error.message,true); }
    return;
  }
  const message=action==='drain'?'排空节点 '+node.name+'？现有 Pod 将通过 Eviction API 迁移，结果以集群观察为准。':(action==='cordon'?'停止':'恢复')+'节点 '+node.name+' 的新工作负载调度？现有服务不会被驱逐。';
  if(!confirm(message)) return;
  try {
    const op=await api('/v1/nodes/'+encodeURIComponent(node.name)+'/'+action,'POST',
      {expected_uid:node.uid,expected_resource_version:node.resource_version});
    operations.set(op.operation_id,{id:op.operation_id,action:action==='drain'?'排空节点':action==='cordon'?'停止节点调度':'恢复节点调度',status:'pending',stage:'已接收'});
    renderOperations(); notice('节点操作已接收，正在等待集群实际状态。');
    await refresh();
  } catch(error) { notice(error.message,true); }
}
