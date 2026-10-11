import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const moduleUrl = new URL('../verify-backend-evidence.mjs', import.meta.url);
const script = fileURLToPath(moduleUrl);
const runtime = fileURLToPath(new URL('../../.runtime/', import.meta.url));
const packages = ['paper', 'jobs', 'generation', 'publication', 'media', 'speech', 'storage', 'audio', 'attempt', 'processing', 'bootstrap', 'retention'];
const api = () => import(moduleUrl.href);

function put(root, relative, value) {
  const target = path.join(root, relative);
  fs.mkdirSync(path.dirname(target), { recursive: true, mode: 0o700 });
  fs.writeFileSync(target, value, { mode: 0o600 });
  return target;
}

function execData(since, { probes = 8, start = since + 1, dump = since + 2 } = {}) {
  const utf = value => { const b = Buffer.from(value); const len = Buffer.alloc(2); len.writeUInt16BE(b.length); return Buffer.concat([len, b]); };
  const long = value => { const b = Buffer.alloc(8); b.writeBigInt64BE(BigInt(value)); return b; };
  const size = []; let n = probes;
  do { const v = n % 128; n = Math.floor(n / 128); size.push(v | (n ? 128 : 0)); } while (n);
  return Buffer.concat([Buffer.from([1, 0xc0, 0xc0, 0x10, 7, 0x10]), utf('synthetic-session'), long(start), long(dump),
    Buffer.from([0x11]), long(42), utf('sample/Example'), Buffer.from(size), Buffer.alloc(Math.ceil(probes / 8), 255)]);
}

function report(options = {}) {
  const values = { name: 'sample.ExampleTest', tests: 1, failures: 0, errors: 0, skipped: 0, ...options };
  return `<?xml version="1.0"?><testsuite ${Object.entries(values).map(([k,v]) => `${k}="${v}"`).join(' ')}><properties><property name="sensitive" value="DO_NOT_PRINT_THIS_SECRET"/></properties></testsuite>`;
}

function coverage({ miss = 0, skip = '', child = false } = {}) {
  const counters = `<counter type="LINE" missed="${miss}" covered="100"/><counter type="BRANCH" missed="${miss}" covered="100"/>`;
  return `<?xml version="1.0"?><!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd"><report name="synthetic">${packages.filter(x => x !== skip).map(x => `<package name="system/assessment/defense/prepared/${x}">${child ? `<class name="sample/Example"><method name="example">${counters}</method>${counters}</class><sourcefile name="Example.java"><line nr="1" mi="0" ci="1" mb="0" cb="1"/>${counters}</sourcefile>` : ''}${counters}</package>`).join('')}${counters}</report>`;
}

async function fixture() {
  fs.mkdirSync(runtime, { recursive: true, mode: 0o700 });
  const owner = fs.mkdtempSync(path.join(runtime, 'evidence-unit-'));
  fs.chmodSync(owner, 0o700);
  const root = path.join(owner, 'prepared-interview-system'); fs.mkdirSync(root, { mode: 0o700 });
  put(root, 'defense-assessment/pom.xml', '<project/>');
  put(root, 'defense-assessment/mvnw', '# synthetic wrapper');
  put(root, 'defense-assessment/.mvn/wrapper/maven-wrapper.properties', 'distributionUrl=synthetic');
  put(root, 'defense-assessment/src/main/java/Example.java', 'package sample; class Example {}');
  put(root, 'defense-assessment/src/test/java/ExampleTest.java', 'package sample; class ExampleTest { @Test void example() {} }');
  put(root, 'defense-system/src/example.ts', 'export const synthetic = true;');
  put(root, 'defense-system/tests/example.test.ts', '// synthetic browser test');
  put(root, 'defense-system/scripts/example.mjs', '// synthetic browser helper');
  for (const name of ['package.json','package-lock.json','vite.config.ts','vitest.config.ts','playwright.config.ts','tsconfig.json']) put(root, 'defense-system/' + name, '{}');
  for (const name of ['prepared-local-config.sh','init-local-mysql.sh','start-local-mysql.sh','migrate-local-mysql.sh','start-db-tunnel.sh','start-backend.sh']) put(root, 'scripts/' + name, '# synthetic launcher');
  const since = Date.now() - 1000;
  const reports = 'defense-assessment/target/surefire-reports/';
  const xml = put(root, reports + 'TEST-sample.ExampleTest.xml', report());
  const raw = put(root, 'defense-assessment/target/jacoco.exec', execData(since));
  const merged = put(root, 'defense-assessment/target/prepared-merged.exec', execData(since));
  const cov = put(root, 'defense-assessment/target/site/prepared-coverage/jacoco.xml', coverage());
  const m = await api();
  const sourceSha256 = m.sourceFingerprint(root);
  return { root, since, xml, raw, merged, cov, reports, options: { root, since, sourceSha256, mavenExitCode: 0 } };
}

test('new verifier checks one fresh complete synthetic suite without reading credentials', async () => {
  const f = await fixture(), m = await api();
  put(f.root, '.env.local', 'SECRET=DO_NOT_PRINT_THIS_SECRET');
  const result = m.verifyEvidence(f.options);
  assert.equal(result.suites, 1); assert.equal(result.tests, 1); assert.equal(result.coverageSessions, 1);
  assert.equal(result.packages.length, 12); assert.equal(result.sourceSha256, f.options.sourceSha256);
  assert.equal(JSON.stringify(result).includes('DO_NOT_PRINT_THIS_SECRET'), false);
});

for (const [name, options] of [['failed',{ failures: 1 }], ['errored',{ errors: 1 }], ['skipped',{ skipped: 1 }], ['empty',{ tests: 0 }]]) {
  test(`rejects ${name} test results`, async () => {
    const f = await fixture(), m = await api(); fs.writeFileSync(f.xml, report(options));
    assert.throws(() => (m.verifyEvidence(f.options)), /TEST_RESULTS_NOT_PASSING/);
  });
}

test('rejects changed application, tests and launcher sources', async () => {
  const f = await fixture(), m = await api(); put(f.root, 'scripts/start-backend.sh', '# changed');
  assert.throws(() => m.verifyEvidence(f.options), /SOURCE_CHANGED/);
});

test('a browser-dependent backend result cannot be accepted after frontend input changes', async () => {
  const f = await fixture(), m = await api();
  put(f.root, 'defense-system/src/example.ts', 'export const synthetic = false;');
  assert.throws(() => m.verifyEvidence(f.options), /SOURCE_CHANGED/);
});

test('supports embedded Vitest configuration and fingerprints actual configuration variants', async () => {
  const f = await fixture(), m = await api();
  fs.renameSync(path.join(f.root,'defense-system/vitest.config.ts'),path.join(f.root,'defense-system/vitest.config.ts.held'));
  put(f.root,'defense-system/tsconfig.app.json','{}');
  f.options.sourceSha256=m.sourceFingerprint(f.root);
  assert.equal(m.verifyEvidence(f.options).tests,1);
  put(f.root,'defense-system/tsconfig.app.json','{"changed":true}');
  assert.throws(()=>m.verifyEvidence(f.options),/SOURCE_CHANGED/);
});

test('recognizes plural, IT and fully-qualified method annotations, not comments or strings', async () => {
  const f = await fixture(), m = await api();
  put(f.root, 'defense-assessment/src/test/java/StartupTests.java', 'package sample; class StartupTests { @org.junit.jupiter.api.Test void boot(){} }');
  put(f.root, 'defense-assessment/src/test/java/DatabaseIT.java', 'package sample; class DatabaseIT { @ParameterizedTest void data(){} }');
  put(f.root, 'defense-assessment/src/test/java/Helper.java', 'package sample; class Helper { String x="@Test"; /* @Test */ // @Test\n String y="""\n@Test\n"""; char z=\'x\'; }');
  put(f.root, f.reports + 'TEST-sample.StartupTests.xml', report({ name: 'sample.StartupTests' }));
  put(f.root, f.reports + 'TEST-sample.DatabaseIT.xml', report({ name: 'sample.DatabaseIT', tests: 2 }));
  f.options.sourceSha256 = m.sourceFingerprint(f.root);
  assert.equal(m.verifyEvidence(f.options).suites, 3); assert.equal(m.verifyEvidence(f.options).tests, 4);
});

test('rejects missing, unexpected or mismatched fresh suite identities', async () => {
  const f = await fixture(), m = await api();
  fs.renameSync(f.xml, f.xml + '.held'); assert.throws(() => m.verifyEvidence(f.options), /TEST_SCOPE_INCOMPLETE/);
  fs.renameSync(f.xml + '.held', f.xml); put(f.root, f.reports + 'TEST-sample.Unexpected.xml', report({ name: 'sample.Unexpected' }));
  assert.throws(() => m.verifyEvidence(f.options), /TEST_SCOPE_INCOMPLETE/);
  fs.renameSync(path.join(f.root, f.reports, 'TEST-sample.Unexpected.xml'), path.join(f.root, f.reports, 'unexpected.held'));
  fs.writeFileSync(f.xml, report({ name: 'sample.Wrong' })); assert.throws(() => m.verifyEvidence(f.options), /REPORT_IDENTITY_INVALID/);
});

test('stale XML cannot stand in for a missing result', async () => {
  const f = await fixture(), m = await api(); fs.utimesSync(f.xml, new Date(f.since - 10000), new Date(f.since - 10000));
  assert.throws(() => m.verifyEvidence(f.options), /STALE_REPORT/);
});

for (const bad of [report().replace('tests="1"','tests="1" tests="2"'), report().replace('tests="1"','tests="NaN"'), report().replace('tests="1"','tests="-1"'), report().replace('tests="1"','tests="1.5"'), report().replace(' tests="1"',''), '<testsuite>', report().replace('</testsuite>', ''), '<!DOCTYPE testsuite><testsuite/>']) {
  test('rejects malformed suite metadata without exposing XML properties', async () => {
    const f = await fixture(), m = await api(); fs.writeFileSync(f.xml, bad);
    assert.throws(() => m.verifyEvidence(f.options), /REPORT_METADATA_INVALID/);
  });
}

for (const target of ['raw','merged','cov']) test(`rejects stale ${target} coverage artifact`, async () => {
  const f = await fixture(), m = await api(); fs.utimesSync(f[target], new Date(f.since-10000), new Date(f.since-10000));
  assert.throws(() => m.verifyEvidence(f.options), /STALE_COVERAGE/);
});

test('detects a historical appended session even if the file modification time is fresh', async () => {
  const f = await fixture(), m = await api(); fs.appendFileSync(f.raw, execData(f.since - 10000));
  assert.throws(() => m.verifyEvidence(f.options), /STALE_COVERAGE_SESSION/);
});

test('checks supplementary browser execution data when it is present', async () => {
  const f = await fixture(), m = await api(); const p = put(f.root, '.runtime/prepared-live.exec', execData(f.since));
  assert.equal(m.verifyEvidence(f.options).browserCoverageSessions, 1);
  fs.writeFileSync(p, execData(f.since-10000)); assert.throws(() => m.verifyEvidence(f.options), /STALE_COVERAGE_SESSION/);
});

test('a broken supplementary coverage link is unsafe, not an absent optional artifact', async () => {
  const f=await fixture(),m=await api();
  fs.mkdirSync(path.join(f.root,'.runtime'),{mode:0o700});
  fs.symlinkSync('missing.exec',path.join(f.root,'.runtime/prepared-live.exec'));
  assert.throws(()=>m.verifyEvidence(f.options),/UNSAFE_EVIDENCE_PATH/);
});

test('reads compact multi-byte probe counts and ignores opaque modified-UTF names', async () => {
  const f = await fixture(), m = await api(); fs.writeFileSync(f.raw, execData(f.since, { probes: 300 }));
  assert.equal(m.verifyEvidence(f.options).coverageSessions, 1);
});

for (const kind of ['empty','unknown','magic','version','truncated','no-session','no-class','negative-time','future-time','backwards-time','varint']) test(`rejects ${kind} execution data`, async () => {
  const f = await fixture(), m = await api(); let b = execData(f.since);
  if(kind==='empty') b=Buffer.alloc(0);
  if(kind==='unknown') b=Buffer.concat([b,Buffer.from([0x99])]);
  if(kind==='magic') b[1]=0;
  if(kind==='version') b[4]=0;
  if(kind==='truncated') b=b.subarray(0,b.length-1);
  if(kind==='no-session') b=Buffer.concat([b.subarray(0,5),b.subarray(b.indexOf(Buffer.from([0x11,0,0,0,0,0,0,0,42])))]);
  if(kind==='no-class') b=b.subarray(0,b.indexOf(Buffer.from([0x11,0,0,0,0,0,0,0,42])));
  if(kind==='negative-time') b=execData(f.since,{start:-1});
  if(kind==='future-time') b=execData(f.since,{dump:Date.now()+60000});
  if(kind==='backwards-time') b=execData(f.since,{start:f.since+5,dump:f.since+1});
  if(kind==='varint') b=Buffer.concat([b.subarray(0,b.length-2),Buffer.from([128,128,128,128,128,0])]);
  fs.writeFileSync(f.raw,b); assert.throws(() => m.verifyEvidence(f.options), /COVERAGE_(FORMAT|TIME|SESSION)_INVALID/);
});

test('requires every prepared package and both independent 80 percent counters', async () => {
  const f=await fixture(),m=await api(); fs.writeFileSync(f.cov,coverage({skip:'bootstrap'}));
  assert.throws(()=>m.verifyEvidence(f.options),/COVERAGE_SCOPE_INCOMPLETE/);
  fs.writeFileSync(f.cov,coverage({miss:100})); assert.throws(()=>m.verifyEvidence(f.options),/COVERAGE_GATE_FAILED/);
  fs.writeFileSync(f.cov,coverage({child:true})); assert.equal(m.verifyEvidence(f.options).packages.length,12);
});

test('new audio-retention package cannot be omitted from the independent evidence gate',async()=>{
  const f=await fixture(),m=await api();fs.writeFileSync(f.cov,coverage({skip:'retention'}));
  assert.throws(()=>m.verifyEvidence(f.options),/COVERAGE_SCOPE_INCOMPLETE/);
});

test('actual prepared Maven report and independent package check include retention',()=>{
  const pom=fs.readFileSync(new URL('../../defense-assessment/pom.xml',import.meta.url),'utf8');
  assert.match(pom,/<include>system\/assessment\/defense\/prepared\/retention\/\*\*<\/include>/);
  assert.match(pom,/<rule>\s*<element>PACKAGE<\/element>\s*<includes><include>system\.assessment\.defense\.prepared\.retention<\/include><\/includes>\s*<limits>\s*<limit><counter>LINE<\/counter><value>COVEREDRATIO<\/value><minimum>0\.80<\/minimum><\/limit>\s*<limit><counter>BRANCH<\/counter><value>COVEREDRATIO<\/value><minimum>0\.80<\/minimum><\/limit>\s*<\/limits>\s*<\/rule>/);
});

for (const kind of ['class','sourcefile']) test(`accepts a legitimate empty ${kind} without mistaking child counters for package coverage`, async () => {
  const f=await fixture(),m=await api();
  const xml=coverage({child:true}).replace(`</${kind}>`,`</${kind}><${kind} name="sample/Empty"/>`);
  fs.writeFileSync(f.cov,xml); assert.equal(m.verifyEvidence(f.options).packages.length,12);
});

for(const bad of [coverage().replace('missed="0"','missed="-1"'),coverage().replace('type="LINE"','type="LINE" type="BRANCH"'),coverage().replace('covered="100"','covered="NaN"'),coverage().replace('<counter type="LINE" missed="0" covered="100"/>',''),coverage().replace('<package','<garbage'),'<report/>']) test('rejects malformed coverage metadata',async()=>{
  const f=await fixture(),m=await api(); fs.writeFileSync(f.cov,bad); assert.throws(()=>m.verifyEvidence(f.options),/COVERAGE_(METADATA_INVALID|SCOPE_INCOMPLETE)/);
});

test('refuses symlink sources, reports and coverage without traversing outside the project', async () => {
  for(const target of ['source','report','coverage']) {
    const f=await fixture(),m=await api();const p=target==='source'?path.join(f.root,'defense-assessment/src/main/java/Example.java'):target==='report'?f.xml:f.raw;
    fs.renameSync(p,p+'.held');fs.symlinkSync(p+'.held',p);assert.throws(()=>m.verifyEvidence(f.options),/UNSAFE_EVIDENCE_PATH/);
  }
});

test('rejects impossible timestamps, mismatched fingerprints and a nonzero Maven exit code',async()=>{
  const f=await fixture(),m=await api();
  for(const since of [0,-1,NaN,Date.now()+60000]) assert.throws(()=>m.verifyEvidence({...f.options,since}),/INVALID_ARGUMENTS/);
  assert.throws(()=>m.verifyEvidence({...f.options,sourceSha256:'not-a-hash'}),/INVALID_ARGUMENTS/);
  assert.throws(()=>m.verifyEvidence({...f.options,mavenExitCode:1}),/MAVEN_NOT_SUCCESSFUL/);
});

test('CLI fingerprint and verification expose safe totals, not report contents or input secrets',async()=>{
  const f=await fixture();const base=['--root',f.root];
  const fingerprint=spawnSync(process.execPath,[script,'--fingerprint',...base],{encoding:'utf8'});
  assert.equal(fingerprint.status,0);assert.equal(JSON.parse(fingerprint.stdout).sourceSha256,f.options.sourceSha256);
  const args=[script,...base,'--since',String(f.since),'--source-sha256',f.options.sourceSha256,'--maven-exit-code','0'];
  const ok=spawnSync(process.execPath,args,{encoding:'utf8'});assert.equal(ok.status,0);assert.equal(JSON.parse(ok.stdout).tests,1);
  fs.writeFileSync(f.xml,report({failures:1}));const bad=spawnSync(process.execPath,args,{encoding:'utf8'});
  assert.equal(bad.status,1);assert.match(bad.stderr,/TEST_RESULTS_NOT_PASSING/);assert.equal((ok.stdout+bad.stderr).includes('DO_NOT_PRINT_THIS_SECRET'),false);
});

test('CLI handles help, unsupported or duplicate options and unreadable evidence safely',async()=>{
  const f=await fixture();
  assert.equal(spawnSync(process.execPath,[script,'--help'],{encoding:'utf8'}).status,0);
  for(const args of [[],['--root'],['--unknown','DO_NOT_PRINT_THIS_SECRET'],['--root',f.root,'--root',f.root],['--fingerprint','--root',path.join(f.root,'missing')]]) {
    const r=spawnSync(process.execPath,[script,...args],{encoding:'utf8'});assert.equal(r.status,1);assert.equal(r.stderr.includes('DO_NOT_PRINT_THIS_SECRET'),false);
  }
});
