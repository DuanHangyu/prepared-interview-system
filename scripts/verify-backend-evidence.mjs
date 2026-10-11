#!/usr/bin/env node
// Read-only completeness/consistency check, not a test runner or final QA.
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';

const PREFIX = 'system/assessment/defense/prepared/';
const PACKAGES = ['paper', 'jobs', 'generation', 'publication', 'media', 'speech', 'storage', 'audio', 'attempt', 'processing', 'bootstrap', 'retention'].map(x => PREFIX + x);
// The verifier and its own separately tested fixture are not application inputs.
const SELF_FILES = new Set(['scripts/verify-backend-evidence.mjs', 'scripts/tests/backend-evidence.test.mjs']);
const SMALL_LIMIT = 16 * 1024 * 1024;
const LARGE_LIMIT = 128 * 1024 * 1024;

class EvidenceError extends Error {
  constructor(code) { super(code); this.code = code; }
}

function requireEvidence(value, code) { if (!value) throw new EvidenceError(code); }

function projectRoot(value) {
  requireEvidence(typeof value === 'string' && value.length > 0, 'INVALID_ARGUMENTS');
  const root = path.resolve(value);
  requireEvidence(path.basename(root) === 'prepared-interview-system' && fs.realpathSync(root) === root, 'UNSAFE_EVIDENCE_PATH');
  return root;
}

function safeStat(root, relative) {
  const file = path.join(root, relative);
  requireEvidence(file.startsWith(root + path.sep), 'UNSAFE_EVIDENCE_PATH');
  let current = root;
  for (const part of relative.split('/')) {
    requireEvidence(part && part !== '..' && part !== '.', 'UNSAFE_EVIDENCE_PATH');
    current = path.join(current, part);
    requireEvidence(!fs.lstatSync(current).isSymbolicLink(), 'UNSAFE_EVIDENCE_PATH');
  }
  return fs.statSync(file);
}

function readRegular(root, relative, limit = SMALL_LIMIT) {
  const before = safeStat(root, relative);
  requireEvidence(before.isFile() && before.size <= limit, 'EVIDENCE_FILE_INVALID');
  const fd = fs.openSync(path.join(root, relative), fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
  try {
    const opened = fs.fstatSync(fd);
    requireEvidence(opened.ino === before.ino && opened.dev === before.dev && opened.size === before.size, 'EVIDENCE_CHANGED');
    const bytes = fs.readFileSync(fd);
    const after = fs.fstatSync(fd);
    requireEvidence(after.size === before.size && after.mtimeMs === before.mtimeMs && bytes.length === before.size, 'EVIDENCE_CHANGED');
    return { bytes, stat: before };
  } finally { fs.closeSync(fd); }
}

function sourceFiles(root, base = 'defense-assessment/src') {
  const files = [];
  function visit(relative) {
    const stat = safeStat(root, relative);
    if (stat.isDirectory()) {
      for (const name of fs.readdirSync(path.join(root, relative)).sort()) visit(relative + '/' + name);
    } else {
      requireEvidence(stat.isFile() && files.length < 10000, 'EVIDENCE_FILE_INVALID');
      files.push(relative);
    }
  }
  visit(base);
  return files;
}

export function sourceFingerprint(project) {
  const root = projectRoot(project);
  requireEvidence(safeStat(root, 'defense-system').isDirectory(), 'UNSAFE_EVIDENCE_PATH');
  const clientConfigs = fs.readdirSync(path.join(root, 'defense-system'))
    .filter(x => /^(?:(?:vite|vitest|playwright|postcss|tailwind|eslint)\.config\.[cm]?[jt]s|tsconfig(?:\.[\w-]+)?\.json)$/.test(x))
    .map(x => 'defense-system/' + x);
  const files = [...['defense-assessment/src', 'defense-system/src', 'defense-system/tests', 'defense-system/scripts', 'scripts'].flatMap(x => sourceFiles(root, x)),
    'defense-assessment/pom.xml', 'defense-assessment/mvnw', 'defense-assessment/.mvn/wrapper/maven-wrapper.properties',
    'defense-system/package.json', 'defense-system/package-lock.json', ...clientConfigs]
    .filter(x => !SELF_FILES.has(x)).sort();
  const hash = createHash('sha256');
  for (const file of files) { hash.update(file); hash.update('\0'); hash.update(readRegular(root, file).bytes); hash.update('\0'); }
  return hash.digest('hex');
}

function testInventory(root) {
  const suites = new Set();
  for (const file of sourceFiles(root).filter(x => x.startsWith('defense-assessment/src/test/java/') && x.endsWith('.java'))) {
    // Ignore Java comments, strings, text blocks and character literals. This
    // deliberately fails closed for unsupported discovery conventions.
    const code = readRegular(root, file).bytes.toString('utf8').replace(/\/\*[\s\S]*?\*\/|\/\/[^\r\n]*|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'/g, ' ');
    if (!/@(?:[\w$]+\.)*(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b/.test(code)) continue;
    const pkg = code.match(/\bpackage\s+([\w.]+)\s*;/);
    requireEvidence(pkg, 'TEST_DISCOVERY_INVALID');
    const name = pkg[1] + '.' + path.basename(file, '.java');
    requireEvidence(!suites.has(name), 'TEST_DISCOVERY_INVALID');
    suites.add(name);
  }
  requireEvidence(suites.size > 0, 'TEST_SCOPE_INCOMPLETE');
  return suites;
}

function attributes(value, code) {
  const result = new Map();
  const pattern = /([A-Za-z_:][A-Za-z0-9_:.-]*)\s*=\s*(["'])([^"']*)\2/g;
  const matches = [...value.matchAll(pattern)];
  requireEvidence(value.replace(pattern, '').trim() === '', code);
  for (const match of matches) {
    requireEvidence(!result.has(match[1]), code); result.set(match[1], match[3]);
  }
  return result;
}

function integer(value, code) {
  requireEvidence(typeof value === 'string' && /^(0|[1-9][0-9]*)$/.test(value), code);
  const number = Number(value);
  requireEvidence(Number.isSafeInteger(number) && number <= 1000000000, code);
  return number;
}

function suiteResult(bytes, name) {
  const xml = bytes.toString('utf8');
  const match = xml.match(/^\s*(?:<\?xml[^>]*\?>\s*)?<testsuite\b([^>]*)>/);
  requireEvidence(match && xml.trimEnd().endsWith('</testsuite>'), 'REPORT_METADATA_INVALID');
  const attrs = attributes(match[1], 'REPORT_METADATA_INVALID');
  requireEvidence(attrs.get('name') === name, 'REPORT_IDENTITY_INVALID');
  const result = {};
  for (const field of ['tests', 'failures', 'errors', 'skipped']) result[field] = integer(attrs.get(field), 'REPORT_METADATA_INVALID');
  requireEvidence(result.tests > 0 && result.failures === 0 && result.errors === 0 && result.skipped === 0, 'TEST_RESULTS_NOT_PASSING');
  return result;
}

function fresh(stat, since, code) { requireEvidence(stat.mtimeMs >= since && stat.mtimeMs <= Date.now() + 1000, code); }

function executionSessions(bytes, since) {
  // JaCoCo 0.8.15 documented binary format; names are opaque modified-UTF
  // fields, never decoded or printed. Only timing and bounded structure matter.
  let offset = 0, sessions = 0, classes = 0;
  requireEvidence(bytes.length > 0 && bytes[0] === 1, 'COVERAGE_FORMAT_INVALID');
  function take(n) {
    requireEvidence(Number.isSafeInteger(n) && n >= 0 && n <= bytes.length - offset, 'COVERAGE_FORMAT_INVALID');
    const result = bytes.subarray(offset, offset + n); offset += n; return result;
  }
  const byte = () => take(1)[0];
  const short = () => take(2).readUInt16BE();
  const opaqueName = () => take(short());
  const timestamp = () => {
    const value = Number(take(8).readBigInt64BE());
    requireEvidence(Number.isSafeInteger(value) && value >= 0, 'COVERAGE_TIME_INVALID'); return value;
  };
  function probes() {
    let count = 0;
    for (let shift = 0; shift <= 28; shift += 7) {
      const value = byte(); count += (value & 127) * 2 ** shift;
      requireEvidence(count <= 10000000, 'COVERAGE_FORMAT_INVALID');
      if (!(value & 128)) return take(Math.ceil(count / 8));
    }
    throw new EvidenceError('COVERAGE_FORMAT_INVALID');
  }
  while (offset < bytes.length) {
    const type = byte();
    if (type === 1) {
      requireEvidence(short() === 0xc0c0 && short() === 0x1007, 'COVERAGE_FORMAT_INVALID');
    } else if (type === 0x10) {
      opaqueName(); const start = timestamp(), dump = timestamp();
      requireEvidence(dump >= start && dump <= Date.now() + 1000, 'COVERAGE_TIME_INVALID');
      requireEvidence(start >= since, 'STALE_COVERAGE_SESSION'); sessions++;
    } else if (type === 0x11) {
      take(8); opaqueName(); probes(); classes++;
    } else throw new EvidenceError('COVERAGE_FORMAT_INVALID');
  }
  requireEvidence(sessions > 0 && classes > 0, 'COVERAGE_SESSION_INVALID');
  return sessions;
}

function coveragePackages(bytes) {
  const xml = bytes.toString('utf8');
  requireEvidence(/<report\b[^>]*>/.test(xml) && xml.trimEnd().endsWith('</report>') && !xml.includes('<!ENTITY'), 'COVERAGE_METADATA_INVALID');
  const found = new Map();
  for (const match of xml.matchAll(/<package\b([^>]*)>([\s\S]*?)<\/package>/g)) {
    const name = attributes(match[1], 'COVERAGE_METADATA_INVALID').get('name');
    requireEvidence(PACKAGES.includes(name) && !found.has(name), 'COVERAGE_SCOPE_INCOMPLETE');
    const tail = match[2].replace(/<(?:class|sourcefile)\b[^>]*\/>/g, '')
      .replace(/<class\b[\s\S]*?<\/class>/g, '').replace(/<sourcefile\b[\s\S]*?<\/sourcefile>/g, '');
    const counters = new Map(), tags = [...tail.matchAll(/<counter\b([^>]*)\/>/g)];
    requireEvidence(tail.replace(/<counter\b[^>]*\/>/g, '').trim() === '', 'COVERAGE_METADATA_INVALID');
    for (const tag of tags) {
      const attrs = attributes(tag[1], 'COVERAGE_METADATA_INVALID'), type = attrs.get('type');
      requireEvidence(['INSTRUCTION','BRANCH','LINE','COMPLEXITY','METHOD','CLASS'].includes(type) && !counters.has(type), 'COVERAGE_METADATA_INVALID');
      counters.set(type, { missed: integer(attrs.get('missed'), 'COVERAGE_METADATA_INVALID'), covered: integer(attrs.get('covered'), 'COVERAGE_METADATA_INVALID') });
    }
    const result = { name };
    for (const type of ['LINE', 'BRANCH']) {
      const count = counters.get(type);
      requireEvidence(count && count.covered + count.missed > 0, 'COVERAGE_METADATA_INVALID');
      requireEvidence(count.covered / (count.covered + count.missed) >= 0.8, 'COVERAGE_GATE_FAILED');
      result[type.toLowerCase()] = count.covered / (count.covered + count.missed);
    }
    found.set(name, result);
  }
  requireEvidence(found.size === PACKAGES.length, 'COVERAGE_SCOPE_INCOMPLETE');
  return [...found.values()];
}

export function verifyEvidence({ root: project, since, sourceSha256, mavenExitCode }) {
  requireEvidence(Number.isSafeInteger(since) && since > 0 && since <= Date.now() && typeof sourceSha256 === 'string' && /^[a-f0-9]{64}$/i.test(sourceSha256), 'INVALID_ARGUMENTS');
  requireEvidence(mavenExitCode === 0, 'MAVEN_NOT_SUCCESSFUL');
  const root = projectRoot(project), expectedHash = sourceSha256.toLowerCase();
  requireEvidence(sourceFingerprint(root) === expectedHash, 'SOURCE_CHANGED');
  const expected = testInventory(root), actual = new Set(); let tests = 0;
  const directory = 'defense-assessment/target/surefire-reports';
  requireEvidence(safeStat(root, directory).isDirectory(), 'UNSAFE_EVIDENCE_PATH');
  for (const name of fs.readdirSync(path.join(root, directory)).filter(x => /^TEST-.*\.xml$/.test(x))) {
    const suite = name.slice(5, -4);
    requireEvidence(/^[\w.$]+$/.test(suite) && expected.has(suite) && !actual.has(suite), 'TEST_SCOPE_INCOMPLETE');
    const entry = readRegular(root, directory + '/' + name); fresh(entry.stat, since, 'STALE_REPORT');
    tests += suiteResult(entry.bytes, suite).tests; actual.add(suite);
  }
  requireEvidence(actual.size === expected.size, 'TEST_SCOPE_INCOMPLETE');
  const raw = readRegular(root, 'defense-assessment/target/jacoco.exec', LARGE_LIMIT); fresh(raw.stat, since, 'STALE_COVERAGE');
  const coverageSessions = executionSessions(raw.bytes, since);
  const merged = readRegular(root, 'defense-assessment/target/prepared-merged.exec', LARGE_LIMIT); fresh(merged.stat, since, 'STALE_COVERAGE');
  const mergedCoverageSessions = executionSessions(merged.bytes, since);
  let browserCoverageSessions = 0;
  const browserFile = '.runtime/prepared-live.exec';
  if (fs.lstatSync(path.join(root, browserFile), { throwIfNoEntry: false })) {
    const browser = readRegular(root, browserFile, LARGE_LIMIT); fresh(browser.stat, since, 'STALE_COVERAGE');
    browserCoverageSessions = executionSessions(browser.bytes, since);
  }
  const coverage = readRegular(root, 'defense-assessment/target/site/prepared-coverage/jacoco.xml'); fresh(coverage.stat, since, 'STALE_COVERAGE');
  const packages = coveragePackages(coverage.bytes);
  requireEvidence(sourceFingerprint(root) === expectedHash, 'SOURCE_CHANGED');
  return { suites: actual.size, tests, failures: 0, errors: 0, skipped: 0, coverageSessions, mergedCoverageSessions, browserCoverageSessions, packages, sourceSha256: expectedHash };
}

export function runCli(argv, io = { out: x => process.stdout.write(x), err: x => process.stderr.write(x) }) {
  try {
    if (argv.length === 1 && argv[0] === '--help') {
      io.out('Read-only backend evidence check. Capture --fingerprint --root PATH before running tests; then supply --root PATH --since EPOCH_MS --source-sha256 HASH --maven-exit-code CODE. This does not run tests or replace final QA.\n'); return 0;
    }
    const values = new Map(); let fingerprint = false;
    for (let i = 0; i < argv.length; i++) {
      const key = argv[i];
      if (key === '--fingerprint') { requireEvidence(!fingerprint, 'INVALID_ARGUMENTS'); fingerprint = true; continue; }
      requireEvidence(['--root', '--since', '--source-sha256', '--maven-exit-code'].includes(key) && !values.has(key) && i + 1 < argv.length, 'INVALID_ARGUMENTS');
      values.set(key, argv[++i]);
    }
    requireEvidence(values.has('--root') && values.size === (fingerprint ? 1 : 4), 'INVALID_ARGUMENTS');
    const result = fingerprint ? { sourceSha256: sourceFingerprint(values.get('--root')) }
      : verifyEvidence({ root: values.get('--root'), since: Number(values.get('--since')), sourceSha256: values.get('--source-sha256'), mavenExitCode: Number(values.get('--maven-exit-code')) });
    io.out(JSON.stringify(result) + '\n'); return 0;
  } catch (failure) {
    io.err('Backend evidence check failed: ' + (failure instanceof EvidenceError ? failure.code : 'EVIDENCE_UNREADABLE') + '.\n'); return 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) process.exitCode = runCli(process.argv.slice(2));
