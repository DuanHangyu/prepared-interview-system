import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

// Real temporary filesystem fixtures. Only the live mysqld inventory is a seam;
// these tests do not assert database, cloud, browser or interview acceptance.
const missingUrl = new URL('../cleanup-prepared-test-artifacts.mjs', import.meta.url);
const implementation = await import(missingUrl.href).catch(error => {
  if (error.code === 'ERR_MODULE_NOT_FOUND' && error.url === missingUrl.href) return {};
  throw error;
});
const api = () => {
  assert.equal(typeof implementation.createCleanupPlan, 'function', 'safe cleanup planning must be implemented');
  assert.equal(typeof implementation.applyCleanupPlan, 'function', 'guarded cleanup must be implemented');
  return implementation;
};
const clock = Date.now();
const old = new Date(clock - 2 * 60 * 60 * 1000);
const deps = { now: () => clock, mysqlDirectories: () => [] };
function fixture(t) {
  const parent = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'prepared-test-cleanup-')));
  const root = path.join(parent, 'prepared-interview-system');
  fs.mkdirSync(root); fs.mkdirSync(path.join(root, '.runtime'), { mode: 0o700 });
  t.after(() => fs.rmSync(parent, { recursive: true, force: false }));
  return root;
}
function file(name, contents) {
  fs.writeFileSync(name, contents, { mode: 0o600 }); fs.utimesSync(name, old, old);
}
function native(root, name = 'om-12345678') {
  const base = path.join(root, '.runtime', name), data = path.join(base, '.runtime/mysql');
  fs.mkdirSync(data, { recursive: true, mode: 0o700 });
  fs.chmodSync(base, 0o700); fs.chmodSync(path.dirname(data), 0o700);
  file(path.join(base, 'stopped-proof.txt'), 'owned-migration-v1\n');
  file(path.join(base, 'server.log'), 'Shutdown complete\n');
  file(path.join(data, 'fixture.ibd'), 'disposable native fixture');
  return { base, data };
}
function run(root, name, result = { counts: { tests: 1, failures: 0, errors: 0, skipped: 0 }, failure: null }) {
  const directory = path.join(root, '.runtime', name); fs.mkdirSync(directory, { mode: 0o700 });
  file(path.join(directory, 'result.json'), JSON.stringify(result));
  file(path.join(directory, 'application.jar'), 'reproducible package');
  const runTime = new Date(old.getTime() + Number(name.slice(-6)) * 1000);
  fs.utimesSync(path.join(directory, 'result.json'), runTime, runTime);
  fs.utimesSync(directory, runTime, runTime); return directory;
}
function plan(root, extra = {}) { return api().createCleanupPlan(root, { ...deps, ...extra }); }

test('dry run selects only normally stopped, aged, owned native data without deleting evidence', t => {
  const root = fixture(t), value = native(root), result = plan(root);
  assert.deepEqual(result.targets.map(x => x.relative), ['.runtime/om-12345678/.runtime/mysql']);
  assert.ok(result.allocatedBytes > 0); assert.ok(fs.existsSync(value.data));
  assert.ok(fs.existsSync(path.join(value.base, 'server.log')));
});
test('guarded apply deletes selected disposable data but preserves the source, canonical database and logs', t => {
  const root = fixture(t), value = native(root);
  fs.mkdirSync(path.join(root, '.runtime/mysql'), { mode: 0o700 });
  file(path.join(root, '.runtime/mysql/business.ibd'), 'preserve'); file(path.join(root, 'source.txt'), 'source');
  const result = api().applyCleanupPlan(plan(root), deps);
  assert.equal(result.removed.length, 1); assert.ok(!fs.existsSync(value.data));
  for (const name of ['source.txt', '.runtime/mysql/business.ibd', '.runtime/om-12345678/server.log', '.runtime/om-12345678/stopped-proof.txt']) assert.ok(fs.existsSync(path.join(root, name)));
  assert.ok(!fs.existsSync(path.join(root, '.runtime/validation.lock')));
  assert.equal(fs.statSync(result.report).mode & 0o777, 0o600);
});
test('an active operation lock blocks deletion and remains owned by the original operator', t => {
  const root = fixture(t), value = native(root), prepared = plan(root);
  fs.mkdirSync(path.join(root, '.runtime/validation.lock'), { mode: 0o700 });
  assert.throws(() => api().applyCleanupPlan(prepared, deps), /VALIDATION_ALREADY_RUNNING/);
  assert.ok(fs.existsSync(value.data)); assert.ok(fs.existsSync(path.join(root, '.runtime/validation.lock')));
});
for (const kind of ['missing-proof', 'wrong-proof', 'no-shutdown', 'recent-proof', 'pid-file', 'socket-file', 'unknown-prefix']) {
  test('refuses native data: ' + kind, t => {
    const root = fixture(t), value = native(root, kind === 'unknown-prefix' ? 'other-12345678' : undefined);
    const proof = path.join(value.base, 'stopped-proof.txt');
    if (kind === 'missing-proof') fs.unlinkSync(proof);
    if (kind === 'wrong-proof') file(proof, 'not owned');
    if (kind === 'no-shutdown') file(path.join(value.base, 'server.log'), 'still running');
    if (kind === 'recent-proof') fs.utimesSync(proof, new Date(clock), new Date(clock));
    if (kind === 'pid-file') file(path.join(value.data, 'server.pid'), '1');
    if (kind === 'socket-file') file(path.join(value.data, 'mysql.sock'), 'active');
    assert.equal(plan(root).targets.length, 0); assert.ok(fs.existsSync(value.data));
  });
}
test('live mysqld directories and inventory failure cannot be treated as stopped', t => {
  const root = fixture(t), value = native(root);
  assert.equal(plan(root, { mysqlDirectories: () => [value.data] }).targets.length, 0);
  assert.throws(() => plan(root, { mysqlDirectories: () => { throw Error('MYSQL_INVENTORY_UNAVAILABLE'); } }), /MYSQL_INVENTORY_UNAVAILABLE/);
});
test('latest eight validation directories protect their native fixtures and reproducible packages', t => {
  const root = fixture(t), value = native(root), directory = run(root, 'validation-000001');
  file(path.join(directory, 'database.json'), JSON.stringify({ data: value.data }));
  assert.equal(plan(root).targets.length, 0);
});
test('maintenance changes to directory times cannot promote old runs above the actual latest eight results', t => {
  const root = fixture(t), value = native(root);
  for (let i = 0; i < 10; i++) run(root, 'validation-' + String(i).padStart(6, '0'));
  file(path.join(root, '.runtime/validation-000002/database.json'), JSON.stringify({ data: value.data }));
  fs.utimesSync(path.join(root, '.runtime/validation-000000'), new Date(clock), new Date(clock));
  const targets = plan(root).targets.map(x => x.relative);
  assert.ok(targets.includes('.runtime/validation-000000/application.jar'));
  assert.ok(!targets.includes('.runtime/validation-000002/application.jar'));
  assert.ok(!targets.includes('.runtime/om-12345678/.runtime/mysql'));
});
test('failed and unclassified validation evidence remains protected after newer runs exist', t => {
  const root = fixture(t), value = native(root), directory = run(root, 'validation-000000', { failure: 'FAILED' });
  file(path.join(directory, 'database.json'), JSON.stringify({ data: value.data }));
  fs.utimesSync(directory, old, old);
  for (let i = 1; i <= 9; i++) run(root, 'validation-' + String(i).padStart(6, '0'));
  const targets = plan(root).targets.map(value => value.relative);
  assert.ok(!targets.includes('.runtime/om-12345678/.runtime/mysql'));
  assert.ok(!targets.includes('.runtime/validation-000000/application.jar'));
  assert.ok(fs.existsSync(path.join(directory, 'application.jar')));
});
test('old successful packages can be removed while their reports and coverage remain', t => {
  const root = fixture(t), directory = run(root, 'validation-000000');
  file(path.join(directory, 'report.xml'), 'actual report'); file(path.join(directory, 'jacoco.exec'), 'coverage');
  fs.utimesSync(directory, old, old);
  for (let i = 1; i <= 9; i++) run(root, 'validation-' + String(i).padStart(6, '0'));
  const prepared = plan(root); assert.deepEqual(prepared.targets.map(x => x.relative), ['.runtime/validation-000000/application.jar', '.runtime/validation-000001/application.jar']);
  api().applyCleanupPlan(prepared, deps); assert.ok(fs.existsSync(path.join(directory, 'report.xml'))); assert.ok(fs.existsSync(path.join(directory, 'jacoco.exec')));
});
test('old completed full-regression packages are disposable, but classes and execution evidence remain', t => {
  const root = fixture(t), directory = run(root, 'validation-000000', {
    scope: 'local-automated-regression', suites: 2, tests: 3, failures: 0, errors: 0, skipped: 0
  });
  const artifacts = path.join(directory, 'completed-artifacts'), target = path.join(artifacts, 'backend-target');
  fs.mkdirSync(target, { recursive: true, mode: 0o700 }); fs.chmodSync(artifacts, 0o700);
  for (const name of ['defense-assessment-system-0.0.1-SNAPSHOT.jar', 'defense-assessment-system-0.0.1-SNAPSHOT.jar.original', 'jacoco.exec', 'Class.class']) file(path.join(target, name), 'retained-or-rebuildable');
  fs.utimesSync(directory, old, old);
  for (let i = 1; i <= 9; i++) run(root, 'validation-' + String(i).padStart(6, '0'));
  const prepared = plan(root);
  assert.equal(prepared.targets.filter(x => x.relative.startsWith('.runtime/validation-000000/')).length, 3);
  api().applyCleanupPlan(prepared, deps);
  for (const name of ['jacoco.exec', 'Class.class']) assert.ok(fs.existsSync(path.join(target, name)));
});
test('root-level counts cannot turn unknown or failed evidence into a cleanup authorization', t => {
  const root = fixture(t);
  for (const [index, scope, failureCode] of [[0, 'unknown', undefined], [1, 'local-automated-regression', 'FAILED']]) {
    const directory = run(root, 'validation-' + String(index).padStart(6, '0'), {
      scope, tests: 3, failures: 0, errors: 0, skipped: 0, failureCode
    }); fs.utimesSync(directory, old, old);
  }
  for (let i = 2; i <= 10; i++) run(root, 'validation-' + String(i).padStart(6, '0'));
  const targets = plan(root).targets.map(x => x.relative);
  for (const name of ['000000', '000001']) assert.ok(!targets.includes('.runtime/validation-' + name + '/application.jar'));
});
test('a linked completed artifact parent is never traversed to select packages', t => {
  const root = fixture(t), directory = run(root, 'validation-000000');
  const outside = path.join(path.dirname(root), 'outside'); fs.mkdirSync(outside, { mode: 0o700 });
  fs.mkdirSync(path.join(outside, 'backend-target'), { mode: 0o700 });
  file(path.join(outside, 'backend-target/defense-assessment-system-0.0.1-SNAPSHOT.jar'), 'preserve');
  fs.symlinkSync(outside, path.join(directory, 'completed-artifacts')); fs.utimesSync(directory, old, old);
  for (let i = 1; i <= 9; i++) run(root, 'validation-' + String(i).padStart(6, '0'));
  assert.ok(!plan(root).targets.some(x => x.relative.includes('completed-artifacts')));
});
test('symlinked roots, runtime, fixture data and linked children are not traversed', t => {
  const root = fixture(t), value = native(root), outside = path.join(path.dirname(root), 'outside');
  fs.mkdirSync(outside); file(path.join(outside, 'preserve.txt'), 'preserve');
  fs.symlinkSync(outside, path.join(value.data, 'linked')); assert.equal(plan(root).targets.length, 0);
  fs.unlinkSync(path.join(value.data, 'linked')); fs.rmSync(value.data, { recursive: true }); fs.symlinkSync(outside, value.data);
  assert.equal(plan(root).targets.length, 0);
  const alias = path.join(path.dirname(root), 'alias'); fs.symlinkSync(root, alias);
  assert.throws(() => plan(alias), /UNSAFE_CLEANUP_ROOT/);
  fs.renameSync(path.join(root, '.runtime'), path.join(root, 'retained-runtime')); fs.symlinkSync(outside, path.join(root, '.runtime'));
  assert.throws(() => plan(root), /UNSAFE_CLEANUP_RUNTIME/); assert.equal(fs.readFileSync(path.join(outside, 'preserve.txt'), 'utf8'), 'preserve');
});
test('changed files or an active database appearing after planning abort before deleting anything', t => {
  const root = fixture(t), value = native(root), prepared = plan(root);
  file(path.join(value.data, 'fixture.ibd'), 'changed content');
  assert.throws(() => api().applyCleanupPlan(prepared, deps), /CLEANUP_PLAN_CHANGED/); assert.ok(fs.existsSync(value.data));
  const second = plan(root);
  assert.throws(() => api().applyCleanupPlan(second, { ...deps, mysqlDirectories: () => [value.data] }), /CLEANUP_PLAN_CHANGED/);
  assert.ok(fs.existsSync(value.data)); assert.ok(!fs.existsSync(path.join(root, '.runtime/validation.lock')));
});
test('a forged or serialized plan cannot authorize any filesystem mutation', t => {
  const root = fixture(t), value = native(root), prepared = plan(root);
  for (const forged of [{ root, targets: [{ relative: '.runtime/mysql' }] }, JSON.parse(JSON.stringify(prepared))]) assert.throws(() => api().applyCleanupPlan(forged, deps), /UNISSUED_CLEANUP_PLAN/);
  assert.ok(fs.existsSync(value.data));
});
test('empty operation is safe and does not create a deletion report', t => {
  const root = fixture(t), result = api().applyCleanupPlan(plan(root), deps);
  assert.deepEqual(result.removed, []); assert.equal(result.report, null);
});
test('new server activity after an old stop proof prevents data deletion', t => {
  const root = fixture(t), value = native(root);
  fs.utimesSync(path.join(value.base, 'server.log'), new Date(clock), new Date(clock));
  assert.equal(plan(root).targets.length, 0); assert.ok(fs.existsSync(value.data));
});
test('malformed credential descriptors fail closed without including their contents in errors', t => {
  const root = fixture(t), value = native(root), directory = run(root, 'validation-000001');
  file(path.join(directory, 'database.json'), '{"password":"never-print-this-secret","data":broken}');
  assert.throws(() => plan(root), error => error.message === 'UNSAFE_CLEANUP_REFERENCE' && !error.message.includes('never-print'));
  assert.ok(fs.existsSync(value.data));
});
test('descriptors cannot redirect protection or cleanup to canonical or foreign data', t => {
  const root = fixture(t), value = native(root), directory = run(root, 'validation-000001');
  for (const data of [path.join(root, '.runtime/mysql'), '/tmp/foreign/mysql']) {
    file(path.join(directory, 'database.json'), JSON.stringify({ data }));
    assert.throws(() => plan(root), /UNSAFE_CLEANUP_REFERENCE/); assert.ok(fs.existsSync(value.data));
  }
});
test('permissive runtime and invalid clocks are refused', t => {
  const root = fixture(t); fs.chmodSync(path.join(root, '.runtime'), 0o755);
  assert.throws(() => plan(root), /UNSAFE_CLEANUP_RUNTIME/);
  fs.chmodSync(path.join(root, '.runtime'), 0o700);
  assert.throws(() => plan(root, { now: () => NaN }), /MYSQL_INVENTORY_UNAVAILABLE/);
});
test('CLI rejects ambiguous arguments, defaults to a real read-only plan and requires explicit apply', t => {
  const root = fixture(t), value = native(root), output = [], errors = [];
  const io = { out: text => output.push(text), err: text => errors.push(text) };
  assert.equal(api().cleanupCli(['--root', root, '--force'], io), 1);
  assert.equal(api().cleanupCli([], io), 1); assert.ok(fs.existsSync(value.data));
  if (process.platform === 'darwin') {
    assert.equal(api().cleanupCli(['--root', root], io), 0);
    assert.equal(JSON.parse(output.at(-1)).mode, 'dry-run'); assert.ok(fs.existsSync(value.data));
    assert.equal(api().cleanupCli(['--root', root, '--apply'], io), 0);
    assert.equal(JSON.parse(output.at(-1)).mode, 'applied'); assert.ok(!fs.existsSync(value.data));
  } else {
    // Unsupported live process inventory must never become implicit permission.
    assert.equal(api().cleanupCli(['--root', root, '--apply'], io), 1); assert.ok(fs.existsSync(value.data));
  }
});
