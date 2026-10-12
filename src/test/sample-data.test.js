const {test}=require('node:test');
const assert=require('node:assert/strict');
const sample=require('../main/resources/static/sample-data.js');

test('normal drain moves the sample instance and preserves the second worker',()=>{
  const state=sample.create();
  const result=sample.applyNodeAction(state,'sample-worker-1','drain');
  assert.equal(result.status,'succeeded');
  assert.equal(state.snapshot.items[1].unschedulable,true);
  assert.equal(state.workloads[0].observed.instances[0].node,'sample-worker-2');
  assert.equal(state.snapshot.items[2].unschedulable,false);
});
test('PDB and unmanaged scenarios remain visibly blocked',()=>{
  const pdb=sample.create('pdb');
  assert.equal(sample.applyNodeAction(pdb,'sample-worker-1','drain').error.code,'eviction_pdb_or_rate_limited');
  assert.equal(pdb.snapshot.items[1].unschedulable,true);
  const unmanaged=sample.create('unmanaged');
  assert.equal(sample.applyNodeAction(unmanaged,'sample-worker-1','drain').error.code,'node_contains_unmanaged_pods');
  assert.equal(unmanaged.snapshot.items[1].unschedulable,false);
});
test('prewarm only changes in-memory labels and never creates a real registry reference',()=>{
  const state=sample.create();
  const operation=sample.applyNodeAction(state,'sample-worker-1','prewarm','sample-image-v2');
  assert.equal(operation.stage,'sample_image_cached');
  assert.deepEqual(state.snapshot.items[1].prewarmed_images,['v2']);
  assert.match(state.images[1].reference,/^sample\.invalid\//);
  assert.throws(()=>sample.applyNodeAction(state,'sample-control-plane','drain'));
});
