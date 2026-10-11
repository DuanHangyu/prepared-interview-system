import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';

const implementation=()=>import('../prepared-browser-artifacts.mjs');
function fixture(t){
  const parent=fs.mkdtempSync(path.join(os.tmpdir(),'prepared-browser-artifacts-'));
  const root=path.join(parent,'prepared-interview-system');fs.mkdirSync(root,{mode:0o700});
  fs.mkdirSync(path.join(root,'.runtime'),{mode:0o700});
  t.after(()=>fs.rmSync(parent,{recursive:true,force:false}));return fs.realpathSync(root);
}
const main={pid:process.pid,ppid:process.ppid,entry:'playwright/cli.js'};

test('each independent invocation gets a private scope and does not adopt prior evidence',async t=>{
  const root=fixture(t),{prepareBrowserArtifacts}=await implementation(),first=prepareBrowserArtifacts(root,{},main);
  fs.mkdirSync(first.outputDir);fs.writeFileSync(path.join(first.outputDir,'failure.txt'),'prior failure',{mode:0o600});
  const second=prepareBrowserArtifacts(root,{},main);
  assert.notEqual(first.outputDir,second.outputDir);assert.equal(fs.readFileSync(path.join(first.outputDir,'failure.txt'),'utf8'),'prior failure');
  for(const item of [first,second]){assert.equal(path.dirname(path.dirname(item.outputDir)),path.join(root,'.runtime'));assert.equal(fs.statSync(path.dirname(item.outputDir)).mode&0o777,0o700);assert.equal(fs.statSync(path.join(path.dirname(item.outputDir),'scope.json')).mode&0o777,0o600);assert.equal(item.reportDir,path.join(path.dirname(item.outputDir),'report'));}
});
test('config reload and actual direct Playwright workers reuse only their issued scope',async t=>{
  const root=fixture(t),{prepareBrowserArtifacts}=await implementation(),env={},first=prepareBrowserArtifacts(root,env,main);
  assert.deepEqual(prepareBrowserArtifacts(root,env,main),first);
  for(const entry of ['playwright/lib/worker/workerProcessEntry.js','playwright/lib/loader/loaderProcessEntry.js'])assert.deepEqual(prepareBrowserArtifacts(root,env,{pid:main.pid+100,ppid:main.pid,entry}),first);
});
test('inherited scope cannot be used by a later independent CLI, unrelated worker or foreign process',async t=>{
  const root=fixture(t),{prepareBrowserArtifacts}=await implementation(),env={};prepareBrowserArtifacts(root,env,main);
  for(const identity of [{pid:main.pid+100,ppid:main.pid,entry:main.entry},{pid:main.pid+100,ppid:main.pid+1,entry:'playwright/lib/worker/workerProcessEntry.js'}])assert.throws(()=>prepareBrowserArtifacts(root,env,identity),/PRIVATE_BROWSER_ARTIFACT_SCOPE_REQUIRED/);
});
test('private runtime and all parent symlinks are rejected without changing other targets',async t=>{
  const root=fixture(t),{prepareBrowserArtifacts}=await implementation();fs.chmodSync(path.join(root,'.runtime'),0o755);
  assert.throws(()=>prepareBrowserArtifacts(root,{},main),/PRIVATE_BROWSER_ARTIFACT_SCOPE_REQUIRED/);fs.chmodSync(path.join(root,'.runtime'),0o700);
  const alias=path.join(path.dirname(root),'alias');fs.symlinkSync(root,alias);
  assert.throws(()=>prepareBrowserArtifacts(alias,{},main),/PRIVATE_BROWSER_ARTIFACT_SCOPE_REQUIRED/);
});
test('scope traversal, aliases, permissive marker and malformed marker are rejected',async t=>{
  const root=fixture(t),{prepareBrowserArtifacts}=await implementation(),env={},first=prepareBrowserArtifacts(root,env,main),scope=path.dirname(first.outputDir),marker=path.join(scope,'scope.json');
  for(const value of [root,scope+'/../'+path.basename(scope),'/tmp/foreign-browser-scope'])assert.throws(()=>prepareBrowserArtifacts(root,{PREPARED_BROWSER_ARTIFACT_SCOPE:value},main),/PRIVATE_BROWSER_ARTIFACT_SCOPE_REQUIRED/);
  fs.chmodSync(marker,0o644);assert.throws(()=>prepareBrowserArtifacts(root,env,main),/PRIVATE_BROWSER_ARTIFACT_SCOPE_REQUIRED/);fs.chmodSync(marker,0o600);
  fs.writeFileSync(marker,'{"schemaVersion":1,"ownerPid":0}');assert.throws(()=>prepareBrowserArtifacts(root,env,main),/PRIVATE_BROWSER_ARTIFACT_SCOPE_REQUIRED/);
});
test('result/report symlinks are rejected before the browser can clear another target',async t=>{
  const root=fixture(t),{prepareBrowserArtifacts}=await implementation(),env={},first=prepareBrowserArtifacts(root,env,main);
  fs.symlinkSync(root,first.outputDir);assert.throws(()=>prepareBrowserArtifacts(root,env,main),/PRIVATE_BROWSER_ARTIFACT_SCOPE_REQUIRED/);fs.unlinkSync(first.outputDir);
  fs.symlinkSync(root,first.reportDir);assert.throws(()=>prepareBrowserArtifacts(root,env,main),/PRIVATE_BROWSER_ARTIFACT_SCOPE_REQUIRED/);
});
test('the real configuration retains a trace on first failure and routes both artifact stores through the private allocator',()=>{
  const config=fs.readFileSync(new URL('../../defense-system/playwright.config.ts',import.meta.url),'utf8');
  assert.match(config,/trace:\s*["']retain-on-failure["']/);
  assert.match(config,/outputDir:\s*browserArtifacts\.outputDir/);
  assert.match(config,/outputFolder:\s*browserArtifacts\.reportDir/);
  assert.doesNotMatch(config,/outputDir:\s*["']node_modules\/\.cache\/playwright-results/);
});
