import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { parseCommand, receiver, runOperator } from '../operator.mjs';
const SECRET = 'synthetic_test_operator_secret_0123456789_ABCDEFGHI';
function response(value, status = 200) { return new Response(JSON.stringify(value), { status }); }
async function location(t) {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'ticketpilot-follow-'));
  t.after(() => rm(directory, { recursive: true, force: true })); return path.join(directory, 'reports.private.json');
}
test('CLI allows HTTPS receiver path prefix but forbids credentials, query, custom ports and plaintext', () => {
  assert.equal(receiver('https://example.com/api/'), 'https://example.com/api');
  for (const value of ['http://example.com', 'https://user:secret@example.com', 'https://example.com/?secret=x', 'https://example.com:8443', 'https://example.com./']) {
    assert.throws(() => receiver(value));
  }
  assert.throws(() => parseCommand(['pair', '--follow', '--out', '/private/test.json']));
  assert.throws(() => parseCommand(['reports', '--follow', '--follow', '--out', '/private/test.json']));
  assert.throws(() => parseCommand(['reports', '-1', '--out', '/private/test.json']));
  assert.throws(() => parseCommand(['reports', '--out', 'relative.json']));
});
test('follow keeps authenticated requests scoped to receiver and advances only persisted cursor', async t => {
  const outputPath = await location(t); const controller = new AbortController(); const calls = []; let step = 0;
  await runOperator(parseCommand(['reports', '0', '--follow', '--out', outputPath]), { endpoint: 'https://example.com/api', secret: SECRET, signal: controller.signal,
    fetchImpl: async (url, options) => { calls.push({ url, options }); return response(step++ === 0 ? { schema: 1, reports: [{ cursor: 1, report: { synthetic: true } }], nextCursor: 1, oldestAvailableCursor: 1, gap: false } : { schema: 1, reports: [], nextCursor: 1, oldestAvailableCursor: 1, gap: false }); },
    wait: async () => { if (calls.length === 2) controller.abort(); }
  });
  assert.equal(calls.length, 2); assert.equal(calls[0].url, 'https://example.com/api/v1/diagnostics?limit=100&after=0'); assert.equal(calls[1].url, 'https://example.com/api/v1/diagnostics?limit=100&after=1');
  assert.equal(calls[0].options.redirect, 'error'); assert.equal(calls[0].options.headers.Authorization, `Bearer ${SECRET}`);
  const lines = (await readFile(outputPath, 'utf8')).trim().split('\n'); assert.equal(lines.length, 1); assert.equal(JSON.parse(lines[0]).nextCursor, 1); assert.equal((await stat(outputPath)).mode & 0o777, 0o600);
});
test('follow retries transient server failure with same cursor, but stops on authorization loss', async t => {
  const outputPath = await location(t); const cursors = []; const delays = []; let count = 0;
  await assert.rejects(runOperator(parseCommand(['reports', '7', '--follow', '--out', outputPath]), { endpoint: 'https://example.com', secret: SECRET,
    fetchImpl: async url => { cursors.push(url); return response({ schema: 1, error: 'synthetic' }, ++count === 1 ? 503 : 401); }, wait: async ms => { delays.push(ms); }
  }), /request_failed/);
  assert.equal(cursors.length, 2); assert.equal(cursors[0], cursors[1]); assert.deepEqual(delays, [20000]); assert.equal(await readFile(outputPath, 'utf8'), '');
});
test('private CLI never overwrites output or generates pairing when output already exists', async t => {
  const outputPath = await location(t); await writeFile(outputPath, 'existing-private-evidence'); let requested = false;
  await assert.rejects(runOperator(parseCommand(['pair', '--out', outputPath]), { endpoint: 'https://example.com', secret: SECRET, fetchImpl: async () => { requested = true; return response({ schema: 1 }); } }));
  assert.equal(requested, false); assert.equal(await readFile(outputPath, 'utf8'), 'existing-private-evidence');
});
test('follow rejects rollback or invented cursors without writing a success batch', async t => {
  const outputPath = await location(t);
  await assert.rejects(runOperator(parseCommand(['reports', '7', '--follow', '--out', outputPath]), { endpoint: 'https://example.com', secret: SECRET,
    fetchImpl: async () => response({ schema: 1, reports: [], nextCursor: 8, oldestAvailableCursor: 1, gap: false })
  }), /invalid_cursor/); assert.equal(await readFile(outputPath, 'utf8'), '');
});

test('follow retries transport and truncated-body failures without advancing cursor', async t => {
  const outputPath = await location(t); const controller = new AbortController(); const cursors = []; let count = 0;
  await runOperator(parseCommand(['reports', '3', '--follow', '--out', outputPath]), { endpoint: 'https://example.com', secret: SECRET, signal: controller.signal,
    fetchImpl: async url => {
      cursors.push(url); count++;
      if (count === 1) throw new TypeError('synthetic transport failure');
      if (count === 2) return { ok: true, body: { async *[Symbol.asyncIterator]() { yield Buffer.from('{'); throw new TypeError('synthetic truncated response'); } } };
      return response({ schema: 1, reports: [], nextCursor: 3, oldestAvailableCursor: 1, gap: false });
    }, wait: async () => { if (count === 3) controller.abort(); }
  });
  assert.equal(cursors.length, 3); assert.ok(cursors.every(url => url === cursors[0]));
  assert.equal(JSON.parse(await readFile(outputPath, 'utf8')).nextCursor, 3);
});
