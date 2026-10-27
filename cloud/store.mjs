import { createHash, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { mkdir, readFile, rename, open, unlink, chmod } from 'node:fs/promises';
import path from 'node:path';
import { fail, RETENTION_MS } from './validation.mjs';
const PAIR_LIFETIME = 10 * 60 * 1000;
const DEVICE_LIFETIME = 90 * 24 * 60 * 60 * 1000;
const MAX_REPORTS = 2000;
export function hash(value) { return createHash('sha256').update(value).digest('hex'); }
export function matches(value, digest) {
  if (typeof value !== 'string' || value.length > 256 || typeof digest !== 'string' || !/^[0-9a-f]{64}$/.test(digest)) return false;
  return timingSafeEqual(Buffer.from(hash(value), 'hex'), Buffer.from(digest, 'hex'));
}
function freshState() { return { schema: 1, nextCursor: 1, pairings: [], devices: [], reports: [] }; }
function checkStored(state) {
  if (!state || state.schema !== 1 || !Number.isSafeInteger(state.nextCursor) || state.nextCursor < 1 ||
      !Array.isArray(state.pairings) || !Array.isArray(state.devices) || !Array.isArray(state.reports) ||
      state.devices.length > 16 || state.pairings.length > 16 || state.reports.length > MAX_REPORTS) throw new Error('invalid_private_store');
  for (const device of state.devices) if (!device.id || !/^[0-9a-f]{64}$/.test(device.tokenHash)) throw new Error('invalid_private_store');
  return state;
}
export class PrivateStore {
  constructor(directory, clock = Date.now) {
    if (!path.isAbsolute(directory)) throw new Error('absolute_private_data_directory_required');
    this.directory = directory; this.file = path.join(directory, 'private-state.json'); this.clock = clock; this.tail = Promise.resolve();
  }
  async init() {
    await mkdir(this.directory, { recursive: true, mode: 0o700 }); await chmod(this.directory, 0o700);
    try {
      const handle = await open(this.file, 'r');
      try { const stat = await handle.stat(); if (stat.size > 40 * 1024 * 1024) throw new Error('invalid_private_store'); this.state = checkStored(JSON.parse(await handle.readFile('utf8'))); }
      finally { await handle.close(); }
      await chmod(this.file, 0o600);
    } catch (error) { if (error.code !== 'ENOENT') throw new Error('private_store_unavailable'); this.state = freshState(); await this.persist(this.state); }
  }
  async persist(state) {
    const temporary = `${this.file}.${randomBytes(8).toString('hex')}.tmp`;
    let handle;
    try {
      handle = await open(temporary, 'wx', 0o600); await handle.writeFile(JSON.stringify(state)); await handle.sync(); await handle.close(); handle = null;
      await rename(temporary, this.file);
      const directory = await open(this.directory, 'r'); try { await directory.sync(); } finally { await directory.close(); }
    } finally { if (handle) await handle.close(); await unlink(temporary).catch(() => {}); }
  }
  transaction(operation) {
    const task = this.tail.then(async () => {
      if (this.failed) throw new Error('private_store_unavailable');
      const state = structuredClone(this.state); const now = this.clock();
      state.pairings = state.pairings.filter(pair => pair.expiresAt > now);
      state.reports = state.reports.filter(report => report.receivedAt > now - RETENTION_MS);
      const result = operation(state, now);
      try { await this.persist(state); } catch { this.failed = true; throw new Error('private_store_unavailable'); }
      this.state = state; return result;
    });
    this.tail = task.catch(() => {}); return task;
  }
  pairing() {
    return this.transaction((state, now) => {
      if (state.pairings.length >= 8) fail('too_many_pairings', 429);
      const code = randomBytes(16).toString('base64url'); const expiresAt = now + PAIR_LIFETIME;
      state.pairings.push({ hash: hash(code), expiresAt });
      return { schema: 1, pairingCode: code, expiresAt: new Date(expiresAt).toISOString() };
    });
  }
  pair(pairingCode, installId) {
    return this.transaction((state, now) => {
      const index = state.pairings.findIndex(pair => matches(pairingCode, pair.hash));
      if (index < 0) fail('pairing_invalid_or_expired', 401);
      const activeDevices = state.devices.filter(device => !device.revoked && device.expiresAt > now && device.installId !== installId);
      if (activeDevices.length >= 8) fail('too_many_devices', 409);
      // Re-pairing this installation revokes its prior token, and sequence begins fresh for the new token.
      state.devices = state.devices.filter(device => device.installId !== installId && !device.revoked && device.expiresAt > now);
      state.pairings.splice(index, 1);
      const token = randomBytes(32).toString('base64url'); const expiresAt = now + DEVICE_LIFETIME; const id = randomUUID();
      state.devices.push({ id, installId, tokenHash: hash(token), createdAt: now, expiresAt, revoked: false, lastSequence: 0, lastHash: '', lastCursor: 0, lastSeenAt: null });
      return { schema: 1, deviceId: id, deviceToken: token, expiresAt: new Date(expiresAt).toISOString() };
    });
  }
  authenticate(token) {
    if (this.failed) throw new Error('private_store_unavailable');
    const now = this.clock();
    const device = this.state.devices.find(item => matches(token, item.tokenHash));
    if (!device || device.revoked || device.expiresAt <= now) fail('unauthorized', 401);
    return { id: device.id, installId: device.installId };
  }
  upload(deviceId, report) {
    return this.transaction((state, now) => {
      const device = state.devices.find(item => item.id === deviceId && !item.revoked && item.expiresAt > now);
      if (!device) fail('unauthorized', 401);
      if (report.installId !== device.installId) fail('installation_mismatch', 403);
      const reportHash = hash(JSON.stringify(report));
      if (report.sequence === device.lastSequence) {
        if (reportHash !== device.lastHash) fail('sequence_conflict', 409);
        return { schema: 1, accepted: true, duplicate: true, cursor: device.lastCursor, reportId: report.reportId };
      }
      if (report.sequence < device.lastSequence || state.reports.some(item => item.report.reportId === report.reportId)) fail('sequence_conflict', 409);
      const cursor = state.nextCursor++;
      state.reports.push({ cursor, deviceId, receivedAt: now, report });
      state.reports = state.reports.slice(-MAX_REPORTS);
      device.lastSequence = report.sequence; device.lastHash = reportHash; device.lastCursor = cursor; device.lastSeenAt = now;
      return { schema: 1, accepted: true, duplicate: false, cursor, reportId: report.reportId };
    });
  }
  list(after, limit) {
    return this.transaction((state) => {
      const reports = state.reports.filter(item => item.cursor > after).slice(0, limit).map(item => ({ cursor: item.cursor, deviceId: item.deviceId, receivedAt: new Date(item.receivedAt).toISOString(), report: item.report }));
      const oldestCursor = state.reports[0]?.cursor ?? state.nextCursor;
      return { schema: 1, reports, nextCursor: reports.at(-1)?.cursor ?? after, oldestAvailableCursor: oldestCursor, gap: after > 0 && after < oldestCursor - 1 };
    });
  }
  devices() {
    return this.transaction((state, now) => ({ schema: 1, devices: state.devices.map(device => ({ deviceId: device.id, installId: device.installId, createdAt: new Date(device.createdAt).toISOString(), expiresAt: new Date(device.expiresAt).toISOString(), revoked: device.revoked, expired: device.expiresAt <= now, lastSequence: device.lastSequence, lastSeenAt: device.lastSeenAt === null ? null : new Date(device.lastSeenAt).toISOString() })) }));
  }
  revoke(id) {
    return this.transaction(state => { const device = state.devices.find(item => item.id === id); if (!device) fail('device_not_found', 404); device.revoked = true; return { schema: 1, revoked: true }; });
  }
}
