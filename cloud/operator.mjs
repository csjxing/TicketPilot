// Owner-only helper; credentials come from private environment, never command arguments.
import { open } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as sleep } from 'node:timers/promises';
import { UUID } from './validation.mjs';

export function parseCommand(argv) {
  const args = [...argv]; const command = args.shift(); const outputIndex = args.indexOf('--out');
  if (outputIndex < 0 || outputIndex !== args.lastIndexOf('--out') || outputIndex + 1 >= args.length || !path.isAbsolute(args[outputIndex + 1])) throw new Error('usage');
  const outputPath = args[outputIndex + 1]; args.splice(outputIndex, 2);
  const followIndex = args.indexOf('--follow'); let follow = false;
  if (followIndex >= 0) { args.splice(followIndex, 1); follow = true; }
  let route; let method = 'GET'; let body;
  if (command === 'pair' && !args.length && !follow) { route = '/v1/pairing'; method = 'POST'; body = {}; }
  else if (command === 'devices' && !args.length && !follow) route = '/v1/devices';
  else if (command === 'reports' && args.length <= 1 && /^(?:0|[1-9]\d*)$/.test(args[0] ?? '0') && Number.isSafeInteger(Number(args[0] ?? '0'))) route = '/v1/diagnostics?limit=100&after=' + (args[0] ?? '0');
  else if (command === 'revoke' && args.length === 1 && UUID.test(args[0]) && !follow) { route = '/v1/device'; method = 'DELETE'; body = { deviceId: args[0] }; }
  else throw new Error('usage');
  return { command, outputPath, follow, route, method, body, after: command === 'reports' ? Number(args[0] ?? '0') : 0 };
}
export function receiver(value) {
  const endpoint = new URL(value ?? '');
  if (endpoint.protocol !== 'https:' || endpoint.username || endpoint.password || endpoint.search || endpoint.hash ||
      endpoint.port || !endpoint.hostname || endpoint.hostname.endsWith('.') || !/^(?:\/[A-Za-z0-9_-]+)*\/?$/.test(endpoint.pathname)) throw new Error('invalid_endpoint');
  return endpoint.href.replace(/\/$/, '');
}
async function request(endpoint, secret, route, method, body, fetchImpl, signal) {
  let response;
  try { response = await fetchImpl(endpoint + route, { method, redirect: 'error', signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(15000)]) : AbortSignal.timeout(15000), headers: { Authorization: `Bearer ${secret}`, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) }, body: body === undefined ? undefined : JSON.stringify(body) }); }
  catch (cause) { if (signal?.aborted) throw cause; const error = new Error('network_unavailable'); error.retryable = true; throw error; }
  if (!response.ok) { const error = new Error('request_failed'); error.retryable = response.status === 429 || response.status >= 500; throw error; }
  const chunks = []; let size = 0;
  try {
    for await (const chunk of response.body) { size += chunk.length; if (size > 8 * 1024 * 1024) throw new Error('response_too_large'); chunks.push(chunk); }
  } catch (cause) {
    if (signal?.aborted || cause.message === 'response_too_large') throw cause;
    const error = new Error('network_unavailable'); error.retryable = true; throw error;
  }
  const value = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  if (value.schema !== 1) throw new Error('unsupported_schema');
  return value;
}
function batchCursor(value, after) {
  if (!Array.isArray(value.reports) || value.reports.length > 100 || typeof value.gap !== 'boolean' ||
      !Number.isSafeInteger(value.nextCursor) || !Number.isSafeInteger(value.oldestAvailableCursor) || value.oldestAvailableCursor < 1) throw new Error('invalid_cursor');
  let previous = after;
  for (const item of value.reports) { if (!Number.isSafeInteger(item.cursor) || item.cursor <= previous) throw new Error('invalid_cursor'); previous = item.cursor; }
  if (value.nextCursor !== previous) throw new Error('invalid_cursor');
  return previous;
}
export async function runOperator(options, { endpoint, secret, fetchImpl = fetch, signal, wait = (ms, abort) => sleep(ms, undefined, { signal: abort }), status = () => {} }) {
  const base = receiver(endpoint);
  if (!secret || !/^[A-Za-z0-9_-]{43,256}$/.test(secret)) throw new Error('invalid_owner_secret');
  // Exclusive creation prevents overwriting evidence and tests output permission before pairing side effects.
  const file = await open(options.outputPath, 'wx', 0o600);
  try {
    if (!options.follow) {
      const value = await request(base, secret, options.route, options.method, options.body, fetchImpl, signal);
      await file.writeFile(JSON.stringify(value, null, 2) + '\n'); await file.sync(); status('Private result saved. Keep it out of Git.'); return;
    }
    let after = options.after, retryDelay = 10000, first = true;
    while (!signal?.aborted) {
      try {
        const value = await request(base, secret, `/v1/diagnostics?limit=100&after=${after}`, 'GET', undefined, fetchImpl, signal);
        const next = batchCursor(value, after);
        // The cursor advances only after the corresponding private batch is durably written.
        if (first || value.reports.length || value.gap) { await file.writeFile(JSON.stringify(value) + '\n'); await file.sync(); }
        first = false; after = next; retryDelay = 10000;
        if (value.reports.length) status(`Received ${value.reports.length} private diagnostic reports.`);
      } catch (error) {
        if (signal?.aborted) break;
        if (error.message !== 'fetch failed' && !error.retryable && error.name !== 'TimeoutError') throw error;
        status('Sync temporarily unavailable; retrying with the saved cursor.'); retryDelay = Math.min(60000, retryDelay * 2);
      }
      try { await wait(retryDelay, signal); } catch (error) { if (!signal?.aborted) throw error; }
    }
    status('Private diagnostic follow stopped.');
  } finally { await file.close(); }
}
async function main() {
  const controller = new AbortController(); const stop = () => controller.abort(); process.on('SIGINT', stop); process.on('SIGTERM', stop);
  try {
    await runOperator(parseCommand(process.argv.slice(2)), { endpoint: process.env.TICKETPILOT_SYNC_URL, secret: process.env.TICKETPILOT_OPERATOR_SECRET, signal: controller.signal, status: value => process.stdout.write(value + '\n') });
  } finally { process.off('SIGINT', stop); process.off('SIGTERM', stop); }
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main().catch(error => {
    if (error.name === 'AbortError') return;
    process.stderr.write(error.message === 'usage' ? 'Usage: node operator.mjs pair|devices|reports [after] [--follow]|revoke <device-id> --out /absolute/private/file.private.json\n' : 'Private sync request failed; check HTTPS, credentials and unused private output path.\n');
    process.exitCode = 1;
  });
}
