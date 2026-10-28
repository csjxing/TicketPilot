import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { validateReport, EVENT_CODES, EVENT_TAGS } from '../validation.mjs';

const NOW = Date.UTC(2026, 9, 28, 1);
const additions = ['SALE_WAITING', 'SALE_OPENING', 'SALE_MONITORING', 'SALE_STOPPED', 'SALE_RESERVATION_REENTRY',
  'QUANTITY_INCREASE_DISPATCHED', 'QUANTITY_TARGET_UNVERIFIED', 'QUANTITY_TRANSITION_PAGE_CHANGED',
  'QUANTITY_TRANSITION_UNVERIFIED', 'QUANTITY_INPUT_UNCONFIRMED', 'DETAIL_SCROLL_PROPOSED',
  'DETAIL_SCROLL_COMMAND_COMPLETED', 'DETAIL_SCROLL_ANCHOR_UNVERIFIED', 'DETAIL_SCROLL_INPUT_UNCONFIRMED'];
function report() {
  return { schema: 1, reportId: randomUUID(), installId: randomUUID(), sequence: 1,
    createdAt: new Date(NOW).toISOString(), appVersion: { name: '0.2.8-preview', code: 24 }, damaiVersion: null,
    androidApi: 36, readiness: { a11y: 'READY', notifications: true, shizuku: true },
    runtime: { armed: false, mode: 'PREPARE', state: 'UNKNOWN' },
    events: [{ at: NOW, tag: 'USER', code: 'MANUAL_REPORT' }] };
}
test('existing schema 1 diagnostic records stay compatible', () => {
  const old = report(); old.appVersion = { name: '0.2.4-preview', code: 20 };
  const restored = validateReport(old, NOW);
  assert.equal(restored.schema, 1); assert.equal(restored.events[0].code, 'MANUAL_REPORT');
  assert.deepEqual(Object.keys(restored).sort(), Object.keys(old).sort());
});
test('timing quantity and detail additions remain fixed events without asserting inventory or an order', () => {
  const value = report();
  value.events = additions.map(code => ({ at: NOW, tag: code.startsWith('SALE_') ? 'MONITOR' : 'INPUT', code }));
  const checked = validateReport(value, NOW);
  assert.equal(checked.runtime.state, 'UNKNOWN'); assert.equal(checked.runtime.armed, false);
  assert.deepEqual(checked.events.map(row => row.code), additions);
  for (const event of checked.events) assert.deepEqual(Object.keys(event).sort(), ['at', 'code', 'tag']);
});
test('new code prefixes cannot carry raw details and no timing quantity or detail text fields are accepted', () => {
  for (const code of additions) {
    const changed = report(); changed.events[0].code = code + ':private';
    assert.throws(() => validateReport(changed, NOW));
  }
  for (const code of ['SALE_OPENING', 'DETAIL_SCROLL_COMMAND_COMPLETED']) {
    for (const field of ['phase', 'quantity', 'rawLog', 'viewer', 'url', 'pageText', 'bounds']) {
      const changed = report(); changed.events[0].code = code; changed.events[0][field] = 'synthetic private text';
      assert.throws(() => validateReport(changed, NOW));
    }
  }
});
test('Java and private receiver share the exact closed code and tag sets', async () => {
  const java = await readFile(new URL('../../android-app/app/src/main/java/cn/local/tickethelper/sync/DiagnosticReport.java', import.meta.url), 'utf8');
  for (const [name, values] of [['Code', EVENT_CODES], ['Tag', EVENT_TAGS]]) {
    const match = java.match(new RegExp('public enum ' + name + '\\s*\\{([^}]+)\\}'));
    assert.ok(match, 'Missing Java enum');
    const javaValues = match[1].split(',').map(value => value.trim()).filter(Boolean);
    assert.deepEqual([...new Set(javaValues)].sort(), [...values].sort());
  }
});
