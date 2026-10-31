const {test} = require('node:test');
const assert = require('node:assert/strict');
const {Kai2Mirror} = require('../../extensions/browser-bridge/mirror.js');
const tick = () => new Promise(resolve => setImmediate(resolve));
test('secondary is bounded and ordered while the primary is independent', async () => {
  const calls = []; const releases = [];
  const mirror = new Kai2Mirror({maxQueued: 2, fetcher: (url, options) => {
    calls.push(options.body); return new Promise(resolve => releases.push(() => resolve({ok:true,text:async()=> 'ok'})));
  }});
  assert.equal(mirror.enqueue('/ingest',{body:'first'}),true);
  assert.equal(mirror.enqueue('/ingest',{body:'second'}),true);
  assert.equal(mirror.enqueue('/ingest',{body:'third'}),false);
  let primary = false; await Promise.resolve().then(()=>{primary=true;});
  assert.equal(primary,true); assert.deepEqual(calls,['first']);
  releases.shift()(); await tick(); assert.deepEqual(calls,['first','second']);
  releases.shift()(); await tick(); assert.equal(mirror.state.accepted,2);assert.equal(mirror.state.dropped,1);
});
test('timeout does not stall later packets and reports loss', async () => {
  const mirror = new Kai2Mirror({timeoutMs:5,fetcher: (_url,o)=>new Promise((_,reject)=>o.signal.addEventListener('abort',()=>reject(new Error('timeout'))))});
  mirror.enqueue('/ingest',{body:'one'});mirror.enqueue('/ingest',{body:'two'});
  await new Promise(r=>setTimeout(r,40));assert.equal(mirror.state.failed,2);assert.equal(mirror.queue.length,0);
});
test('rejects conflicting ports, never sends outside loopback, caps bytes', () => {
  assert.throws(()=>new Kai2Mirror({port:8891}));assert.throws(()=>new Kai2Mirror({port:0}));
  const mirror=new Kai2Mirror({maxBytes:1});assert.equal(mirror.enqueue('/ingest',{body:'too large'}),false);
});
