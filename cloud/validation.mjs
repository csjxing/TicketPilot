// Protocol values are closed sets: raw logs, screen text, identities and model keys have no field.
export const MAX_BODY_BYTES = 32 * 1024;
export const RETENTION_MS = 7 * 24 * 60 * 60 * 1000;
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export const STATES = new Set(['IDLE', 'STOPPED', 'CONFIG_INVALID', 'EXPIRED', 'BLOCKED', 'UNRELATED', 'UNKNOWN', 'READY', 'PROBING', 'OUT_OF_STOCK', 'IN_STOCK', 'TIER_SELECTED', 'CHECKOUT', 'PENDING_PAYMENT_VERIFIED', 'NEEDS_REVIEW']);
export const EVENT_TAGS = new Set(['SERVICE', 'MONITOR', 'CATALOG', 'INPUT', 'CHECKOUT', 'UPDATE', 'SYNC', 'USER']);
export const EVENT_CODES = new Set(['MANUAL_REPORT', 'SESSION_STARTED', 'SESSION_STOPPED', 'CONNECTED', 'DISCONNECTED', 'PERMISSION_REQUIRED', 'FOREGROUND_CHANGED', 'RESERVATION_ONLY', 'IN_STOCK', 'OUT_OF_STOCK', 'TIER_SELECTED', 'CHECKOUT_LOADED', 'ORDER_PENDING_VERIFIED', 'ORDER_OUTCOME_UNKNOWN', 'READ_FAILED', 'READ_RETRY', 'READ_RECOVERED', 'INPUT_FAILED', 'TIMEOUT', 'CANCELLED', 'UPDATE_AVAILABLE', 'UPDATE_INSTALLED', 'CONFIG_INVALID', 'EXPIRED', 'BLOCKED', 'UNKNOWN']);
export class ProtocolError extends Error {
  constructor(code, status = 400) { super(code); this.code = code; this.status = status; }
}
export function fail(code = 'invalid_payload', status = 400) { throw new ProtocolError(code, status); }
export function objectKeys(value, expected) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail();
  const keys = Object.keys(value).sort();
  if (keys.join('\0') !== [...expected].sort().join('\0')) fail();
}
export function requireUuid(value) { if (typeof value !== 'string' || !UUID.test(value)) fail(); return value.toLowerCase(); }
function integer(value, min, max) { if (!Number.isSafeInteger(value) || value < min || value > max) fail(); return value; }
function member(value, set) { if (!set.has(value)) fail(); return value; }
function boolean(value) { if (typeof value !== 'boolean') fail(); return value; }
function version(value) {
  objectKeys(value, ['name', 'code']);
  if (typeof value.name !== 'string' || !/^[0-9][A-Za-z0-9._+-]{0,63}$/.test(value.name)) fail();
  return { name: value.name, code: integer(value.code, 0, 2147483647) };
}
function instant(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value)) fail();
  const time = Date.parse(value);
  if (!Number.isFinite(time) || time < Date.UTC(2020, 0, 1) || time > Date.UTC(2100, 0, 1)) fail();
  return time;
}
export function validateReport(value, now = Date.now()) {
  objectKeys(value, ['schema', 'reportId', 'installId', 'sequence', 'createdAt', 'appVersion', 'damaiVersion', 'androidApi', 'readiness', 'runtime', 'events']);
  if (value.schema !== 1) fail('unsupported_schema');
  const at = instant(value.createdAt);
  if (at > now + 5 * 60 * 1000 || at < now - RETENTION_MS) fail('clock_out_of_range', 422);
  objectKeys(value.readiness, ['a11y', 'notifications', 'shizuku']);
  objectKeys(value.runtime, ['armed', 'mode', 'state']);
  if (!Array.isArray(value.events) || value.events.length > 64) fail();
  const events = value.events.map(event => {
    objectKeys(event, ['at', 'tag', 'code']);
    return { at: integer(event.at, Date.UTC(2020, 0, 1), Date.UTC(2100, 0, 1)), tag: member(event.tag, EVENT_TAGS), code: member(event.code, EVENT_CODES) };
  });
  // Return a freshly constructed allowlisted object; never persist the untrusted input.
  return {
    schema: 1, reportId: requireUuid(value.reportId), installId: requireUuid(value.installId),
    sequence: integer(value.sequence, 1, Number.MAX_SAFE_INTEGER), createdAt: new Date(at).toISOString(),
    appVersion: version(value.appVersion), damaiVersion: value.damaiVersion === null ? null : version(value.damaiVersion),
    androidApi: integer(value.androidApi, 30, 99),
    readiness: { a11y: member(value.readiness.a11y, new Set(['OFF', 'ENABLED_DISCONNECTED', 'READY', 'UNKNOWN'])), notifications: boolean(value.readiness.notifications), shizuku: boolean(value.readiness.shizuku) },
    runtime: { armed: boolean(value.runtime.armed), mode: member(value.runtime.mode, new Set(['OFF', 'ALERT', 'PREPARE', 'LOCK_ORDER'])), state: member(value.runtime.state, STATES) }, events
  };
}
export function validatePair(value) {
  objectKeys(value, ['pairingCode', 'installId']);
  if (typeof value.pairingCode !== 'string' || !/^[A-Za-z0-9_-]{22}$/.test(value.pairingCode)) fail();
  return { pairingCode: value.pairingCode, installId: requireUuid(value.installId) };
}
