'use strict';
const SampleData = (() => {
  const image = (version, digit) => ({
    id:'sample-image-'+version, tag:version, os:'linux', architecture:'amd64',
    digest:'sha256:'+digit.repeat(64), reference:'sample.invalid/inference@sha256:'+digit.repeat(64)
  });
  const node = (name, ready, cpu, memory, gpu, allowed) => ({
    name, uid:'sample-uid-'+name, resource_version:'1', ready, unschedulable:false,
    maintenance_allowed:allowed, kubelet_version:'v1.34.0 · 样例', runtime_version:'containerd · 样例',
    capacity:{cpu,memory,'nvidia.com/gpu':gpu}, allocatable:{cpu,memory,'nvidia.com/gpu':gpu},
    conditions:[{type:'Ready',status:ready},{type:'MemoryPressure',status:'False'},{type:'DiskPressure',status:'False'}],
    prewarmed_images:[]
  });
  function create(scenario='normal') {
    if(!['normal','pdb','unmanaged'].includes(scenario)) throw new Error('未知模拟场景');
    return {
      scenario, sequence:0,
      images:[image('v1','a'),image('v2','b')],
      workloads:[{
        id:'sample-workload-1', name:'inference-demo', replicas:1, version:1, image_id:'sample-image-v1',
        observed:{phase:'ready',instances:[{name:'sample-pod-1',node:'sample-worker-1',phase:'Running'}],
          access:{scope:'cluster',host:'sample.invalid（模拟地址）'}}
      }],
      snapshot:{cluster_id:'sample-cluster',stale:false,observed_at:new Date().toISOString(),items:[
        node('sample-control-plane','True','8','16Gi','未上报',false),
        node('sample-worker-1','True','16','64Gi','8',true),
        node('sample-worker-2','True','16','64Gi','8',false)
      ]}
    };
  }
  function applyNodeAction(state, name, action, imageId) {
    const target=state.snapshot.items.find(n=>n.name===name);
    if(!target || !target.maintenance_allowed) throw new Error('该样例节点未开放操作');
    const operation={id:'sample-op-'+(++state.sequence),target_id:name,action,status:'succeeded',stage:'模拟完成'};
    const change=unschedulable=>{ target.unschedulable=unschedulable; target.resource_version=String(Number(target.resource_version)+1); };
    if(action==='prewarm') {
      const selected=state.images.find(i=>i.id===imageId);
      if(!selected) throw new Error('样例镜像不存在');
      operation.stage='sample_image_cached';
      if(!target.prewarmed_images.includes(selected.tag)) target.prewarmed_images.push(selected.tag);
    } else if(action==='drain') {
      if(state.scenario==='unmanaged') {
        operation.status='blocked'; operation.stage='blocked'; operation.error={code:'node_contains_unmanaged_pods'};
      } else if(state.scenario==='pdb') {
        change(true); operation.status='blocked'; operation.stage='blocked'; operation.error={code:'eviction_pdb_or_rate_limited'};
      } else {
        const alternative=state.snapshot.items.find(n=>n.name==='sample-worker-2' && n.ready==='True' && !n.unschedulable);
        if(!alternative) {
          operation.status='blocked'; operation.stage='blocked'; operation.error={code:'no_alternative_ready_node'};
        } else {
          change(true); operation.stage='drained';
          for(const workload of state.workloads) for(const instance of workload.observed.instances) {
            if(instance.node===name) instance.node=alternative.name;
          }
        }
      }
    } else if(action==='cordon') { change(true); operation.stage='ready'; }
    else if(action==='uncordon') { change(false); operation.stage='ready'; }
    else throw new Error('未知模拟操作');
    state.snapshot.observed_at=new Date().toISOString();
    return operation;
  }
  return {create,applyNodeAction};
})();
if(typeof module!=='undefined' && module.exports) module.exports=SampleData;
