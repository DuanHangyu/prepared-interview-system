// Harness unit tests use temporary NEW-shaped roots and fake command processes.
// They do not claim database/browser/device/cloud integration.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { EventEmitter } from 'node:events';
import net from 'node:net';
import http from 'node:http';
import {spawnSync} from 'node:child_process';
import { createPlan, acquireLock, archivePriorEvidence, runCommand, runValidation, runCli, vacant, reachable, startApp, awaitApp, closeApps } from '../run-prepared-validation.mjs';

const environment = () => ({
  HOME: '/actual-user-home', PATH: '/usr/bin:/bin', JAVA_HOME: '/java',
  PREPARED_VALIDATION_MAVEN: '/maven/bin/mvn', PREPARED_MYSQL_BASEDIR: '/mysql',
  PREPARED_DB_JDBC_URL: 'jdbc:mysql://127.0.0.1:13317/prepared_interview?sslMode=REQUIRED',
  PREPARED_DB_USERNAME: 'prepared_app', PREPARED_DB_PASSWORD: 'app-test-only',
  MYSQL_INTEGRATION_SERVER_URL: 'jdbc:mysql://127.0.0.1:13317/?sslMode=REQUIRED',
  MYSQL_INTEGRATION_USERNAME: 'root', MYSQL_INTEGRATION_PASSWORD: 'admin-test-only',
  PREPARED_TEST_LOGIN_PASSWORD: 'login-test-only', PREPARED_REDIS_HOST: '127.0.0.1', PREPARED_REDIS_PORT: '16389',
});
function fixture(t) {
  const parent = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'prepared-validation-test-')));
  const root = path.join(parent, 'prepared-interview-system'); fs.mkdirSync(root);
  for (const name of ['.runtime', 'defense-assessment', 'defense-system']) fs.mkdirSync(path.join(root, name), { mode: 0o700 });
  t.after(() => fs.rmSync(parent, { recursive: true, force: true })); return root;
}
test('plan fixes full discovery, coverage and every required live condition', t => {
  const plan = createPlan(fixture(t), environment());
  assert.deepEqual(plan.backendVerify.args, ['--batch-mode', '--no-transfer-progress', 'verify', '-Pprepared-coverage', '-Dtest=*Test,*Tests,*IT', '-DskipTests=false', '-Dmaven.test.skip=false', '-Dmaven.test.failure.ignore=false']);
  assert.equal(plan.backendVerify.cwd, path.join(plan.root, 'defense-assessment'));
  for (const flag of ['PREPARED_LIVE_TESTS', 'PREPARED_BROWSER_FIXTURES', 'PREPARED_BROWSER_GENERATION_FIXTURES', 'PREPARED_FORMAL_BROWSER_FIXTURES', 'PREPARED_SUPPLEMENT_BROWSER_FIXTURES', 'PREPARED_STUDENT_SUPPLEMENT_BROWSER_FIXTURES', 'PREPARED_MYSQL_BOOTSTRAP_LIVE']) assert.equal(plan.backendVerify.env[flag], 'true');
  assert.equal(plan.apps.length, 6); assert.deepEqual(plan.apps.map(x => x.port), [13014,13015,13016,13017,13018,13019]);
  assert.deepEqual(plan.frontend.map(x => x.name), ['dependency-audit', 'typecheck', 'unit-coverage', 'build', 'browser-regression']);
});
test('local validation caps Maven and its forked tests at 1 GiB and each backend at 512 MiB',t=>{
  const plan=createPlan(fixture(t),environment());
  for(const command of [plan.build,plan.backendVerify])
    assert.equal(command.env.JAVA_TOOL_OPTIONS,'-Xms64m -Xmx1024m');
  for(const command of plan.apps.filter(c=>c.kind==='backend'))
    assert.equal(command.env.JAVA_TOOL_OPTIONS,'-Xms64m -Xmx512m');
  for(const command of [...plan.frontend,...plan.apps.filter(c=>c.kind==='frontend')])
    assert.equal(command.env.JAVA_TOOL_OPTIONS,undefined);
});
test('inherited Java injection cannot expand validation heaps or disable its original full gates',t=>{
  const plan=createPlan(fixture(t),{...environment(),JAVA_TOOL_OPTIONS:'-Xmx20g -DskipTests=true -javaagent:/untrusted.jar',
    JDK_JAVA_OPTIONS:'-Xmx20g -Dtest=OnlyOneTest',_JAVA_OPTIONS:'-Xmx20g'});
  assert.equal(plan.build.env.JAVA_TOOL_OPTIONS,'-Xms64m -Xmx1024m');
  assert.equal(plan.backendVerify.env.JAVA_TOOL_OPTIONS,'-Xms64m -Xmx1024m');
  assert.deepEqual(plan.backendVerify.args,['--batch-mode','--no-transfer-progress','verify','-Pprepared-coverage',
    '-Dtest=*Test,*Tests,*IT','-DskipTests=false','-Dmaven.test.skip=false','-Dmaven.test.failure.ignore=false']);
  for(const command of [plan.build,plan.backendVerify,...plan.frontend,...plan.apps]){
    assert.equal(command.env.JDK_JAVA_OPTIONS,undefined);assert.equal(command.env._JAVA_OPTIONS,undefined);
    assert.ok(!JSON.stringify(command).includes('/untrusted.jar'));
  }
});
test('full gate owns migration fixture without forwarding a supplied administrator identity', t => {
  const plan=createPlan(fixture(t),environment());
  assert.equal(plan.backendVerify.env.PREPARED_OWNED_MYSQL_MIGRATION_TESTS,'true');
  for(const key of ['MYSQL_INTEGRATION_SERVER_URL','MYSQL_INTEGRATION_USERNAME','MYSQL_INTEGRATION_PASSWORD'])
    assert.equal(plan.backendVerify.env[key],undefined);
  for(const command of [plan.build,...plan.frontend,...plan.apps])
    assert.equal(command.env.PREPARED_OWNED_MYSQL_MIGRATION_TESTS,undefined);
});
test('owned full migration verification does not require existing administrator credentials',t=>{
  const env=environment();for(const key of ['MYSQL_INTEGRATION_SERVER_URL','MYSQL_INTEGRATION_USERNAME','MYSQL_INTEGRATION_PASSWORD'])delete env[key];
  const p=createPlan(fixture(t),env);assert.equal(p.backendVerify.env.PREPARED_OWNED_MYSQL_MIGRATION_TESTS,'true');
});
test('an inherited false owned-mode flag cannot redirect full migrations to existing resources',t=>{
  const p=createPlan(fixture(t),{...environment(),PREPARED_OWNED_MYSQL_MIGRATION_TESTS:'false'});
  assert.equal(p.backendVerify.env.PREPARED_OWNED_MYSQL_MIGRATION_TESTS,'true');
});
test('each full plan owns fresh browser aliases and never adopts an inherited fixture scope',t=>{
  const root=fixture(t),env={...environment(),PREPARED_VALIDATION_RUN_ID:'inherited-unrelated-scope'};
  const first=createPlan(root,env),second=createPlan(root,env);
  const id=first.backendVerify.env.PREPARED_VALIDATION_RUN_ID;
  assert.match(id,/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/);
  assert.notEqual(id,second.backendVerify.env.PREPARED_VALIDATION_RUN_ID);
  for(const command of [first.build,...first.frontend,...first.apps])assert.equal(command.env.PREPARED_VALIDATION_RUN_ID,undefined);
});
test('inherited secrets, external proxies and skipping overrides cannot enter child environments', t => {
  const env = { ...environment(), PREPARED_LIVE_TESTS: 'false', PREPARED_DASHSCOPE_API_KEY: 'cloud-secret', MYSQL_PASSWORD: 'old-secret', NODE_OPTIONS: '--require=evil', MAVEN_OPTS: '-DskipTests', VUE_APP_PROXY_TARGET: 'http://old:8081', LIVE_E2E: '1', E2E_BASE_URL: 'http://old' };
  const p = createPlan(fixture(t), env);
  for (const command of [p.build, ...p.frontend, p.backendVerify, ...p.apps]) for (const key of ['PREPARED_DASHSCOPE_API_KEY','MYSQL_PASSWORD','NODE_OPTIONS','MAVEN_OPTS','LIVE_E2E','E2E_BASE_URL']) assert.equal(command.env[key], undefined);
  for (const command of [p.build, ...p.frontend, ...p.apps]) assert.equal(command.env.MYSQL_INTEGRATION_PASSWORD, undefined);
  for (const command of [p.build, ...p.frontend, ...p.apps.filter(x => x.kind === 'frontend')]) assert.equal(command.env.PREPARED_DB_PASSWORD, undefined);
  assert.equal(p.backendVerify.env.PREPARED_LIVE_TESTS, 'true');
  for (const app of p.apps.filter(x => x.kind === 'backend')) {
    assert.equal(app.env.PREPARED_MODELS_ENABLED, 'false'); assert.equal(app.env.PREPARED_PRIVATE_STORAGE_ENABLED, 'false');
    assert.equal(app.env.PREPARED_GENERATION_WORKER_ENABLED, 'false'); assert.equal(app.env.PREPARED_ANSWER_MEDIA_WORKER_ENABLED, 'false');
  }
  assert.equal(p.apps[3].env.VUE_APP_PROXY_TARGET, 'http://127.0.0.1:13016/');
  assert.equal(p.build.env.HOME, env.HOME);
});
test('missing requirements and non-isolated database/Redis fail before execution', t => {
  const root = fixture(t);
  for (const key of Object.keys(environment()).filter(x=>!x.startsWith('MYSQL_INTEGRATION_'))) { const e=environment(); delete e[key]; assert.throws(() => createPlan(root,e), /CONFIGURATION_INVALID/); }
  for (const url of ['jdbc:mysql://127.0.0.1:13003/prepared_interview', 'jdbc:mysql://remote:13317/prepared_interview', 'jdbc:mysql://127.0.0.1:13317/ai_interview', 'jdbc:mysql://127.0.0.1:13317/prepared_interview?allowMultiQueries=true', 'jdbc:mysql://127.0.0.1:13317/prepared_interview?sslMode=DISABLED']) assert.throws(() => createPlan(root,{...environment(),PREPARED_DB_JDBC_URL:url}), /CONFIGURATION_INVALID/);
  for (const e of [{REDIS_INTEGRATION_HOST:'remote'}, {PREPARED_REDIS_PORT:'6379'}, {PREPARED_REDIS_HOST:'remote'}, {MYSQL_INTEGRATION_SERVER_URL:'jdbc:mysql://remote:13317/'}, {JAVA_HOME:'relative'}, {PREPARED_VALIDATION_MAVEN:'/bin/mvn\nunsafe'}]) assert.throws(() => createPlan(root,{...environment(),...e}), /CONFIGURATION_INVALID/);
});
test('forged native capability is rejected instead of silently ignored by plan creation',t=>{
  assert.throws(()=>createPlan(fixture(t),environment(),{owned:true}),/DATABASE_OWNERSHIP_INVALID/);
});
test('old project, symlinked roots and unsafe runtime directories are refused', t => {
  const root=fixture(t); assert.throws(() => createPlan(path.dirname(root),environment()), /UNSAFE_PROJECT/);
  const link=path.join(path.dirname(root),'alias');fs.symlinkSync(root,link);assert.throws(() => createPlan(link,environment()), /UNSAFE_PROJECT/);
  fs.rmdirSync(path.join(root,'.runtime'));fs.symlinkSync(path.dirname(root),path.join(root,'.runtime'));assert.throws(() => acquireLock(root), /UNSAFE_RUNTIME/);
});
test('exclusive private operation lock never removes an existing owner lock', t => {
  const root=fixture(t), release=acquireLock(root), lock=path.join(root,'.runtime/validation.lock');
  assert.equal(fs.statSync(lock).mode&0o777,0o700);assert.throws(() => acquireLock(root), /VALIDATION_ALREADY_RUNNING/);
  assert.ok(fs.existsSync(lock));release();release();assert.ok(!fs.existsSync(lock));
});
test('history is moved recoverably, not merged or deleted; empty history is valid', t => {
  const root=fixture(t), run=fs.mkdtempSync(path.join(root,'.runtime/validation-'));
  for (const name of ['defense-assessment/target','defense-system/node_modules/.cache/vitest-coverage','defense-system/dist']) { fs.mkdirSync(path.join(root,name),{recursive:true});fs.writeFileSync(path.join(root,name,'old'),'prior'); }
  fs.writeFileSync(path.join(root,'.runtime/prepared-live.exec'),'historical');
  const moved=archivePriorEvidence(root,run);assert.equal(moved.length,4);
  for (const name of moved) assert.ok(!fs.existsSync(path.join(root,name)));
  assert.equal(fs.readFileSync(path.join(run,'prior/backend-target/old'),'utf8'),'prior');
  assert.equal(fs.readFileSync(path.join(run,'prior/frontend-coverage/old'),'utf8'),'prior');
  assert.equal(fs.readFileSync(path.join(run,'prior/browser-backend.exec'),'utf8'),'historical');
  assert.deepEqual(archivePriorEvidence(root,fs.mkdtempSync(path.join(root,'.runtime/validation-'))),[]);
});
test('archive rejects symlink evidence, external destinations and destination collisions', t => {
  const root=fixture(t),run=fs.mkdtempSync(path.join(root,'.runtime/validation-'));
  fs.symlinkSync(path.dirname(root),path.join(root,'defense-assessment/target'));assert.throws(() => archivePriorEvidence(root,run), /UNSAFE_EVIDENCE/);
  assert.ok(fs.lstatSync(path.join(root,'defense-assessment/target')).isSymbolicLink());
  assert.throws(() => archivePriorEvidence(root,path.dirname(root)), /UNSAFE_RUNTIME/);
});
test('archive preserves the configured frontend coverage and does not adopt unrelated coverage',t=>{
  const root=fixture(t),run=fs.mkdtempSync(path.join(root,'.runtime/validation-'));
  const configured='defense-system/node_modules/.cache/vitest-coverage';
  fs.mkdirSync(path.join(root,configured),{recursive:true});fs.writeFileSync(path.join(root,configured,'coverage-final.json'),'actual-current-proof');
  fs.mkdirSync(path.join(root,'defense-system/coverage'));fs.writeFileSync(path.join(root,'defense-system/coverage/unrelated'),'not-configured');
  fs.writeFileSync(path.join(root,'defense-system/node_modules/.cache/sibling'),'preserve');
  assert.deepEqual(archivePriorEvidence(root,run),[configured]);
  assert.ok(!fs.existsSync(path.join(root,configured)));
  assert.equal(fs.readFileSync(path.join(run,'prior/frontend-coverage/coverage-final.json'),'utf8'),'actual-current-proof');
  assert.equal(fs.readFileSync(path.join(root,'defense-system/coverage/unrelated'),'utf8'),'not-configured');
  assert.equal(fs.readFileSync(path.join(root,'defense-system/node_modules/.cache/sibling'),'utf8'),'preserve');
});
test('configured coverage symlinks fail before moving any existing evidence',t=>{
  for(const level of ['node_modules','.cache','vitest-coverage']){
    const root=fixture(t),run=fs.mkdtempSync(path.join(root,'.runtime/validation-'));
    fs.mkdirSync(path.join(root,'defense-assessment/target'));fs.writeFileSync(path.join(root,'defense-assessment/target/preserve'),'original');
    const parts=['node_modules','.cache','vitest-coverage'],at=parts.indexOf(level);
    fs.mkdirSync(path.join(root,'defense-system',...parts.slice(0,at)),{recursive:true});
    fs.symlinkSync(path.dirname(root),path.join(root,'defense-system',...parts.slice(0,at+1)));
    assert.throws(()=>archivePriorEvidence(root,run),/UNSAFE_EVIDENCE/);
    assert.equal(fs.readFileSync(path.join(root,'defense-assessment/target/preserve'),'utf8'),'original');
    assert.ok(!fs.existsSync(path.join(run,'prior')));
  }
});
function fakeSpawn(event='close',code=0) { return () => { const child=new EventEmitter();child.pid=undefined;process.nextTick(() => child.emit(event,event==='error'?new Error('private-secret'):code,event==='close'&&code===null?'SIGTERM':null));return child; }; }
test('command logs are private and shell-less; failures and private error details are not printed', async t => {
  const root=fixture(t), log=path.join(root,'.runtime/run.log'),command={name:'unit',bin:'/bin/tool',args:['value with spaces'],cwd:root,env:{}};
  let observed;await runCommand(command,log,{spawn:(bin,args,options)=>{observed={bin,args,options};return fakeSpawn()();}});
  assert.equal(observed.options.shell,false);assert.equal(observed.options.detached,true);assert.equal(fs.statSync(log).mode&0o777,0o600);
  await assert.rejects(runCommand(command,path.join(root,'.runtime/nonzero.log'),{spawn:fakeSpawn('close',2)}),/^Error: COMMAND_FAILED:unit$/);
  await assert.rejects(runCommand(command,path.join(root,'.runtime/signal.log'),{spawn:fakeSpawn('close',null)}),/COMMAND_FAILED/);
  await assert.rejects(runCommand(command,path.join(root,'.runtime/error.log'),{spawn:fakeSpawn('error')}),/^Error: COMMAND_FAILED:unit$/);
  await assert.rejects(runCommand(command,path.join(root,'.runtime/throw.log'),{spawn:()=>{throw new Error('private-secret');}}),/^Error: COMMAND_FAILED:unit$/);
});
test('command deadline stops only the owned process group and returns failure', async t => {
  const root=fixture(t);let kills=[];
  const child=new EventEmitter();child.pid=123456;
  await assert.rejects(runCommand({name:'hang',bin:'/bin/tool',args:[],cwd:root,env:{}},path.join(root,'.runtime/deadline.log'),{spawn:()=>child,timeoutMs:10,kill:(pid,signal)=>{kills.push([pid,signal]);process.nextTick(()=>child.emit('close',null,signal));}}),/COMMAND_TIMED_OUT:hang/);
  assert.deepEqual(kills,[[-123456,'SIGTERM']]);
});
test('abort stops only the owned command and pre-aborted runs never spawn', async t => {
  const root=fixture(t), controller=new AbortController();let kills=[];
  const child=new EventEmitter();child.pid=123456;
  const command={name:'abort',bin:'/bin/tool',args:[],cwd:root,env:{}};
  const pending=runCommand(command,path.join(root,'.runtime/abort.log'),{signal:controller.signal,spawn:()=>child,kill:(pid,signal)=>{kills.push([pid,signal]);process.nextTick(()=>child.emit('close',null,signal));}});
  controller.abort();await assert.rejects(pending,/COMMAND_INTERRUPTED:abort/);assert.deepEqual(kills,[[-123456,'SIGTERM']]);
  await assert.rejects(runCommand(command,path.join(root,'.runtime/not-created.log'),{signal:controller.signal,spawn:()=>{throw new Error('must not spawn');}}),/COMMAND_INTERRUPTED:abort/);
  assert.ok(!fs.existsSync(path.join(root,'.runtime/not-created.log')));
});
function pipelineFixture(t) {
  const root=fixture(t);fs.mkdirSync(path.join(root,'scripts'));fs.mkdirSync(path.join(root,'scripts/tests'));fs.writeFileSync(path.join(root,'scripts/tests/unit.test.mjs'),'// explicit synthetic unit seam');
  const calls=[],output=[];let cleaned=0;
  const deps={checkTools:()=>{},vacant:async port=>calls.push('vacant:'+port),reachable:async port=>calls.push('reachable:'+port),sourceFingerprint:()=> 'a'.repeat(64),runCommand:async command=>{calls.push(command.name);if(command.name==='backend-build'){fs.mkdirSync(path.join(root,'defense-assessment/target'));fs.writeFileSync(path.join(root,'defense-assessment/target/app.jar'),'fake unit artifact');}},startApp:command=>({child:{},closed:false,failed:false,command}),awaitApp:async(app,command)=>calls.push(command.name),closeApps:async()=>{cleaned++;},verifyEvidence:()=>({suites:1,tests:1,failures:0,errors:0,skipped:0}),
    startOwnedDatabase:async()=>{calls.push('database-open');return {capability:{},check:async()=>calls.push('database-check'),close:async()=>calls.push('database-close')};},
    createOwnedPlan:(root,env)=>createPlan(root,env)}; // explicitly synthetic, cannot establish native ownership
  return {root,calls,deps,output,io:{out:x=>output.push(x)},cleaned:()=>cleaned};
}
test('explicit synthetic orchestration unit covers all steps without claiming real integration', async t => {
  const f=pipelineFixture(t),result=await runValidation(f.root,environment(),f.io,f.deps);
  assert.deepEqual(f.calls.slice(0,8),['vacant:13014','vacant:13015','vacant:13016','vacant:13017','vacant:13018','vacant:13019','vacant:4173','reachable:16389']);
  assert.deepEqual(f.calls.slice(8),['harness-tests','dependency-install','dependency-audit','typecheck','unit-coverage','build','browser-regression','backend-build','database-open','database-check','backend-13014','frontend-13015','backend-13016','frontend-13017','backend-13018','frontend-13019','database-check','backend-full','database-check','database-close']);
  assert.equal(f.cleaned(),1);assert.equal(result.scope,'local-automated-regression');for(const key of ['physicalDevices','cloudIntegration','capacityAcceptance','finalQA'])assert.equal(result[key],false);
  assert.ok(!fs.existsSync(path.join(f.root,'.runtime/validation.lock')));const dir=fs.readdirSync(path.join(f.root,'.runtime')).find(x=>x.startsWith('validation-'));
  assert.equal(fs.statSync(path.join(f.root,'.runtime',dir,'result.json')).mode&0o777,0o600);
});
test('native owner check and shutdown failures never publish successful evidence',async t=>{
  for(const scenario of ['open','check','close']){
    const f=pipelineFixture(t);
    f.deps.startOwnedDatabase=async()=>{
      if(scenario==='open')throw new Error('OWNED_DATABASE_FAILED');
      return {capability:{},check:async()=>{if(scenario==='check')throw new Error('OWNED_DATABASE_FAILED');},close:async()=>{if(scenario==='close')throw new Error('OWNED_DATABASE_FAILED');}};
    };
    await assert.rejects(runValidation(f.root,environment(),f.io,f.deps),/OWNED_DATABASE_FAILED/);
    assert.equal(f.cleaned(),1);
    for(const dir of fs.readdirSync(path.join(f.root,'.runtime')))assert.ok(!fs.existsSync(path.join(f.root,'.runtime',dir,'result.json')));
  }
});
test('interrupt during owned-service cleanup never publishes the already-collected test result',async t=>{
  const f=pipelineFixture(t);f.deps.closeApps=async()=>process.emit('SIGTERM');
  await assert.rejects(runValidation(f.root,environment(),f.io,f.deps),/VALIDATION_INTERRUPTED/);
  for(const dir of fs.readdirSync(path.join(f.root,'.runtime')))assert.ok(!fs.existsSync(path.join(f.root,'.runtime',dir,'result.json')));
});
test('only whole-backend orchestration gets a bounded 90-minute budget without inherited overrides', async t => {
  const f=pipelineFixture(t), observed=[],execute=f.deps.runCommand;
  f.deps.runCommand=async(command,log,options)=>{
    observed.push({name:command.name,timeoutMs:options.timeoutMs,signal:options.signal});
    await execute(command,log,options);
  };
  await runValidation(f.root,{...environment(),PREPARED_VALIDATION_TIMEOUT_MS:'999999999',PREPARED_BACKEND_TIMEOUT_MS:'1'},f.io,f.deps);
  assert.equal(observed.filter(x=>x.name==='backend-full').length,1);
  assert.equal(observed.find(x=>x.name==='backend-full').timeoutMs,90*60*1000,'whole backend budget');
  for(const item of observed){
    assert.equal(item.timeoutMs,(item.name==='backend-full'?90:45)*60*1000,item.name);
    assert.ok(item.signal instanceof AbortSignal);
  }
});
test('whole-backend deadline never yields successful evidence and still cleans owned services',async t=>{
  const f=pipelineFixture(t),execute=f.deps.runCommand;let verified=false;
  f.deps.runCommand=async(command,log,options)=>{
    if(command.name==='backend-full')throw new Error('COMMAND_TIMED_OUT:backend-full');
    await execute(command,log,options);
  };
  f.deps.verifyEvidence=()=>{verified=true;throw new Error('must not verify partial output');};
  await assert.rejects(runValidation(f.root,environment(),f.io,f.deps),/^Error: COMMAND_TIMED_OUT:backend-full$/);
  assert.equal(verified,false);assert.equal(f.cleaned(),1);
  assert.ok(!fs.existsSync(path.join(f.root,'.runtime/validation.lock')));
  for(const dir of fs.readdirSync(path.join(f.root,'.runtime')))
    assert.ok(!fs.existsSync(path.join(f.root,'.runtime',dir,'result.json')));
});
for(const scenario of ['occupied','unreachable','command','source','artifact','startup','exit','evidence','interrupt'])test('synthetic orchestration refuses '+scenario+' and cleans only its owned services',async t=>{
  const f=pipelineFixture(t);let count=0;
  if(scenario==='occupied')f.deps.vacant=async()=>{throw new Error('TEST_PORT_OCCUPIED');};
  if(scenario==='unreachable')f.deps.reachable=async()=>{throw new Error('LOCAL_DEPENDENCY_UNAVAILABLE');};
  if(scenario==='command')f.deps.runCommand=async()=>{throw new Error('COMMAND_FAILED:unit');};
  if(scenario==='source')f.deps.sourceFingerprint=()=> (++count===1?'a':'b').repeat(64);
  if(scenario==='artifact'){const execute=f.deps.runCommand;f.deps.runCommand=async command=>{await execute(command);if(command.name==='backend-build')fs.writeFileSync(path.join(f.root,'defense-assessment/target/extra.jar'),'fake');};}
  if(scenario==='startup')f.deps.awaitApp=async()=>{throw new Error('APPLICATION_START_FAILED');};
  if(scenario==='exit')f.deps.startApp=command=>({child:{},closed:true,failed:false,command});
  if(scenario==='evidence')f.deps.verifyEvidence=()=>{throw new Error('TEST_SCOPE_INCOMPLETE');};
  if(scenario==='interrupt')f.deps.runCommand=async()=>process.emit('SIGTERM');
  await assert.rejects(runValidation(f.root,environment(),f.io,f.deps));assert.equal(f.cleaned(),1);assert.ok(!fs.existsSync(path.join(f.root,'.runtime/validation.lock')));
  for(const dir of fs.readdirSync(path.join(f.root,'.runtime')))assert.ok(!fs.existsSync(path.join(f.root,'.runtime',dir,'result.json')));
});
test('CLI help is read-only; malformed arguments never run tests or reveal config', async () => {
  const output=[];const io={out:x=>output.push(x),err:x=>output.push(x)};
  assert.equal(await runCli(['--help'],io),0);assert.match(output.join(''),/not final QA/);
  for(const argv of [[],['--root'],['--root','/old','--quick'],['--root','/old','--root','/old']]) assert.equal(await runCli(argv,io),1);
  assert.ok(!output.join('').includes('app-test-only'));
});
test('actual local TCP preflight distinguishes owned fixture listener from a vacant port',async t=>{
  const server=net.createServer();await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));t.after(()=>new Promise(resolve=>server.close(resolve)));
  const port=server.address().port;await reachable(port);await assert.rejects(vacant(port),/TEST_PORT_OCCUPIED/);await vacant(0);
});
test('actual owned harness fixture app starts, answers health and is stopped without port-based killing',async t=>{
  const root=fixture(t),run=fs.mkdtempSync(path.join(root,'.runtime/validation-'));
  const available=net.createServer();await new Promise(resolve=>available.listen(0,'127.0.0.1',resolve));const port=available.address().port;await new Promise(resolve=>available.close(resolve));
  const command={name:'fixture-app',bin:process.execPath,args:['-e',`require('node:http').createServer((q,s)=>{s.writeHead(200,{'content-type':'application/json'});s.end('{"status":"UP"}')}).listen(${port},'127.0.0.1')`],cwd:root,env:{PATH:process.env.PATH},kind:'frontend',port};
  const app=startApp(command,'unused',run);t.after(()=>closeApps([app]));await awaitApp(app,command);assert.equal(app.closed,false);await closeApps([app]);assert.equal(app.closed,true);await vacant(port);
  await assert.rejects(awaitApp({closed:true,failed:false},command),/APPLICATION_START_FAILED/);
});
test('unavailable real tooling fails CLI safely before lock, archive or commands',async t=>{
  const root=fixture(t),output=[],io={out:x=>output.push(x),err:x=>output.push(x)};
  assert.equal(await runCli(['--root',root],io,environment()),1);assert.match(output.join(''),/VALIDATION_FAILED/);
  assert.ok(!fs.existsSync(path.join(root,'.runtime/validation.lock')));assert.ok(!output.join('').includes('admin-test-only'));
});
test('controlled socket ToS fault cannot crash local health supervision across three native runs',()=>{
  const moduleUrl=new URL('../run-prepared-validation.mjs',import.meta.url).href;
  const code=`import http from 'node:http';import net from 'node:net';
    import {awaitApp} from ${JSON.stringify(moduleUrl)};
    net.Socket.prototype.setTypeOfService=function(){throw Object.assign(new Error('CONTROLLED_TOS_EINVAL'),{code:'EINVAL',syscall:'setTypeOfService'});};
    const server=http.createServer((q,s)=>{s.writeHead(200,{'content-type':'application/json'});s.end('{"status":"UP"}');});
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    try{await awaitApp({closed:false,failed:false},{kind:'backend',port:server.address().port});process.stdout.write('LOCAL_HEALTH_OK');}
    finally{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}`;
  for(let round=0;round<3;round++){
    const child=spawnSync(process.execPath,['--input-type=module','-e',code],{env:{PATH:process.env.PATH},encoding:'utf8',timeout:10000});
    assert.equal(child.error,undefined);assert.equal(child.status,0,'controlled native round '+round+' '+child.stderr);
    assert.equal(child.stdout,'LOCAL_HEALTH_OK');
  }
});
async function healthFixture(t,handler){
  const server=http.createServer(handler);await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(()=>{server.closeAllConnections();return new Promise(resolve=>server.close(resolve));});
  return {port:server.address().port,kind:'backend'};
}
for(const [status,body,expected]of [[200,'{"status":"UP"}',true],[201,'{"status":"UP"}',true],[200,'{"status":"DOWN"}',false],[200,'invalid-json',false],[200,'null',false],[503,'{"status":"UP"}',false],[302,'{"status":"UP"}',false]])test('bounded native health probe respects status and backend body '+status+'/'+body,async t=>{
  const command=await healthFixture(t,(q,s)=>{assert.equal(q.url,'/actuator/health');s.writeHead(status);s.end(body);});
  const {probeAppHealth}=await import('../run-prepared-validation.mjs');
  assert.equal(await probeAppHealth(command),expected);
});
test('frontend health success does not require or accumulate an application body',async t=>{
  const command=await healthFixture(t,(q,s)=>{assert.equal(q.url,'/');s.writeHead(200);s.flushHeaders();});command.kind='frontend';
  const {probeAppHealth}=await import('../run-prepared-validation.mjs');assert.equal(await probeAppHealth(command),true);
});
test('native health hard deadline includes the body and body size is bounded',async t=>{
  const {probeAppHealth}=await import('../run-prepared-validation.mjs');
  const slow=await healthFixture(t,(q,s)=>{s.writeHead(200);s.write('{');});
  assert.equal(await probeAppHealth(slow,{timeoutMs:20}),false);
  const huge=await healthFixture(t,(q,s)=>{s.writeHead(200);s.end(' '.repeat(65)+'{"status":"UP"}');});
  assert.equal(await probeAppHealth(huge,{maxBytes:64}),false);
});
test('aborted response and unavailable native health endpoint are bounded failures',async t=>{
  const {probeAppHealth}=await import('../run-prepared-validation.mjs');
  const aborted=await healthFixture(t,(q,s)=>{s.writeHead(200);s.write('{');s.destroy();});
  assert.equal(await probeAppHealth(aborted),false);
  const temporary=net.createServer();await new Promise(resolve=>temporary.listen(0,'127.0.0.1',resolve));const port=temporary.address().port;
  await new Promise(resolve=>temporary.close(resolve));assert.equal(await probeAppHealth({kind:'backend',port}),false);
});
test('health redirects are not followed to another local or external target',async t=>{
  let reached=0;const target=await healthFixture(t,(q,s)=>{reached++;s.end('{"status":"UP"}');});
  const source=await healthFixture(t,(q,s)=>{s.writeHead(302,{location:'http://127.0.0.1:'+target.port+'/actuator/health'});s.end();});
  const {probeAppHealth}=await import('../run-prepared-validation.mjs');assert.equal(await probeAppHealth(source),false);assert.equal(reached,0);
});
test('health input cannot expand time or memory budgets or redirect through an unknown kind',async()=>{
  const {probeAppHealth}=await import('../run-prepared-validation.mjs');
  for(const command of [{kind:'unknown',port:12345},{kind:'frontend',port:0},{kind:'backend',port:'12345'}])
    await assert.rejects(async()=>probeAppHealth(command),/HEALTH_PROBE_INVALID/);
  for(const options of [{timeoutMs:2001},{timeoutMs:0},{maxBytes:65537},{maxBytes:0}])
    await assert.rejects(async()=>probeAppHealth({kind:'backend',port:12345},options),/HEALTH_PROBE_INVALID/);
});
