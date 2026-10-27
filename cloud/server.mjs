import http from 'node:http';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { PrivateStore, hash, matches } from './store.mjs';
import { MAX_BODY_BYTES, ProtocolError, fail, objectKeys, requireUuid, validatePair, validateReport } from './validation.mjs';

class Limiter {
  constructor(clock) { this.clock = clock; this.entries = new Map(); }
  take(key, limit) {
    const now = this.clock();
    for (const [id, value] of this.entries) if (value.until <= now) this.entries.delete(id);
    let bucket = this.entries.get(key);
    if (!bucket) { if (this.entries.size >= 1000) fail('rate_limited', 429); bucket = { count: 0, until: now + 60000 }; this.entries.set(key, bucket); }
    if (++bucket.count > limit) fail('rate_limited', 429);
  }
}
function bearer(request) {
  const authorization = request.headers.authorization;
  if (typeof authorization !== 'string' || !/^Bearer [A-Za-z0-9_-]{32,256}$/.test(authorization)) fail('unauthorized', 401);
  return authorization.slice(7);
}
function reply(response, status, result) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer', 'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'" });
  response.end(JSON.stringify(result));
}
async function json(request) {
  if (!/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(request.headers['content-type'] ?? '')) fail('json_required', 415);
  if (request.headers['content-encoding']) fail('content_encoding_not_supported', 415);
  const declared = request.headers['content-length'];
  if (declared !== undefined && (!/^\d+$/.test(declared) || Number(declared) > MAX_BODY_BYTES)) fail('payload_too_large', 413);
  const chunks = []; let length = 0;
  for await (const chunk of request) { length += chunk.length; if (length > MAX_BODY_BYTES) fail('payload_too_large', 413); chunks.push(chunk); }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { fail('invalid_json'); }
}
function listQuery(url) {
  if ([...url.searchParams.keys()].some(key => !['after', 'limit'].includes(key)) || url.searchParams.getAll('after').length > 1 || url.searchParams.getAll('limit').length > 1) fail('invalid_query');
  const afterValue = url.searchParams.get('after') ?? '0'; const limitValue = url.searchParams.get('limit') ?? '100';
  if (!/^(?:0|[1-9]\d*)$/.test(afterValue) || !/^[1-9]\d*$/.test(limitValue)) fail('invalid_query');
  const after = Number(afterValue); const limit = Number(limitValue);
  if (!Number.isSafeInteger(after) || !Number.isSafeInteger(limit) || limit > 200) fail('invalid_query');
  return { after, limit };
}
export async function createTicketPilotServer({ dataDirectory, operatorSecret, clock = Date.now, requireHttps = true }) {
  if (typeof operatorSecret !== 'string' || !/^[A-Za-z0-9_-]{43,256}$/.test(operatorSecret)) throw new Error('operator_secret_requires_32_random_bytes_base64url');
  const operatorHash = hash(operatorSecret); const store = new PrivateStore(dataDirectory, clock); await store.init(); const limiter = new Limiter(clock);
  function operator(request) { if (!matches(bearer(request), operatorHash)) fail('unauthorized', 401); }
  const server = http.createServer({ maxHeaderSize: 8192, requestTimeout: 10000, headersTimeout: 5000 }, async (request, response) => {
    try {
      // Only bind to loopback in production and terminate HTTPS at the same host's proxy.
      if (requireHttps && request.headers['x-forwarded-proto'] !== 'https') fail('https_required', 426);
      limiter.take(`request:${request.socket.remoteAddress}`, 180);
      const url = new URL(request.url, 'http://loopback.invalid');
      if (request.url !== `${url.pathname}${url.search}` || url.hash) fail('invalid_path');
      const route = `${request.method} ${url.pathname}`;
      if (route !== 'GET /v1/diagnostics' && url.search) fail('invalid_query');
      if (route === 'GET /health') { reply(response, 200, { service: 'ticketpilot-private-sync', schema: 1, status: 'ok' }); return; }
      if (route === 'POST /v1/pairing') {
        operator(request); limiter.take('operator:pairing', 8); objectKeys(await json(request), []);
        reply(response, 201, await store.pairing()); return;
      }
      if (route === 'POST /v1/pair') {
        limiter.take('pairing:attempt', 12); const value = validatePair(await json(request));
        reply(response, 201, await store.pair(value.pairingCode, value.installId)); return;
      }
      if (route === 'POST /v1/diagnostics') {
        const device = store.authenticate(bearer(request)); limiter.take(`upload:${device.id}`, 30);
        const report = validateReport(await json(request), clock()); reply(response, 200, await store.upload(device.id, report)); return;
      }
      if (route === 'GET /v1/diagnostics') {
        operator(request); const query = listQuery(url); reply(response, 200, await store.list(query.after, query.limit)); return;
      }
      if (route === 'GET /v1/devices') { operator(request); reply(response, 200, await store.devices()); return; }
      if (route === 'DELETE /v1/device') {
        operator(request); const value = await json(request); objectKeys(value, ['deviceId']);
        reply(response, 200, await store.revoke(requireUuid(value.deviceId))); return;
      }
      fail('not_found', 404);
    } catch (error) {
      const known = error instanceof ProtocolError;
      // Never include request headers, input values, tokens, stack traces or filesystem paths.
      if (!response.headersSent && !response.destroyed) reply(response, known ? error.status : 503, { schema: 1, error: known ? error.code : 'service_unavailable' });
    }
  });
  server.keepAliveTimeout = 3000;
  return { server, store };
}
async function main() {
  const directory = process.env.TICKETPILOT_DATA_DIR;
  if (!directory || !path.isAbsolute(directory)) throw new Error('absolute_private_data_directory_required');
  const port = Number(process.env.TICKETPILOT_PORT ?? '8787');
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('invalid_listen_port');
  const { server } = await createTicketPilotServer({ dataDirectory: directory, operatorSecret: process.env.TICKETPILOT_OPERATOR_SECRET });
  server.listen(port, '127.0.0.1', () => process.stdout.write('TicketPilot private sync ready on loopback.\n'));
  const stop = () => server.close(() => process.exit(0)); process.on('SIGINT', stop); process.on('SIGTERM', stop);
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main().catch(() => { process.stderr.write('TicketPilot private sync could not start; check private configuration and storage.\n'); process.exitCode = 1; });
}
