const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const SampleData=require('../main/resources/static/sample-data.js');

class Element {
  constructor() { this.children=[]; this.textContent=''; this.hidden=false; this.disabled=false; this.open=false; this.value=''; }
  append(...items) { this.children.push(...items); }
  replaceChildren(...items) { this.children=items; }
  addEventListener(name,handler) { this[name]=handler; }
  querySelector() { return new Element(); }
}

test('standalone sample opens without network and drain only mutates browser state',()=>{
  const nodes=new Map();
  const document={
    getElementById(id) { if(!nodes.has(id)) nodes.set(id,new Element()); return nodes.get(id); },
    createElement() { return new Element(); },
    querySelectorAll() { return []; }
  };
  const $=id=>document.getElementById(id);
  $('sample-scenario').value='normal';
  let requests=0;
  const context=vm.createContext({document,SampleData,location:{search:'?mode=sample'},URLSearchParams,
    fetch() { requests++; throw new Error('network is forbidden in sample mode'); },
    setInterval() {},AbortSignal,crypto:globalThis.crypto,confirm() { return true; }});
  const source=fs.readFileSync(path.join(__dirname,'../main/resources/static/app.js'),'utf8');
  vm.runInContext(source,context);
  assert.equal(requests,0);
  assert.equal($('sample-banner').hidden,false);
  assert.equal($('connection-state').textContent,'模拟数据');
  assert.equal($('new-workload').disabled,true);
  const workerRow=$('node-rows').children[1];
  const buttons=workerRow.children[6].children;
  assert.equal(buttons[1].textContent,'模拟排空');
  buttons[1].onclick();
  assert.equal(vm.runInContext('sampleState.workloads[0].observed.instances[0].node',context),'sample-worker-2');
  assert.equal($('operation-list').children.length,1);
  assert.equal(requests,0);
  $('sample-scenario').value='pdb';
  $('sample-scenario').onchange();
  $('node-rows').children[1].children[6].children[1].onclick();
  assert.match($('notice').textContent,/eviction_pdb_or_rate_limited/);
  assert.equal(requests,0);
});
