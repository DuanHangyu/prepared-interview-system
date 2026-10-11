// Disposable native database ownership for local validation only.
import fs from 'node:fs';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import {checkPreparedBrowserProof} from './assert-owned-browser-fixture.mjs';

const issued=new WeakMap();
const requireValue=(ok)=>{if(!ok)throw new Error('DATABASE_OWNERSHIP_INVALID');};
const digest=bytes=>createHash('sha256').update(bytes).digest('hex');
function privatePath(file,directory=false) {
  const stat=fs.lstatSync(file);
  requireValue(!stat.isSymbolicLink()&&(directory?stat.isDirectory():stat.isFile())&&(stat.mode&0o777)===(directory?0o700:0o600)
    &&stat.uid===process.getuid()&&fs.realpathSync(file)===file);
  return stat;
}
function controlDirectory(project,run) {
  requireValue(path.isAbsolute(project)&&fs.realpathSync(project)===project&&path.basename(project)==='prepared-interview-system');
  requireValue(path.dirname(run)===path.join(project,'.runtime')&&/^validation-[A-Za-z0-9]{6}$/.test(path.basename(run)));
  privatePath(path.join(project,'.runtime'),true);privatePath(run,true);
}
export function readOwnedDescriptor(project,run,ownerPid) {
  controlDirectory(project,run);
  const file=path.join(run,'database.json'),stat=privatePath(file);
  requireValue(stat.size<=8192);
  const bytes=fs.readFileSync(file),value=JSON.parse(bytes.toString('utf8'));
  requireValue(value&&Object.getPrototypeOf(value)===Object.prototype
    &&Object.keys(value).sort().join(',')===['schemaVersion','url','username','password','data','pid','ownerPid','nonce'].sort().join(','));
  requireValue(value.schemaVersion===1&&value.ownerPid===ownerPid&&Number.isSafeInteger(ownerPid)&&ownerPid>0
    &&Number.isSafeInteger(value.pid)&&value.pid>0&&typeof value.nonce==='string'&&/^[a-f0-9-]{36}$/.test(value.nonce)
    &&value.username==='prepared_migrator'&&typeof value.password==='string'&&/^[A-Za-z0-9_-]{48}$/.test(value.password));
  requireValue(typeof value.url==='string'&&/^jdbc:mysql:\/\/127\.0\.0\.1:[0-9]+\/prepared_interview\?sslMode=REQUIRED&serverTimezone=Asia\/Shanghai$/.test(value.url));
  const address=new URL(value.url.slice(5));requireValue(Number(address.port)>=1024&&Number(address.port)<=65535&&address.port!=='13317');
  requireValue(typeof value.data==='string'&&path.isAbsolute(value.data)
    &&/^om-[a-f0-9]{8}$/.test(path.basename(path.dirname(path.dirname(value.data))))
    &&path.dirname(path.dirname(path.dirname(value.data)))===path.join(project,'.runtime')
    &&path.basename(value.data)==='mysql'&&path.basename(path.dirname(value.data))==='.runtime'&&fs.realpathSync(value.data)===value.data);
  requireValue(fs.readFileSync(path.join(value.data,'server.pid'),'utf8').trim()===String(value.pid));
  const browser=path.join(run,'browser-database.json'),browserEnv={LIVE_E2E:'1',PREPARED_VALIDATION_BROWSER_DESCRIPTOR:browser,PREPARED_VALIDATION_BROWSER_RUN_ID:value.nonce};
  try{checkPreparedBrowserProof(project,browserEnv,1);}catch(invalid){throw new Error('DATABASE_OWNERSHIP_INVALID');}
  const browserBytes=fs.readFileSync(browser),proof=JSON.parse(browserBytes.toString('utf8'));
  requireValue(proof.nativePid===value.pid&&proof.ownerPid===value.ownerPid&&proof.nativePort===Number(address.port));
  return {file,sha256:digest(Buffer.concat([bytes,browserBytes])),configuration:{PREPARED_DB_JDBC_URL:value.url,PREPARED_DB_USERNAME:value.username,PREPARED_DB_PASSWORD:value.password,
    PREPARED_VALIDATION_DATABASE_DESCRIPTOR:file,PREPARED_VALIDATION_BROWSER_DESCRIPTOR:browser,PREPARED_VALIDATION_BROWSER_RUN_ID:value.nonce,SA_TOKEN_TOKEN_NAME:'prepared-interview-validation-'+value.nonce}};
}
export function issuedDatabaseConfiguration(capability,project) {
  const state=capability&&issued.get(capability);
  requireValue(state&&state.active&&state.project===project&&state.child.exitCode===null&&!state.closed);
  const descriptor=readOwnedDescriptor(project,state.run,state.child.pid);
  requireValue(descriptor.sha256===state.sha256);
  return {...descriptor.configuration};
}
export function nativeDatabaseCommand(project,run,env) {
  controlDirectory(project,run);
  const connector=path.join(env.HOME,'.m2/repository/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar');
  for(const file of [path.join(env.JAVA_HOME,'bin/java'),connector,path.join(env.PREPARED_MYSQL_BASEDIR,'bin/mysqld')])fs.accessSync(file);
  const backend=path.join(project,'defense-assessment');
  return {bin:path.join(env.JAVA_HOME,'bin/java'),args:['-cp',[path.join(backend,'target/test-classes'),path.join(backend,'target/classes'),connector].join(path.delimiter),
    'system.assessment.defense.infrastructure.migration.OwnedPreparedValidationMysql',run],cwd:backend,
    env:{HOME:env.HOME,PATH:env.PATH,JAVA_HOME:env.JAVA_HOME,PREPARED_MYSQL_BASEDIR:env.PREPARED_MYSQL_BASEDIR,
      JAVA_TOOL_OPTIONS:'-Xms32m -Xmx128m'}};
}
export async function startOwnedDatabase(project,run,env,options={}) {
  // Explicit process seams serve unit tests only; the CLI cannot inject them.
  const command=nativeDatabaseCommand(project,run,env),log=path.join(run,'database-owner.log');
  requireValue(!options.signal?.aborted&&!fs.existsSync(path.join(run,'database.json')));
  const fd=fs.openSync(log,fs.constants.O_WRONLY|fs.constants.O_CREAT|fs.constants.O_EXCL|fs.constants.O_NOFOLLOW,0o600);
  let child;
  try{child=(options.spawn||spawn)(command.bin,command.args,{cwd:command.cwd,env:command.env,shell:false,detached:true,stdio:['pipe','pipe',fd]});}
  finally{fs.closeSync(fd);}
  const state={project,run,child,closed:false,stopped:false,active:false,sha256:undefined};
  let pending,buffer='';
  function fail(){if(pending){const {reject,timer}=pending;pending=undefined;clearTimeout(timer);reject(new Error('OWNED_DATABASE_FAILED'));}}
  const exit=new Promise(resolve=>{child.once('error',()=>{state.closed=true;state.active=false;fail();resolve();});child.once('close',()=>{state.closed=true;state.active=false;fail();resolve();});});
  const interrupt=()=>{state.active=false;fail();if(!state.closed)child.stdin.end();};
  options.signal?.addEventListener('abort',interrupt,{once:true});
  exit.then(()=>options.signal?.removeEventListener('abort',interrupt));
  child.stdin.on('error',fail);child.stdout.on('error',fail);
  child.stdout.on('data',chunk=>{
    buffer+=chunk.toString('utf8');
    if(buffer.length>128){fail();return;}
    while(buffer.includes('\n')){
      const end=buffer.indexOf('\n'),line=buffer.slice(0,end);buffer=buffer.slice(end+1);
      if(!pending||pending.expected!==line){fail();continue;}
      const {resolve,timer}=pending;pending=undefined;clearTimeout(timer);resolve();
    }
  });
  function response(expected,timeoutMs) {
    requireValue(!state.closed&&!pending&&!options.signal?.aborted);
    return new Promise((resolve,reject)=>{pending={expected,resolve,reject,timer:setTimeout(()=>fail(),timeoutMs)};});
  }
  const kill=options.kill||process.kill;
  async function emergencyStop(){
    state.active=false;
    // End the protocol first, allowing try-with-resources normal shutdown.
    if(!state.closed)child.stdin.end();
    let timer;
    await Promise.race([exit,new Promise(resolve=>{timer=setTimeout(()=>{if(!state.closed)try{kill(-child.pid,'SIGTERM');}catch{}resolve();},45000);})]);clearTimeout(timer);
    if(!state.closed){await Promise.race([exit,new Promise(resolve=>{timer=setTimeout(resolve,5000);})]);clearTimeout(timer);}
    if(!state.closed){try{kill(-child.pid,'SIGKILL');}catch{}await Promise.race([exit,new Promise(resolve=>{timer=setTimeout(resolve,5000);})]);clearTimeout(timer);}
  }
  const capability=Object.freeze({});
  try {
    const ready=response('READY',240000);await ready;
    requireValue(!options.signal?.aborted);
    const descriptor=readOwnedDescriptor(project,run,child.pid);state.sha256=descriptor.sha256;state.active=true;issued.set(capability,state);
    return {capability,
      async check(){issuedDatabaseConfiguration(capability,project);const checked=response('CHECKED',10000);child.stdin.write('CHECK\n');await checked;issuedDatabaseConfiguration(capability,project);},
      async close(){
        if(!state.active){await emergencyStop();if(!state.stopped&&!options.signal?.aborted)throw new Error('OWNED_DATABASE_FAILED');return;}
        state.active=false;
        try{const stopped=response('STOPPED',50000);child.stdin.write('STOP\n');await stopped;await emergencyStop();requireValue(state.closed&&child.exitCode===0);state.stopped=true;}
        finally{await emergencyStop();}
      },
    };
  }catch(failure){await emergencyStop();throw new Error('OWNED_DATABASE_FAILED');}
}
