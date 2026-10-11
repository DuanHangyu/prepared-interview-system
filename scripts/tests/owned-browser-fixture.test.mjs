import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {assertPreparedFixture,checkPreparedBrowserProof} from '../assert-owned-browser-fixture.mjs';
test('unowned small or malformed IDs remain rejected and legacy independent IDs are explicit',()=>{
  for(const id of [0,1,8,NaN,2.5])assert.throws(()=>assertPreparedFixture({},id),/INDEPENDENT_BROWSER_FIXTURE_REQUIRED/);
  assert.equal(assertPreparedFixture({},9),true);
});
test('content-free synthetic owner proof accepts positive IDs but rejects scope, expiry, identity and private-file violations',t=>{
  const parent=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'prepared-browser-unit-'))),project=path.join(parent,'prepared-interview-system');
  fs.mkdirSync(project,{mode:0o700});fs.mkdirSync(path.join(project,'.runtime'),{mode:0o700});const run=fs.mkdtempSync(path.join(project,'.runtime/validation-')),file=path.join(run,'browser-database.json');
  t.after(()=>fs.rmSync(parent,{recursive:true,force:true}));
  const proof={schemaVersion:1,nativePid:process.pid,ownerPid:process.pid,nativePort:24444,nonce:'11111111-1111-4111-8111-111111111111'};
  const env={LIVE_E2E:'1',PREPARED_VALIDATION_BROWSER_DESCRIPTOR:file,PREPARED_VALIDATION_BROWSER_RUN_ID:proof.nonce};
  const write=()=>fs.writeFileSync(file,JSON.stringify(proof),{mode:0o600});write();
  for(const id of [1,8,9,100])assert.equal(checkPreparedBrowserProof(project,env,id),true);
  for(const mutation of [{schemaVersion:2},{nativePid:99999999},{ownerPid:0},{nativePort:13317},{nativePort:1},{nativePort:65536},{nonce:'wrong'},{password:'not-allowed'}]){
    fs.writeFileSync(file,JSON.stringify({...proof,...mutation}));assert.throws(()=>checkPreparedBrowserProof(project,env,1),/INDEPENDENT_BROWSER_FIXTURE_REQUIRED/);
  }
  write();
  for(const mutation of [{LIVE_E2E:'0'},{PREPARED_VALIDATION_BROWSER_RUN_ID:'invalid'},{PREPARED_VALIDATION_BROWSER_DESCRIPTOR:'relative'}, {PREPARED_VALIDATION_BROWSER_DESCRIPTOR:path.join(run,'other.json')}])
    assert.throws(()=>checkPreparedBrowserProof(project,{...env,...mutation},1),/INDEPENDENT_BROWSER_FIXTURE_REQUIRED/);
  assert.throws(()=>checkPreparedBrowserProof(project,{PREPARED_VALIDATION_BROWSER_RUN_ID:proof.nonce},9));
  assert.throws(()=>checkPreparedBrowserProof(path.dirname(project),env,1));assert.throws(()=>checkPreparedBrowserProof(project,env,0));
  fs.chmodSync(file,0o644);assert.throws(()=>checkPreparedBrowserProof(project,env,1));fs.chmodSync(file,0o600);
  fs.writeFileSync(file,' '.repeat(4097));assert.throws(()=>checkPreparedBrowserProof(project,env,1));
  write();const saved=file+'.saved';fs.renameSync(file,saved);fs.symlinkSync(saved,file);assert.throws(()=>checkPreparedBrowserProof(project,env,1));
});
test('a caller owned flag or external descriptor cannot exempt an ID from ownership checks',()=>{
  for(const env of [{owned:'true'}, {PREPARED_VALIDATION_BROWSER_DESCRIPTOR:'/tmp/forged.json',PREPARED_VALIDATION_BROWSER_RUN_ID:'11111111-1111-4111-8111-111111111111'}])
    assert.throws(()=>assertPreparedFixture(env,1),/INDEPENDENT_BROWSER_FIXTURE_REQUIRED/);
});
