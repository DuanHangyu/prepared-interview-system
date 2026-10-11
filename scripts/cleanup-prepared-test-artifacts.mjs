#!/usr/bin/env node
// Explicit local maintenance, never business retention or database cleanup.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { acquireLock } from './run-prepared-validation.mjs';

const issued = new WeakMap();
const MIN_AGE_MS = 60 * 60 * 1000;
const KEEP_RUNS = 8;
const requireValue = (ok, code) => { if (!ok) throw new Error(code); };
function checkedRoot(root) {
  requireValue(typeof root === 'string' && path.isAbsolute(root) && path.basename(root) === 'prepared-interview-system'
    && fs.realpathSync(root) === root && fs.statSync(root).uid === process.getuid(), 'UNSAFE_CLEANUP_ROOT');
  const runtime = path.join(root, '.runtime'), stat = fs.lstatSync(runtime);
  requireValue(stat.isDirectory() && !stat.isSymbolicLink() && stat.uid === process.getuid()
    && (stat.mode & 0o777) === 0o700 && fs.realpathSync(runtime) === runtime, 'UNSAFE_CLEANUP_RUNTIME');
  return runtime;
}
function privateNode(file, directory = false) {
  const stat = fs.lstatSync(file, { throwIfNoEntry: false });
  return stat && !stat.isSymbolicLink() && stat.uid === process.getuid() && fs.realpathSync(file) === file
    && (directory ? stat.isDirectory() && (stat.mode & 0o777) === 0o700 : stat.isFile() && (stat.mode & 0o777) === 0o600);
}
function readSmall(file, limit) {
  requireValue(privateNode(file) && fs.statSync(file).size <= limit, 'UNSAFE_CLEANUP_MARKER');
  return fs.readFileSync(file, 'utf8');
}
function mysqlDirectories() {
  let output;
  try {
    output = execFileSync('/usr/sbin/lsof', ['-nP', '-a', '-c', 'mysqld', '-d', 'cwd', '-F', 'pfn'],
      { encoding: 'utf8', timeout: 10000, maxBuffer: 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] });
  } catch (error) {
    if (error.status === 1 && !String(error.stdout ?? '').trim() && !String(error.stderr ?? '').trim()) return [];
    throw new Error('MYSQL_INVENTORY_UNAVAILABLE');
  }
  const directories = [], owners = new Set(); let owner, field;
  for (const line of output.trim().split('\n')) {
    if (/^p\d+$/.test(line)) { owner = line.slice(1); field = undefined; }
    else if (line === 'fcwd') field = 'cwd';
    else if (line.startsWith('n')) {
      requireValue(owner && field === 'cwd' && path.isAbsolute(line.slice(1)) && !line.endsWith(' (deleted)'), 'MYSQL_INVENTORY_UNAVAILABLE');
      directories.push(fs.realpathSync(line.slice(1))); owners.add(owner);
    }
  }
  requireValue(owners.size === output.split('\n').filter(line => /^p\d+$/.test(line)).length, 'MYSQL_INVENTORY_UNAVAILABLE');
  return directories;
}
const defaults = { now: () => Date.now(), mysqlDirectories };
function inventory(file) {
  const entries = []; let allocatedBytes = 0;
  function visit(current, relative) {
    const stat = fs.lstatSync(current);
    requireValue(stat.uid === process.getuid() && !stat.isSymbolicLink()
      && (stat.isDirectory() || stat.isFile()) && (stat.isDirectory() || stat.nlink === 1), 'UNSAFE_CLEANUP_TREE');
    requireValue(entries.length < 20000, 'CLEANUP_TREE_TOO_LARGE');
    entries.push({ relative, directory: stat.isDirectory(), dev: stat.dev, ino: stat.ino, size: stat.size, mtimeMs: stat.mtimeMs });
    allocatedBytes += stat.blocks * 512;
    if (stat.isDirectory()) for (const name of fs.readdirSync(current).sort()) visit(path.join(current, name), path.join(relative, name));
  }
  visit(file, ''); return { entries, allocatedBytes };
}
function collect(root, dependencies) {
  const runtime = checkedRoot(root), now = dependencies.now(), active = dependencies.mysqlDirectories();
  requireValue(Number.isFinite(now) && Array.isArray(active) && active.every(value => typeof value === 'string' && path.isAbsolute(value)), 'MYSQL_INVENTORY_UNAVAILABLE');
  const directories = fs.readdirSync(runtime).filter(name => /^validation-[A-Za-z0-9]{6}$/.test(name))
    .map(name => {
      const file = path.join(runtime, name); requireValue(privateNode(file, true), 'UNSAFE_CLEANUP_MARKER');
      const result = path.join(file, 'result.json');
      // Removing a package changes its parent's mtime, not the execution time.
      // Unclassified runs are always protected, irrespective of their ordering.
      return { name, file, mtime: privateNode(result) ? fs.statSync(result).mtimeMs : fs.statSync(file).birthtimeMs };
    })
    .sort((a, b) => b.mtime - a.mtime || b.name.localeCompare(a.name));
  const protectedNative = new Set(), protectedRuns = new Set(directories.slice(0, KEEP_RUNS).map(value => value.name));
  for (const value of directories) {
    let result;
    try { result = JSON.parse(readSmall(path.join(value.file, 'result.json'), 65536)); } catch { /* Unclassified or incomplete evidence is retained. */ }
    const counts = result?.counts ?? (result?.scope === 'local-automated-regression' ? result : undefined);
    if (!counts || !Number.isSafeInteger(counts.tests) || counts.tests < 1 || counts.failures !== 0 || counts.errors !== 0
      || counts.skipped !== 0 || result.failure || result.commandFailure || result.failureCode) protectedRuns.add(value.name);
    if (!protectedRuns.has(value.name)) continue;
    const descriptor = path.join(value.file, 'database.json');
    if (!fs.existsSync(descriptor)) continue;
    let data;
    try { data = JSON.parse(readSmall(descriptor, 8192)).data; }
    catch { throw new Error('UNSAFE_CLEANUP_REFERENCE'); }
    requireValue(typeof data === 'string' && path.isAbsolute(data) && path.dirname(path.dirname(path.dirname(data))) === runtime
      && /^om-[a-f0-9]{8}$/.test(path.basename(path.dirname(path.dirname(data))))
      && path.basename(path.dirname(data)) === '.runtime' && path.basename(data) === 'mysql', 'UNSAFE_CLEANUP_REFERENCE');
    protectedNative.add(data);
  }
  const targets = [], skipped = [];
  function add(file, kind) {
    try { const tree = inventory(file); targets.push({ relative: path.relative(root, file), kind, ...tree }); }
    catch (error) { skipped.push({ relative: path.relative(root, file), reason: error.message }); }
  }
  for (const name of fs.readdirSync(runtime).sort()) {
    if (!/^om-[a-f0-9]{8}$/.test(name)) continue;
    const base = path.join(runtime, name), data = path.join(base, '.runtime/mysql'), proof = path.join(base, 'stopped-proof.txt');
    if (!fs.existsSync(data)) continue;
    try {
      requireValue(privateNode(base, true) && privateNode(path.join(base, '.runtime'), true) && privateNode(data, true), 'UNSAFE_CLEANUP_TREE');
      requireValue(readSmall(proof, 64) === 'owned-migration-v1\n'
        && /Shutdown complete/.test(readSmall(path.join(base, 'server.log'), 1024 * 1024)), 'NO_NORMAL_STOP_PROOF');
      requireValue(fs.statSync(path.join(base, 'server.log')).mtimeMs <= fs.statSync(proof).mtimeMs, 'SERVER_CHANGED_AFTER_STOP');
      requireValue(now - fs.statSync(proof).mtimeMs >= MIN_AGE_MS, 'RECENT_FIXTURE');
      requireValue(!fs.existsSync(path.join(data, 'server.pid')) && !fs.existsSync(path.join(data, 'mysql.sock'))
        && !active.some(directory => directory === data || directory.startsWith(data + path.sep)), 'ACTIVE_FIXTURE');
      requireValue(!protectedNative.has(data), 'PROTECTED_EVIDENCE'); add(data, 'stopped-native-test-data');
    } catch (error) { skipped.push({ relative: path.relative(root, data), reason: error.message }); }
  }
  for (const value of directories) {
    if (protectedRuns.has(value.name)) continue;
    const files = [path.join(value.file, 'application.jar')];
    const artifacts = path.join(value.file, 'completed-artifacts'), target = path.join(artifacts, 'backend-target');
    if (privateNode(artifacts, true) && privateNode(target, true)) {
      for (const name of ['defense-assessment-system-0.0.1-SNAPSHOT.jar', 'defense-assessment-system-0.0.1-SNAPSHOT.jar.original']) files.push(path.join(target, name));
    }
    for (const file of files) {
      if (!privateNode(file) || now - fs.statSync(file).mtimeMs < MIN_AGE_MS) continue;
      add(file, 'old-successful-application-package');
    }
  }
  targets.sort((a, b) => a.relative.localeCompare(b.relative));
  return { root, targets, skipped, allocatedBytes: targets.reduce((sum, value) => sum + value.allocatedBytes, 0) };
}
export function createCleanupPlan(root, dependencies = defaults) {
  const value = collect(root, { ...defaults, ...dependencies });
  const plan = Object.freeze({ root, targets: Object.freeze(value.targets.map(target => Object.freeze({ relative: target.relative, kind: target.kind, allocatedBytes: target.allocatedBytes }))),
    skipped: Object.freeze(value.skipped), allocatedBytes: value.allocatedBytes });
  issued.set(plan, value); return plan;
}
export function applyCleanupPlan(plan, dependencies = defaults) {
  const original = issued.get(plan); requireValue(original, 'UNISSUED_CLEANUP_PLAN');
  const release = acquireLock(original.root);
  try {
    issued.delete(plan);
    const current = collect(original.root, { ...defaults, ...dependencies });
    requireValue(JSON.stringify(current.targets) === JSON.stringify(original.targets), 'CLEANUP_PLAN_CHANGED');
    if (current.targets.length === 0) return { removed: [], allocatedBytes: 0, report: null };
    const operation = fs.mkdtempSync(path.join(checkedRoot(original.root), 'test-cleanup-')); fs.chmodSync(operation, 0o700);
    const report = path.join(operation, 'report.json'), removed = [];
    const publish = status => fs.writeFileSync(report, JSON.stringify({ scope: 'local-test-artifacts-only', status, irreversible: true,
      removed, planned: plan.targets, allocatedBytes: removed.reduce((sum, value) => sum + value.allocatedBytes, 0),
      businessDataCleanup: false, finalQA: false }) + '\n', { flag: fs.existsSync(report) ? 'w' : 'wx', mode: 0o600 });
    publish('IN_PROGRESS');
    try {
      for (const [index, target] of current.targets.entries()) {
        const source = path.join(original.root, target.relative), quarantine = path.join(operation, 'discard-' + index);
        requireValue(JSON.stringify(inventory(source)) === JSON.stringify({ entries: target.entries, allocatedBytes: target.allocatedBytes }), 'CLEANUP_PLAN_CHANGED');
        fs.renameSync(source, quarantine);
        // Renaming freezes the exact selected root; never recursively remove an
        // unresolved caller path, runtime root, repository or business database.
        requireValue(JSON.stringify(inventory(quarantine)) === JSON.stringify({ entries: target.entries, allocatedBytes: target.allocatedBytes }), 'CLEANUP_QUARANTINE_CHANGED');
        fs.rmSync(quarantine, { recursive: target.entries[0].directory, force: false });
        removed.push({ relative: target.relative, kind: target.kind, allocatedBytes: target.allocatedBytes }); publish('IN_PROGRESS');
      }
      publish('COMPLETE'); return { removed, allocatedBytes: removed.reduce((sum, value) => sum + value.allocatedBytes, 0), report };
    } catch (error) { publish('INCOMPLETE_QUARANTINE_RETAINED'); throw error; }
  } finally { release(); }
}
export function cleanupCli(argv, io = { out: value => process.stdout.write(value), err: value => process.stderr.write(value) }) {
  try {
    requireValue(argv.length === 2 || argv.length === 3, 'USAGE: --root ABSOLUTE_NEW_PROJECT [--apply]');
    requireValue(argv[0] === '--root' && (argv.length === 2 || argv[2] === '--apply'), 'USAGE: --root ABSOLUTE_NEW_PROJECT [--apply]');
    const plan = createCleanupPlan(argv[1]);
    io.out(JSON.stringify(argv.length === 3 ? { mode: 'applied', ...applyCleanupPlan(plan) } : { mode: 'dry-run', ...plan }) + '\n'); return 0;
  } catch (error) { io.err(error.message + '\n'); return 1; }
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = cleanupCli(process.argv.slice(2));
