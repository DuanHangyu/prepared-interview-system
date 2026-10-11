#!/usr/bin/env node
// Local automated regression only: never physical-device/cloud/final acceptance.
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import {request as httpRequest} from 'node:http';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { sourceFingerprint, verifyEvidence } from './verify-backend-evidence.mjs';
import {issuedDatabaseConfiguration,startOwnedDatabase} from './owned-prepared-database.mjs';

const FLAGS = ['PREPARED_LIVE_TESTS','PREPARED_BROWSER_FIXTURES','PREPARED_BROWSER_GENERATION_FIXTURES','PREPARED_FORMAL_BROWSER_FIXTURES','PREPARED_SUPPLEMENT_BROWSER_FIXTURES','PREPARED_STUDENT_SUPPLEMENT_BROWSER_FIXTURES','PREPARED_MYSQL_BOOTSTRAP_LIVE'];
const DISABLED = ['PREPARED_MODELS_ENABLED','PREPARED_PRIVATE_STORAGE_ENABLED','PREPARED_GENERATION_WORKER_ENABLED','PREPARED_TTS_WORKER_ENABLED','PREPARED_ASR_WORKER_ENABLED','PREPARED_SCORE_WORKER_ENABLED','PREPARED_AUDIO_ASSEMBLY_ENABLED','PREPARED_ANSWER_MEDIA_WORKER_ENABLED'];
function requireValue(ok, code) { if (!ok) throw new Error(code); }
function projectRoot(value) {
  requireValue(typeof value==='string' && path.isAbsolute(value), 'UNSAFE_PROJECT');
  const root=path.resolve(value);
  requireValue(path.basename(root)==='prepared-interview-system' && fs.realpathSync(root)===root, 'UNSAFE_PROJECT');
  return root;
}
function safePath(root, relative, missing=false) {
  let current=root;
  for (const part of relative.split('/')) {
    requireValue(part && part!=='.' && part!=='..', 'UNSAFE_EVIDENCE');current=path.join(current,part);
    const stat=fs.lstatSync(current,{throwIfNoEntry:false});
    if (!stat) { requireValue(missing,'UNSAFE_EVIDENCE');return undefined; }
    requireValue(!stat.isSymbolicLink(),'UNSAFE_EVIDENCE');
  }
  return current;
}
function runtime(root) {
  const dir=path.join(root,'.runtime'), stat=fs.lstatSync(dir,{throwIfNoEntry:false});
  if (!stat) fs.mkdirSync(dir,{mode:0o700});
  else requireValue(stat.isDirectory()&&!stat.isSymbolicLink()&&(stat.mode&0o077)===0,'UNSAFE_RUNTIME');
  return dir;
}
export function createPlan(project, env, databaseCapability) {
  const root=projectRoot(project);
  const owned=databaseCapability===undefined?undefined:issuedDatabaseConfiguration(databaseCapability,root);
  if(owned)env={...env,...owned};
  const required=['HOME','PATH','JAVA_HOME','PREPARED_VALIDATION_MAVEN','PREPARED_MYSQL_BASEDIR','PREPARED_DB_JDBC_URL','PREPARED_DB_USERNAME','PREPARED_DB_PASSWORD','PREPARED_TEST_LOGIN_PASSWORD','PREPARED_REDIS_HOST','PREPARED_REDIS_PORT'];
  for(const key of required) requireValue(typeof env[key]==='string'&&env[key].length>0&&!/[\r\n\0]/.test(env[key]),'CONFIGURATION_INVALID');
  for(const key of ['HOME','JAVA_HOME','PREPARED_VALIDATION_MAVEN','PREPARED_MYSQL_BASEDIR']) requireValue(path.isAbsolute(env[key]),'CONFIGURATION_INVALID');
  // Existing fixture contracts are deliberately local/fixed. Do not redirect a
  // destructive test database or Redis fixture to an inherited old environment.
  for(const [key,base] of [['PREPARED_DB_JDBC_URL','jdbc:mysql://127.0.0.1:13317/prepared_interview'],['MYSQL_INTEGRATION_SERVER_URL','jdbc:mysql://127.0.0.1:13317/']]) {
    if(owned)continue; // issued endpoint is checked by native owner and descriptor, never plain caller flags
    if(key==='MYSQL_INTEGRATION_SERVER_URL'&&env[key]===undefined)continue;
    requireValue(typeof env[key]==='string','CONFIGURATION_INVALID');
    const [address,query,...rest]=env[key].split('?');
    requireValue(address===base&&rest.length===0&&query,'CONFIGURATION_INVALID');
    const params=new URLSearchParams(query);
    requireValue(params.get('sslMode')==='REQUIRED'&&[...params.keys()].every(k=>['sslMode','useUnicode','characterEncoding','serverTimezone','connectionTimeZone'].includes(k))&&[...params.keys()].length===new Set(params.keys()).size,'CONFIGURATION_INVALID');
  }
  requireValue(env.PREPARED_REDIS_HOST==='127.0.0.1'&&env.PREPARED_REDIS_PORT==='16389'&&(!env.REDIS_INTEGRATION_HOST||env.REDIS_INTEGRATION_HOST==='127.0.0.1')&&(!env.REDIS_INTEGRATION_PORT||env.REDIS_INTEGRATION_PORT==='16389'),'CONFIGURATION_INVALID');
  const base={HOME:env.HOME,PATH:env.PATH,JAVA_HOME:env.JAVA_HOME,CI:'true'};
  const db=Object.fromEntries(['PREPARED_DB_JDBC_URL','PREPARED_DB_USERNAME','PREPARED_DB_PASSWORD','PREPARED_REDIS_HOST','PREPARED_REDIS_PORT'].map(k=>[k,env[k]]));
  if(owned)db.PREPARED_VALIDATION_DATABASE_DESCRIPTOR=owned.PREPARED_VALIDATION_DATABASE_DESCRIPTOR;
  if(owned)db.SA_TOKEN_TOKEN_NAME=owned.SA_TOKEN_TOKEN_NAME;
  if(env.PREPARED_REDIS_PASSWORD)db.PREPARED_REDIS_PASSWORD=env.PREPARED_REDIS_PASSWORD;
  // All destructive legacy integration suites create an owned native fixture.
  // Existing administrator identities are never forwarded or given wider grants.
  const tests={...base,...db,PREPARED_MYSQL_BASEDIR:env.PREPARED_MYSQL_BASEDIR,PREPARED_TEST_LOGIN_PASSWORD:env.PREPARED_TEST_LOGIN_PASSWORD,REDIS_INTEGRATION_HOST:'127.0.0.1',REDIS_INTEGRATION_PORT:'16389',PREPARED_OWNED_MYSQL_MIGRATION_TESTS:'true',PREPARED_VALIDATION_RUN_ID:randomUUID(),...Object.fromEntries(FLAGS.map(k=>[k,'true']))};
  if(owned)for(const key of ['PREPARED_VALIDATION_BROWSER_DESCRIPTOR','PREPARED_VALIDATION_BROWSER_RUN_ID'])tests[key]=owned[key];
  const backend=path.join(root,'defense-assessment'),frontend=path.join(root,'defense-system');
  const command=(name,bin,args,cwd,environment)=>({name,bin,args,cwd,env:{...environment}});
  // Bound both Maven and its forked test JVM without replacing JaCoCo/Mockito
  // argLine or forwarding caller VM injection. These are local-test heap caps,
  // not limits on native memory or a production capacity configuration.
  const maven=(name,args,environment)=>command(name,env.PREPARED_VALIDATION_MAVEN,['--batch-mode','--no-transfer-progress',...args],backend,
    {...environment,JAVA_TOOL_OPTIONS:'-Xms64m -Xmx1024m'});
  const npm=(name,args)=>command(name,'npm',args,frontend,base);
  const apps=[];
  for(const port of [13014,13016,13018]) {
    const appEnv={...base,...db,JAVA_TOOL_OPTIONS:'-Xms64m -Xmx512m',SPRING_PROFILES_ACTIVE:'prepared-local',PREPARED_BACKEND_PORT:String(port),PREPARED_INTERVIEW_WORKFLOW_ENABLED:port===13014?'false':'true',PREPARED_ALLOWED_ORIGINS:'http://127.0.0.1:13015,http://127.0.0.1:13017,http://127.0.0.1:13019',...Object.fromEntries(DISABLED.map(k=>[k,'false']))};
    apps.push({...command('backend-'+port,path.join(env.JAVA_HOME,'bin/java'),[],backend,appEnv),kind:'backend',port});
    apps.push({...command('frontend-'+(port+1),process.execPath,[path.join(frontend,'node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port',String(port+1),'--strictPort'],frontend,{...base,PREPARED_FRONTEND_PORT:String(port+1),VUE_APP_PROXY_TARGET:'http://127.0.0.1:'+port+'/'}),kind:'frontend',port:port+1});
  }
  return {root,base,build:maven('backend-build',['package','-DskipTests=true'],base),install:npm('dependency-install',['ci']),backendVerify:maven('backend-full',['verify','-Pprepared-coverage','-Dtest=*Test,*Tests,*IT','-DskipTests=false','-Dmaven.test.skip=false','-Dmaven.test.failure.ignore=false'],tests),frontend:[npm('dependency-audit',['audit','--audit-level=low']),npm('typecheck',['run','typecheck']),npm('unit-coverage',['run','test:coverage']),npm('build',['run','build']),npm('browser-regression',['run','test:e2e','--','--workers=1','--retries=0'])],apps};
}
export function acquireLock(root) {
  const lock=path.join(runtime(root),'validation.lock');
  try { fs.mkdirSync(lock,{mode:0o700}); } catch { throw new Error('VALIDATION_ALREADY_RUNNING'); }
  const identity=fs.statSync(lock);let released=false;
  return ()=>{if(released)return;const current=fs.lstatSync(lock,{throwIfNoEntry:false});requireValue(current&&!current.isSymbolicLink()&&current.ino===identity.ino&&current.dev===identity.dev,'LOCK_OWNERSHIP_CHANGED');fs.rmdirSync(lock);released=true;};
}
export function archivePriorEvidence(root, run) {
  const dir=runtime(root);requireValue(path.dirname(run)===dir&&fs.realpathSync(run)===run&&fs.lstatSync(run).isDirectory(),'UNSAFE_RUNTIME');
  // Match vitest.config.mts, not an unrelated/default coverage directory. Validate
  // every ancestor before moving anything; never traverse node_modules symlinks.
  const files=[['defense-assessment/target','backend-target'],['defense-system/node_modules/.cache/vitest-coverage','frontend-coverage'],['defense-system/dist','frontend-dist'],['.runtime/prepared-live.exec','browser-backend.exec']];
  const found=files.filter(([relative])=>safePath(root,relative,true));
  const prior=path.join(run,'prior');fs.mkdirSync(prior,{mode:0o700});
  for(const [relative,name]of found)fs.renameSync(path.join(root,relative),path.join(prior,name));
  return found.map(([relative])=>relative);
}
function stopOwned(child, kill=process.kill) {
  if(child.pid)try{kill(-child.pid,'SIGTERM');}catch{ /* Already exited; never look up/kill by port. */ }
}
export function runCommand(command, log, options={}) {
  const spawnCommand=options.spawn||spawn,kill=options.kill||process.kill;
  return new Promise((resolve,reject)=>{
    let fd,child,timer,escalation,timedOut=false,interrupted=false,finished=false;
    function finish(error){if(finished)return;finished=true;clearTimeout(timer);clearTimeout(escalation);options.signal?.removeEventListener('abort',interrupt);if(fd!==undefined)fs.closeSync(fd);error?reject(error):resolve();}
    function terminate(){stopOwned(child,kill);escalation=setTimeout(()=>{if(child.pid)try{kill(-child.pid,'SIGKILL');}catch{}finish(new Error((interrupted?'COMMAND_INTERRUPTED:':'COMMAND_TIMED_OUT:')+command.name));},5000);}
    function interrupt(){interrupted=true;terminate();}
    try {
      requireValue(!options.signal?.aborted,'COMMAND_INTERRUPTED:'+command.name);
      fd=fs.openSync(log,fs.constants.O_WRONLY|fs.constants.O_CREAT|fs.constants.O_EXCL|fs.constants.O_NOFOLLOW,0o600);
      child=spawnCommand(command.bin,command.args,{cwd:command.cwd,env:command.env,shell:false,detached:true,stdio:['ignore',fd,fd]});
      child.once('error',()=>finish(new Error('COMMAND_FAILED:'+command.name)));
      child.once('close',code=>finish(interrupted?new Error('COMMAND_INTERRUPTED:'+command.name):timedOut?new Error('COMMAND_TIMED_OUT:'+command.name):code===0?undefined:new Error('COMMAND_FAILED:'+command.name)));
      options.signal?.addEventListener('abort',interrupt,{once:true});
      timer=setTimeout(()=>{timedOut=true;terminate();},options.timeoutMs??45*60*1000);
    } catch {finish(new Error((options.signal?.aborted?'COMMAND_INTERRUPTED:':'COMMAND_FAILED:')+command.name));}
  });
}
export async function vacant(port) {
  await new Promise((resolve,reject)=>{const server=net.createServer();server.once('error',()=>reject(new Error('TEST_PORT_OCCUPIED')));server.listen(port,'127.0.0.1',()=>server.close(resolve));});
}
export async function reachable(port) {
  await new Promise((resolve,reject)=>{const socket=net.connect({host:'127.0.0.1',port});socket.setTimeout(2000);socket.once('connect',()=>{socket.destroy();resolve();});const fail=()=>{socket.destroy();reject(new Error('LOCAL_DEPENDENCY_UNAVAILABLE'));};socket.once('error',fail);socket.once('timeout',fail);});
}
export function startApp(command, jar, run) {
  const fd=fs.openSync(path.join(run,command.name+'.log'),'wx',0o600);
  let child;
  try {child=spawn(command.bin,command.kind==='backend'?['-jar',jar]:command.args,{cwd:command.cwd,env:command.env,shell:false,detached:true,stdio:['ignore',fd,fd]});}finally{fs.closeSync(fd);}
  const owned={child,failed:false,closed:false};
  owned.exit=new Promise(resolve=>{child.once('error',()=>{owned.failed=true;owned.closed=true;resolve();});child.once('close',()=>{owned.closed=true;resolve();});});return owned;
}
export function probeAppHealth(command,options={}) {
  const timeoutMs=options.timeoutMs??2000,maxBytes=options.maxBytes??65536;
  requireValue(['backend','frontend'].includes(command.kind)&&Number.isInteger(command.port)&&command.port>=1&&command.port<=65535
    &&Number.isInteger(timeoutMs)&&timeoutMs>0&&timeoutMs<=2000&&Number.isInteger(maxBytes)&&maxBytes>0&&maxBytes<=65536,'HEALTH_PROBE_INVALID');
  // The bundled fetch/undici ToS path can throw outside its promise on macOS.
  // Use bounded, non-reusing loopback HTTP without patching global network APIs
  // or swallowing uncaught process errors. Redirects are never followed.
  return new Promise(resolve=>{
    let request,response,finished=false;
    const timer=setTimeout(()=>finish(false),timeoutMs);
    function finish(ok){if(finished)return;finished=true;clearTimeout(timer);response?.destroy();request?.destroy();resolve(ok);}
    try{
      request=httpRequest({hostname:'127.0.0.1',port:command.port,path:command.kind==='backend'?'/actuator/health':'/',method:'GET',agent:false},incoming=>{
        response=incoming;
        response.once('error',()=>finish(false));response.once('aborted',()=>finish(false));
        if(!(response.statusCode>=200&&response.statusCode<300))return finish(false);
        if(command.kind==='frontend')return finish(true);
        let size=0;const chunks=[];
        response.on('data',chunk=>{size+=chunk.length;if(size>maxBytes)return finish(false);chunks.push(chunk);});
        response.once('end',()=>{try{finish(JSON.parse(Buffer.concat(chunks).toString('utf8'))?.status==='UP');}catch{finish(false);}});
      });
      request.once('error',()=>finish(false));request.end();
    }catch{finish(false);}
  });
}
export async function awaitApp(app, command) {
  const limit=Date.now()+90000;
  while(Date.now()<limit) {
    requireValue(!app.closed&&!app.failed,'APPLICATION_START_FAILED');
    if(await probeAppHealth(command))return;
    await new Promise(resolve=>setTimeout(resolve,250));
  }
  throw new Error('APPLICATION_START_TIMED_OUT');
}
export async function closeApps(apps) {
  for(const app of apps)stopOwned(app.child);
  await Promise.all(apps.map(async app=>{
    let timer;await Promise.race([app.exit,new Promise(resolve=>{timer=setTimeout(()=>{if(!app.closed&&app.child.pid)try{process.kill(-app.child.pid,'SIGKILL');}catch{}resolve();},5000);})]);clearTimeout(timer);
  }));
}
function checkTools(plan) {
  for(const file of [plan.build.bin,plan.apps[0].bin,path.join(plan.backendVerify.env.PREPARED_MYSQL_BASEDIR,'bin/mysqld'),'/Users/duanhangyu/.homebrew/bin/ffmpeg','/Users/duanhangyu/.homebrew/bin/ffprobe'])fs.accessSync(file,fs.constants.X_OK);
  requireValue(fs.lstatSync(path.join(plan.root,'defense-system/package-lock.json')).isFile(),'TOOLING_UNAVAILABLE');
}
export async function runValidation(project,environment,io={out:x=>process.stdout.write(x)},testAdapters={}) {
  // Dependency seams are only for explicit unit tests; the CLI has no option
  // to inject success, narrow test scope, suppress execution or bypass evidence.
  const deps={checkTools,vacant,reachable,sourceFingerprint,runCommand,startApp,awaitApp,closeApps,verifyEvidence,startOwnedDatabase,createOwnedPlan:createPlan,...testAdapters};
  let plan=createPlan(project,environment);deps.checkTools(plan);const release=acquireLock(plan.root),apps=[];
  const oldMask=process.umask(0o077);let run,database,result,interrupted=false;
  const controller=new AbortController();
  const interrupt=()=>{interrupted=true;controller.abort();for(const app of apps)stopOwned(app.child);};
  process.on('SIGINT',interrupt);process.on('SIGTERM',interrupt);
  try {
    for(const port of [...plan.apps.map(x=>x.port),4173])await deps.vacant(port);
    await deps.reachable(16389);
    run=fs.mkdtempSync(path.join(runtime(plan.root),'validation-'));
    const sourceSha256=deps.sourceFingerprint(plan.root),since=Date.now();
    archivePriorEvidence(plan.root,run);
    const execute=async command=>{
      requireValue(!interrupted,'VALIDATION_INTERRUPTED');
      // Whole-suite wall time includes repeated native/browser integration cases.
      // This bounded orchestration allowance does NOT change any test assertion,
      // application startup, HTTP deadline, student timer or performance target.
      const timeoutMs=(command===plan.backendVerify?90:45)*60*1000;
      io.out('Running '+command.name+'; diagnostics remain private.\n');
      await deps.runCommand(command,path.join(run,command.name+'.log'),{signal:controller.signal,timeoutMs});
      requireValue(!interrupted,'VALIDATION_INTERRUPTED');
      requireValue(deps.sourceFingerprint(plan.root)===sourceSha256,'SOURCE_CHANGED');
    };
    // Test/build subprocesses never receive cloud keys. Build and browser
    // processes do not receive application or test-administrator credentials.
    const scriptTests=fs.readdirSync(path.join(plan.root,'scripts/tests')).filter(x=>x.endsWith('.test.mjs')).sort().map(x=>path.join(plan.root,'scripts/tests',x));
    await execute({name:'harness-tests',bin:process.execPath,args:['--test',...scriptTests],cwd:plan.root,env:plan.base});
    await execute(plan.install);
    for(const command of plan.frontend)await execute(command);
    await execute(plan.build);
    const jars=fs.readdirSync(path.join(plan.root,'defense-assessment/target')).filter(x=>x.endsWith('.jar'));
    requireValue(jars.length===1,'APPLICATION_ARTIFACT_INVALID');const jar=path.join(run,'application.jar');
    fs.copyFileSync(safePath(plan.root,'defense-assessment/target/'+jars[0]),jar,fs.constants.COPYFILE_EXCL);
    database=await deps.startOwnedDatabase(plan.root,run,environment,{signal:controller.signal});
    await database.check();
    plan=deps.createOwnedPlan(plan.root,environment,database.capability);
    for(const command of plan.apps){requireValue(!interrupted,'VALIDATION_INTERRUPTED');const app=deps.startApp(command,jar,run);apps.push(app);await deps.awaitApp(app,command);}
    await database.check();await execute(plan.backendVerify);await database.check();
    requireValue(apps.every(x=>!x.closed&&!x.failed),'APPLICATION_EXITED');
    const evidence=deps.verifyEvidence({root:plan.root,since,sourceSha256,mavenExitCode:0});
    result={...evidence,scope:'local-automated-regression',physicalDevices:false,cloudIntegration:false,capacityAcceptance:false,finalQA:false};
  } finally {
    try{try{await deps.closeApps(apps);}finally{await database?.close();}}
    finally{process.removeListener('SIGINT',interrupt);process.removeListener('SIGTERM',interrupt);process.umask(oldMask);release();}
  }
  // A stopped/failed owner is not successful evidence. Publish only after all
  // owned apps and the native database have completed their cleanup protocol.
  requireValue(!interrupted,'VALIDATION_INTERRUPTED');
  fs.writeFileSync(path.join(run,'result.json'),JSON.stringify(result)+'\n',{flag:'wx',mode:0o600});
  io.out(JSON.stringify(result)+'\n');return result;
}
export async function runCli(argv,io={out:x=>process.stdout.write(x),err:x=>process.stderr.write(x)},environment=process.env) {
  try {
    if(argv.length===1&&argv[0]==='--help'){io.out('Run full local automated prepared regression: --root ABSOLUTE_NEW_PROJECT. Creates a fresh owned native MySQL after build; requires local Redis 16389 and free test ports 13014–13019/4173. All live fixture conditions are forced on, old output is archived, owned services are stopped afterwards and private database files retained. No cloud or existing administrator keys are forwarded. This is not final QA or device/cloud/capacity acceptance.\n');return 0;}
    requireValue(argv.length===2&&argv[0]==='--root','INVALID_ARGUMENTS');await runValidation(argv[1],environment,io);return 0;
  }catch(error){const code=error instanceof Error&&/^(?:[A-Z_]+)(?::[a-z0-9-]+)?$/.test(error.message)?error.message:'VALIDATION_FAILED';io.err('Prepared validation failed: '+code+'. Inspect owner-only local diagnostics; no acceptance granted.\n');return 1;}
}
if(process.argv[1]&&import.meta.url===pathToFileURL(path.resolve(process.argv[1])).href)process.exitCode=await runCli(process.argv.slice(2));
