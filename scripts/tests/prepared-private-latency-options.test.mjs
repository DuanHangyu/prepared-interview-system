import test from 'node:test';
import assert from 'node:assert/strict';

const url=new URL('../prepared-private-latency-options.mjs',import.meta.url);
const implementation=await import(url.href).catch(error=>{
  if(error.code==='ERR_MODULE_NOT_FOUND'&&error.url===url.href)return {};
  throw error;
});
const options=(...args)=>{
  assert.equal(typeof implementation.privateLatencyOptions,'function','private diagnostic privacy policy must be implemented');
  return implementation.privateLatencyOptions(...args);
};
test('bounded diagnostic options suppress optional provider content without disabling JDK timing samples',()=>{
  assert.deepEqual(options('/project/.runtime/control/latency.jfc','/project/.runtime/validation-123456/latency.jfr'),[
    '-Dio.lettuce.core.jfr=false',
    '-XX:FlightRecorderOptions=memorysize=8m,stackdepth=64',
    '-XX:StartFlightRecording=name=PreparedLatency,settings=/project/.runtime/control/latency.jfc,filename=/project/.runtime/validation-123456/latency.jfr,maxsize=32m,maxage=20m,dumponexit=true'
  ]);
});
test('the returned policy cannot be mutated or inherit arbitrary options',()=>{
  const result=options('/a/latency.jfc','/a/result.jfr');
  assert.ok(Object.isFrozen(result)); assert.throws(()=>result.push('-Dio.lettuce.core.jfr=true'),TypeError);
  assert.ok(result.every(x=>!x.includes('secret-value')));
});
for(const value of [undefined,null,42,'relative.jfc','/a/../latency.jfc','/a/./latency.jfc','/a/foo bar.jfc','/a/file,jfr.jfc','/a/file;jfr.jfc','/a/$(id).jfc','/a/file\n.jfc','/a/file.jfr']) {
  test('rejects unsafe settings path '+JSON.stringify(value),()=>assert.throws(()=>options(value,'/a/file.jfr'),/UNSAFE_LATENCY_PATH/));
}
for(const value of [null,'relative.jfr','/a/../file.jfr','/a/file,settings=other.jfr','/a/"file.jfr','/a/file.jfc']) {
  test('rejects unsafe recording path '+JSON.stringify(value),()=>assert.throws(()=>options('/a/file.jfc',value),/UNSAFE_LATENCY_PATH/));
}
