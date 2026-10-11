// Boundary unit tests; synthetic files are not native database acceptance.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {EventEmitter} from 'node:events';
import {PassThrough} from 'node:stream';
import {issuedDatabaseConfiguration,readOwnedDescriptor,nativeDatabaseCommand,startOwnedDatabase} from '../owned-prepared-database.mjs';
import {createPlan} from '../run-prepared-validation.mjs';

test('caller configuration or a forged owner flag is never an issued database capability',()=>{
  for(const value of [undefined,null,{}, {owned:true}, {url:'jdbc:mysql://127.0.0.1:24444/prepared_interview?sslMode=REQUIRED',username:'root',password:'unit-only'}])
    assert.throws(()=>issuedDatabaseConfiguration(value,'/Users/duanhangyu/Code/prepared-interview-system'),/DATABASE_OWNERSHIP_INVALID/);
});

function fixture(t) {
  const parent=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'prepared-owned-unit-'))),project=path.join(parent,'prepared-interview-system');
  fs.mkdirSync(project,{mode:0o700});fs.mkdirSync(path.join(project,'.runtime'),{mode:0o700});
  const run=fs.mkdtempSync(path.join(project,'.runtime/validation-'));
  const data=path.join(project,'.runtime/om-abcdef12/.runtime/mysql');fs.mkdirSync(data,{mode:0o700,recursive:true});
  fs.writeFileSync(path.join(data,'server.pid'),String(process.pid)+'\n',{mode:0o600});
  const home=path.join(parent,'home'),java=path.join(parent,'java'),mysql=path.join(parent,'mysql');
  for(const file of [path.join(home,'.m2/repository/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar'),path.join(java,'bin/java'),path.join(mysql,'bin/mysqld')]){fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,'explicit synthetic tooling fixture');}
  const env={HOME:home,PATH:'/usr/bin:/bin',JAVA_HOME:java,PREPARED_MYSQL_BASEDIR:mysql,PREPARED_VALIDATION_MAVEN:'/synthetic-maven',
    PREPARED_DB_JDBC_URL:'jdbc:mysql://127.0.0.1:13317/prepared_interview?sslMode=REQUIRED',PREPARED_DB_USERNAME:'prepared_test',PREPARED_DB_PASSWORD:'inherited-not-forwarded',
    PREPARED_TEST_LOGIN_PASSWORD:'unit-login',PREPARED_REDIS_HOST:'127.0.0.1',PREPARED_REDIS_PORT:'16389'};
  const descriptor={schemaVersion:1,url:'jdbc:mysql://127.0.0.1:24444/prepared_interview?sslMode=REQUIRED&serverTimezone=Asia/Shanghai',username:'prepared_migrator',password:'a'.repeat(48),data,pid:process.pid,ownerPid:process.pid,nonce:'11111111-1111-4111-8111-111111111111'};
  const file=path.join(run,'database.json');
  const write=()=>{fs.writeFileSync(file,JSON.stringify(descriptor)+'\n',{mode:0o600});fs.writeFileSync(path.join(run,'browser-database.json'),JSON.stringify({schemaVersion:1,nativePid:descriptor.pid,ownerPid:descriptor.ownerPid,nativePort:24444,nonce:descriptor.nonce})+'\n',{mode:0o600});};
  t.after(()=>fs.rmSync(parent,{recursive:true,force:true})); // exact directory owned by this unit fixture
  return {project,run,env,descriptor,file,write};
}
test('private native descriptor checks endpoint, own PID, exact keys, file mode and immutable scope',t=>{
  const f=fixture(t);f.write();const checked=readOwnedDescriptor(f.project,f.run,f.descriptor.ownerPid);
  assert.equal(checked.configuration.PREPARED_DB_USERNAME,'prepared_migrator');assert.equal(checked.file,f.file);assert.match(checked.sha256,/^[a-f0-9]{64}$/);
  for(const mutation of [{schemaVersion:2},{ownerPid:1},{pid:1},{username:'root'},{url:'jdbc:mysql://remote:24444/prepared_interview?sslMode=REQUIRED&serverTimezone=Asia/Shanghai'},
    {url:'jdbc:mysql://127.0.0.1:13317/prepared_interview?sslMode=REQUIRED&serverTimezone=Asia/Shanghai'},{url:'jdbc:mysql://127.0.0.1:0/prepared_interview?sslMode=REQUIRED&serverTimezone=Asia/Shanghai'},
    {password:'short'},{nonce:'invalid'},{data:path.dirname(f.descriptor.data)},{unknown:'untrusted'}]){
    fs.writeFileSync(f.file,JSON.stringify({...f.descriptor,...mutation}));assert.throws(()=>readOwnedDescriptor(f.project,f.run,f.descriptor.ownerPid));
  }
  f.write();fs.chmodSync(f.file,0o644);assert.throws(()=>readOwnedDescriptor(f.project,f.run,f.descriptor.ownerPid));fs.chmodSync(f.file,0o600);
  fs.writeFileSync(f.file,' '.repeat(8193));assert.throws(()=>readOwnedDescriptor(f.project,f.run,f.descriptor.ownerPid));
  f.write();const original=f.file+'.saved';fs.renameSync(f.file,original);fs.symlinkSync(original,f.file);assert.throws(()=>readOwnedDescriptor(f.project,f.run,f.descriptor.ownerPid));
  assert.throws(()=>readOwnedDescriptor(path.dirname(f.project),f.run,f.descriptor.ownerPid));
});
test('native subprocess command has no inherited database, model, administrator or VM overrides',t=>{
  const f=fixture(t),command=nativeDatabaseCommand(f.project,f.run,{...f.env,PREPARED_DASHSCOPE_API_KEY:'never-forward',MYSQL_INTEGRATION_PASSWORD:'never-forward',JAVA_TOOL_OPTIONS:'never-forward'});
  assert.deepEqual(Object.keys(command.env).sort(),['HOME','JAVA_HOME','PATH','PREPARED_MYSQL_BASEDIR','JAVA_TOOL_OPTIONS'].sort());assert.equal(command.args.at(-1),f.run);
  assert.equal(command.env.JAVA_TOOL_OPTIONS,'-Xms32m -Xmx128m');
  assert.equal(command.cwd,path.join(f.project,'defense-assessment'));assert.ok(!JSON.stringify(command).includes('never-forward'));
});
function fakeProtocol(f,scenario='normal') {
  const child=new EventEmitter();child.pid=f.descriptor.ownerPid;child.exitCode=null;child.stdout=new PassThrough();child.stdin=new PassThrough();
  let observed;
  const close=()=>{child.exitCode=scenario==='nonzero'?1:0;child.emit('close',child.exitCode);};
  child.stdin.on('data',bytes=>{const command=bytes.toString().trim();process.nextTick(()=>{
    if(command==='CHECK')child.stdout.write(scenario==='protocol'?'WRONG\n':'CHECKED\n');
    if(command==='STOP'){child.stdout.write('STOPPED\n');close();}
  });});child.stdin.on('finish',()=>process.nextTick(close));
  return {child,spawn:(bin,args,options)=>{
    observed={bin,args,options};f.write();process.nextTick(()=>{if(scenario==='startup-error'){child.emit('error',new Error('private-internal-detail'));close();}else child.stdout.write('READY\n');});return child;
  },observed:()=>observed};
}
test('issued capability follows synthetic protocol, binds actual plan and is revoked after stop',async t=>{
  const f=fixture(t),process=fakeProtocol(f),owner=await startOwnedDatabase(f.project,f.run,f.env,{spawn:process.spawn});
  assert.equal(process.observed().options.shell,false);assert.equal(process.observed().options.detached,true);
  const plan=createPlan(f.project,f.env,owner.capability);assert.equal(plan.backendVerify.env.PREPARED_DB_JDBC_URL,f.descriptor.url);
  assert.equal(plan.backendVerify.env.PREPARED_DB_PASSWORD,f.descriptor.password);assert.equal(plan.backendVerify.env.PREPARED_VALIDATION_DATABASE_DESCRIPTOR,f.file);
  for(const app of plan.apps.filter(x=>x.kind==='backend'))assert.equal(app.env.PREPARED_DB_JDBC_URL,f.descriptor.url);
  for(const cmd of [plan.build,...plan.frontend,...plan.apps.filter(x=>x.kind==='frontend')])assert.equal(cmd.env.PREPARED_VALIDATION_DATABASE_DESCRIPTOR,undefined);
  await owner.check();await owner.close();await owner.close();
  assert.throws(()=>issuedDatabaseConfiguration(owner.capability,f.project),/DATABASE_OWNERSHIP_INVALID/);
  assert.ok(fs.existsSync(f.file));assert.equal(fs.statSync(path.join(f.run,'database-owner.log')).mode&0o777,0o600);
});
test('changed private descriptor cannot redirect an issued owner and is retained after failure',async t=>{
  const f=fixture(t),process=fakeProtocol(f),owner=await startOwnedDatabase(f.project,f.run,f.env,{spawn:process.spawn});
  fs.writeFileSync(f.file,JSON.stringify({...f.descriptor,nonce:'22222222-2222-4222-8222-222222222222'}));
  await assert.rejects(owner.check(),/DATABASE_OWNERSHIP_INVALID/);await owner.close();assert.ok(fs.existsSync(f.file));
});
test('abort revokes an issued owner and shuts down through its own protocol',async t=>{
  const f=fixture(t),process=fakeProtocol(f),controller=new AbortController();
  const owner=await startOwnedDatabase(f.project,f.run,f.env,{spawn:process.spawn,signal:controller.signal});
  controller.abort();
  await assert.rejects(owner.check(),/DATABASE_OWNERSHIP_INVALID/);
  await owner.close();assert.notEqual(process.child.exitCode,null);assert.ok(fs.existsSync(f.file));
});
test('unexpected owner exit between final check and close cannot be accepted as clean shutdown',async t=>{
  const f=fixture(t),process=fakeProtocol(f),owner=await startOwnedDatabase(f.project,f.run,f.env,{spawn:process.spawn});
  await owner.check();process.child.exitCode=0;process.child.emit('close',0);
  await assert.rejects(owner.close(),/OWNED_DATABASE_FAILED/);
  assert.throws(()=>issuedDatabaseConfiguration(owner.capability,f.project),/DATABASE_OWNERSHIP_INVALID/);
});
for(const scenario of ['startup-error','protocol','nonzero'])test('synthetic owner failure remains failure and only closes its own handle '+scenario,async t=>{
  const f=fixture(t),process=fakeProtocol(f,scenario);
  if(scenario==='startup-error')await assert.rejects(startOwnedDatabase(f.project,f.run,f.env,{spawn:process.spawn}),/OWNED_DATABASE_FAILED/);
  else {
    const owner=await startOwnedDatabase(f.project,f.run,f.env,{spawn:process.spawn});
    if(scenario==='protocol'){await assert.rejects(owner.check(),/OWNED_DATABASE_FAILED/);await owner.close();}
    else await assert.rejects(owner.close(),/DATABASE_OWNERSHIP_INVALID/);
  }
  assert.notEqual(process.child.exitCode,null);
});

test('all operational prepared fixtures share the checked database entry instead of a hardcoded address',()=>{
  const files=['PreparedPublicationLiveTest','PreparedAudioLiveTest','PreparedJobLedgerLiveTest','PreparedGenerationLiveTest','PreparedProcessingSchemaLiveTest',
    'PreparedPaperLiveHttpTest','PreparedLoginAdmissionLiveHttpTest','PreparedQueuedMediaLiveHttpTest','PreparedBrowserFixtureIsolationLiveTest','PreparedGenerationBrowserFixtureTest','PreparedBrowserFixtureTest'];
  for(const name of files){
    const body=fs.readFileSync(new URL('../../defense-assessment/src/test/java/system/assessment/defense/prepared/'+name+'.java',import.meta.url),'utf8');
    assert.ok(!body.includes('jdbc:mysql://127.0.0.1:13317/'),name+' must not silently connect to canonical state');
    assert.ok(body.includes('PreparedLiveDatabase.jdbcUrl()'),name+' must check the actual issued server');
  }
});

test('operational datadir guards use the checked owner scope, not an inherited canonical directory',()=>{
  const root=path.resolve(import.meta.dirname,'../..');
  for(const name of ['PreparedLoginAdmissionLiveHttpTest','PreparedBrowserFixtureIsolationLiveTest']){
    const body=fs.readFileSync(path.join(root,'defense-assessment/src/test/java/system/assessment/defense/prepared',name+'.java'),'utf8');
    assert.ok(body.includes('SELECT @@datadir'),name+' must retain the actual server identity assertion');
    assert.ok(body.includes('PreparedLiveDatabase.checked().expectedData()'),name+' must compare with the verified current owner');
    assert.ok(!body.includes('.runtime/mysql'),name+' must not silently assume or adopt canonical state');
  }
});
