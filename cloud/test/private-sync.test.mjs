import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, rm, stat } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { createTicketPilotServer } from '../server.mjs';
import { PrivateStore } from '../store.mjs';
import { validateReport, RETENTION_MS } from '../validation.mjs';
const OPERATOR = 'synthetic_test_operator_secret_0123456789_ABCDEFGHI';
const NOW = Date.UTC(2026, 9, 27, 1);
function report(installId, sequence = 1) {
  return { schema: 1, reportId: randomUUID(), installId, sequence, createdAt: new Date(NOW).toISOString(), appVersion: { name: '0.2.4-preview', code: 20 }, damaiVersion: { name: '9.0.37', code: 9000037 }, androidApi: 36, readiness: { a11y: 'READY', notifications: true, shizuku: false }, runtime: { armed: false, mode: 'PREPARE', state: 'OUT_OF_STOCK' }, events: [{ at: NOW, tag: 'USER', code: 'MANUAL_REPORT' }] };
}
async function fixture(t, requireHttps = false) {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'ticketpilot-sync-test-')); let now = NOW;
  const { server, store } = await createTicketPilotServer({ dataDirectory: directory, operatorSecret: OPERATOR, clock: () => now, requireHttps });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  t.after(async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); await rm(directory, { recursive: true, force: true }); });
  async function request(route, { token = OPERATOR, method = 'GET', body, headers = {} } = {}) {
    const response = await fetch(base + route, { method, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...headers }, body: body === undefined ? undefined : JSON.stringify(body) });
    return { status: response.status, body: await response.json(), headers: response.headers };
  }
  async function pair(installId = randomUUID()) {
    const generated = await request('/v1/pairing', { method: 'POST', body: {} }); assert.equal(generated.status, 201);
    const paired = await request('/v1/pair', { method: 'POST', token: null, body: { pairingCode: generated.body.pairingCode, installId } }); assert.equal(paired.status, 201);
    return { ...paired.body, installId, pairingCode: generated.body.pairingCode };
  }
  return { directory, store, request, pair, now: value => { now = value; } };
}

test('typed diagnostics upload is authenticated, private, persisted and read by cursor', async t => {
  const f = await fixture(t); const p = await f.pair(); const value = report(p.installId);
  const upload = await f.request('/v1/diagnostics', { method: 'POST', token: p.deviceToken, body: value }); assert.equal(upload.status, 200); assert.equal(upload.body.cursor, 1); assert.equal(upload.body.duplicate, false);
  const found = await f.request('/v1/diagnostics?after=0&limit=1'); assert.equal(found.status, 200); assert.deepEqual(found.body.reports[0].report, value); assert.equal(found.body.nextCursor, 1);
  const empty = await f.request('/v1/diagnostics?after=1'); assert.deepEqual(empty.body.reports, []);
  const raw = await readFile(path.join(f.directory, 'private-state.json'), 'utf8'); assert.ok(!raw.includes(p.deviceToken)); assert.ok(!raw.includes(p.pairingCode)); assert.ok(!raw.includes(OPERATOR));
  assert.equal((await stat(f.directory)).mode & 0o777, 0o700); assert.equal((await stat(path.join(f.directory, 'private-state.json'))).mode & 0o777, 0o600);
  const restored = new PrivateStore(f.directory, () => NOW); await restored.init(); assert.equal((await restored.list(0, 10)).reports.length, 1);
});
test('device credential cannot read reports or create pairings; operator cannot upload as device', async t => {
  const f = await fixture(t); const p = await f.pair();
  for (const route of ['/v1/diagnostics', '/v1/devices']) assert.equal((await f.request(route, { token: p.deviceToken })).status, 401);
  assert.equal((await f.request('/v1/pairing', { method: 'POST', token: p.deviceToken, body: {} })).status, 401);
  assert.equal((await f.request('/v1/diagnostics', { method: 'POST', body: report(p.installId) })).status, 401);
  assert.equal((await f.request('/v1/diagnostics', { token: null })).status, 401);
});
test('duplicate delivery is idempotent while changed content or old sequence is rejected', async t => {
  const f = await fixture(t); const p = await f.pair(); const first = report(p.installId);
  const upload = body => f.request('/v1/diagnostics', { method: 'POST', token: p.deviceToken, body });
  assert.equal((await upload(first)).body.duplicate, false); const retry = await upload(first); assert.equal(retry.body.duplicate, true); assert.equal(retry.body.cursor, 1);
  assert.equal((await upload({ ...first, runtime: { ...first.runtime, state: 'UNKNOWN' } })).status, 409);
  assert.equal((await upload(report(p.installId, 2))).status, 200); assert.equal((await upload(first)).status, 409);
  assert.equal((await f.request('/v1/diagnostics')).body.reports.length, 2);
});
test('parallel uploads serialize and cannot produce duplicate cursors', async t => {
  const f = await fixture(t); const p = await f.pair(); const q = await f.pair();
  const results = await Promise.all([p, q].map(d => f.request('/v1/diagnostics', { method: 'POST', token: d.deviceToken, body: report(d.installId) })));
  assert.deepEqual(results.map(x => x.body.cursor).sort(), [1, 2]);
});
test('installation binding rejects another random installation id', async t => {
  const f = await fixture(t); const p = await f.pair();
  assert.equal((await f.request('/v1/diagnostics', { method: 'POST', token: p.deviceToken, body: report(randomUUID()) })).status, 403);
});
test('pair code is single-use and expires in ten minutes', async t => {
  const f = await fixture(t); const p = await f.pair();
  assert.equal((await f.request('/v1/pair', { method: 'POST', token: null, body: { pairingCode: p.pairingCode, installId: randomUUID() } })).status, 401);
  const generated = await f.request('/v1/pairing', { method: 'POST', body: {} }); f.now(NOW + 10 * 60 * 1000 + 1);
  assert.equal((await f.request('/v1/pair', { method: 'POST', token: null, body: { pairingCode: generated.body.pairingCode, installId: randomUUID() } })).status, 401);
});
test('revocation and re-pairing invalidate device tokens; token hashes never leave operator listing', async t => {
  const f = await fixture(t); const p = await f.pair(); const listing = await f.request('/v1/devices');
  assert.ok(!JSON.stringify(listing.body).includes('tokenHash')); assert.ok(!JSON.stringify(listing.body).includes(p.deviceToken));
  assert.equal((await f.request('/v1/device', { method: 'DELETE', body: { deviceId: p.deviceId } })).status, 200);
  assert.equal((await f.request('/v1/diagnostics', { method: 'POST', token: p.deviceToken, body: report(p.installId) })).status, 401);
  const q = await f.pair(p.installId); const r = await f.pair(p.installId); assert.notEqual(q.deviceToken, r.deviceToken);
  assert.equal((await f.request('/v1/diagnostics', { method: 'POST', token: q.deviceToken, body: report(q.installId) })).status, 401);
});
test('device credential expires after ninety days', async t => {
  const f = await fixture(t); const p = await f.pair(); f.now(NOW + 90 * 86400000 + 1);
  assert.equal((await f.request('/v1/diagnostics', { method: 'POST', token: p.deviceToken, body: report(p.installId) })).status, 401);
});
test('report retention removes private diagnostics after seven days and reports cursor gaps', async t => {
  const f = await fixture(t); const p = await f.pair();
  for (const sequence of [1, 2]) await f.request('/v1/diagnostics', { method: 'POST', token: p.deviceToken, body: report(p.installId, sequence) });
  f.now(NOW + RETENTION_MS + 1); const list = await f.request('/v1/diagnostics?after=1'); assert.deepEqual(list.body.reports, []); assert.equal(list.body.gap, true); assert.equal(list.body.oldestAvailableCursor, 3);
  assert.equal((JSON.parse(await readFile(path.join(f.directory, 'private-state.json'), 'utf8'))).reports.length, 0);
});
test('PII/raw-log/arbitrary fields and arbitrary enum values fail closed at every nesting level', async t => {
  const f = await fixture(t); const p = await f.pair();
  const variants = [v => { v.rawLog = 'not accepted'; }, v => { v.runtime.attendee = 'not accepted'; }, v => { v.readiness.screen = 'not accepted'; }, v => { v.events[0].text = 'not accepted'; }, v => { v.events[0].code = 'FREE_FORM'; }, v => { v.events[0].tag = 'ARBITRARY'; }, v => { v.runtime.state = 'REMOTE_CLICK'; }, v => { v.appVersion.name = 'private user data'; }];
  for (const mutate of variants) { const value = report(p.installId); mutate(value); const result = await f.request('/v1/diagnostics', { method: 'POST', token: p.deviceToken, body: value }); assert.equal(result.status, 400); assert.ok(!JSON.stringify(result.body).includes('not accepted')); }
  assert.deepEqual((await f.request('/v1/diagnostics')).body.reports, []);
});
test('event limits, unsupported schema, numbers and timestamp bounds are enforced', () => {
  const p = randomUUID();
  for (const mutate of [v => { v.events = Array(65).fill(v.events[0]); }, v => { v.schema = 2; }, v => { v.sequence = 0; }, v => { v.sequence = Number.MAX_SAFE_INTEGER + 1; }, v => { v.readiness.shizuku = 'true'; }, v => { v.androidApi = 29; }, v => { v.createdAt = new Date(NOW + 6 * 60000).toISOString(); }, v => { v.createdAt = new Date(NOW - RETENTION_MS - 1).toISOString(); }]) {
    const value = report(p); mutate(value); assert.throws(() => validateReport(value, NOW));
  }
  const noVersion = report(p); noVersion.damaiVersion = null; assert.equal(validateReport(noVersion, NOW).damaiVersion, null);
});
test('stale mobile queue receives actionable HTTP422 clock error without losing local/cloud data', async t => {
  const f = await fixture(t); const p = await f.pair(); const value = report(p.installId); value.createdAt = new Date(NOW - RETENTION_MS - 1).toISOString();
  const result = await f.request('/v1/diagnostics', { method: 'POST', token: p.deviceToken, body: value });
  assert.equal(result.status, 422); assert.equal(result.body.error, 'clock_out_of_range'); assert.deepEqual((await f.request('/v1/diagnostics')).body.reports, []);
});
test('HTTPS marker required in production, JSON content and bounded bodies enforced', async t => {
  const f = await fixture(t, true);
  assert.equal((await f.request('/health', { token: null })).status, 426);
  const health = await f.request('/health', { token: null, headers: { 'X-Forwarded-Proto': 'https' } }); assert.equal(health.status, 200); assert.equal(health.headers.get('cache-control'), 'no-store');
  assert.equal((await f.request('/v1/pairing', { method: 'POST', body: {}, headers: { 'X-Forwarded-Proto': 'https', 'Content-Type': 'text/plain' } })).status, 415);
  assert.equal((await f.request('/v1/pairing', { method: 'POST', body: { large: 'a'.repeat(33000) }, headers: { 'X-Forwarded-Proto': 'https' } })).status, 413);
});
test('pair attempts have bounded rate even with invalid code', async t => {
  const f = await fixture(t);
  for (let i = 0; i < 12; i++) assert.equal((await f.request('/v1/pair', { method: 'POST', token: null, body: { pairingCode: 'x'.repeat(22), installId: randomUUID() } })).status, 401);
  assert.equal((await f.request('/v1/pair', { method: 'POST', token: null, body: { pairingCode: 'x'.repeat(22), installId: randomUUID() } })).status, 429);
});
test('bad query, arbitrary command/config endpoint and body fail closed', async t => {
  const f = await fixture(t);
  for (const query of ['after=-1', 'limit=201', 'after=0&after=1', 'token=secret', 'limit=1.5']) assert.equal((await f.request('/v1/diagnostics?' + query)).status, 400);
  for (const route of ['/v1/execute', '/v1/shell', '/v1/adb', '/v1/order', '/v1/config']) assert.equal((await f.request(route, { method: 'POST', body: {} })).status, 404);
  assert.equal((await f.request('/v1/pairing', { method: 'POST', body: { extra: true } })).status, 400);
});
test('corrupt existing store fails without overwriting private evidence', async t => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'ticketpilot-corrupt-')); t.after(() => rm(directory, { recursive: true, force: true }));
  const file = path.join(directory, 'private-state.json'); await writeFile(file, 'corrupt-private-evidence', { mode: 0o600 });
  await assert.rejects(new PrivateStore(directory).init(), /private_store_unavailable/); assert.equal(await readFile(file, 'utf8'), 'corrupt-private-evidence');
});
test('storage error fails closed until restart rather than risk uncertain duplicate writes', async t => {
  const f = await fixture(t); const p = await f.pair(); f.store.persist = async () => { throw new Error('synthetic write failure'); };
  assert.equal((await f.request('/v1/diagnostics', { method: 'POST', token: p.deviceToken, body: report(p.installId) })).status, 503);
  assert.equal((await f.request('/v1/diagnostics', { method: 'POST', token: p.deviceToken, body: report(p.installId) })).status, 503);
});
