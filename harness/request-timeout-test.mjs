import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createServer as createTlsServer, createSecureContext } from 'node:tls';
import { execFileSync, execFile } from 'node:child_process';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { HarnessRuntime } from './runtime.mjs';

const directory = await mkdtemp(join(tmpdir(), 'easylsp-timeout-'));
execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', join(directory, 'key.pem'), '-out', join(directory, 'cert.pem'), '-days', '1', '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost'], { stdio: 'ignore' });
const key = await readFile(join(directory, 'key.pem')), cert = await readFile(join(directory, 'cert.pem'));
process.env.NODE_EXTRA_CA_CERTS = join(directory, 'cert.pem');
const secureContext = createSecureContext({ key, cert });
const sockets = new Set();
const tls = createTlsServer({ key, cert, SNICallback: (_name, callback) => setTimeout(() => callback(null, secureContext), 11000) }, socket => {
  // The baseline deliberately disconnects before the delayed TLS handshake finishes.
  socket.on('error', () => {});
  socket.once('data', () => socket.end('HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok'));
});
tls.on('tlsClientError', () => {});
tls.on('connection', socket => { socket.on('error', () => {}); sockets.add(socket); socket.on('close', () => sockets.delete(socket)); });
await new Promise(resolve => tls.listen(0, '127.0.0.1', resolve));
const url = `https://localhost:${tls.address().port}`;
const native = `const start=Date.now(); try { await fetch(process.env.TEST_URL); console.log(JSON.stringify({ok:true,ms:Date.now()-start})); } catch(e) { console.log(JSON.stringify({ok:false,ms:Date.now()-start,code:e.cause?.code})); }`;
async function child(args, extra = {}) {
  return await new Promise((resolve, reject) => execFile(process.execPath, args, { env: { ...process.env, TEST_URL: url, ...extra } }, (error, stdout) => error ? reject(error) : resolve(JSON.parse(stdout))));
}
const baseline = await child(['--input-type=module', '-e', native]);
assert.equal(baseline.code, 'UND_ERR_CONNECT_TIMEOUT'); assert.ok(baseline.ms >= 9500 && baseline.ms < 11000);
const fixed = await child(['--import', new URL('model-http.mjs', import.meta.url).pathname, '--input-type=module', '-e', native], { EASYLSP_HTTP_TIMEOUT_MS: '30000', EASYLSP_MODEL_ORIGIN: url });
assert.equal(fixed.ok, true); assert.ok(fixed.ms > 11000);
let delayFirst = false, delayStream = false, disconnected;
const http = createServer(async (request, response) => {
  if (request.method !== 'POST') { response.writeHead(405).end(); return; }
  let raw = ''; for await (const chunk of request) raw += chunk;
  const frame = JSON.parse(raw);
  if (request.url === '/mcp') {
    if (frame.id == null) { response.writeHead(202).end(); return; }
    const result = frame.method === 'initialize' ? { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'timeout-fixture', version: '1' } } : { tools: [] };
    response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify({ jsonrpc: '2.0', id: frame.id, result })); return;
  }
  response.on('close', () => disconnected?.());
  if (delayFirst) await new Promise(resolve => setTimeout(resolve, 11000));
  if (response.destroyed) return;
  response.setHeader('Content-Type', 'text/event-stream');
  const send = (content, finish_reason) => response.write(`data: ${JSON.stringify({ id: 'reply', model: 'fixture', choices: [{ index: 0, delta: { content }, finish_reason }] })}\n\n`);
  send('first ', null);
  if (delayStream) await new Promise(resolve => setTimeout(resolve, 11000));
  if (response.destroyed) return;
  send('complete', 'stop'); response.end('data: [DONE]\n\n');
});
await new Promise(resolve => http.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${http.address().port}`;
const results = [];
async function run(id, timeoutMs, cancel = false) {
  const runtime = new HarnessRuntime();
  try {
    await runtime.start({ directory: join(directory, id), api: 'openai-completions', baseURL: `${base}/v1`, model: 'fixture', secret: 'key', contextWindow: 32768, maxTokens: 1024, timeoutMs, bridgeURL: `${base}/mcp`, bridgeToken: 'key' });
    const start = Date.now();
    await runtime.rpc('session/prompt', { sessionId: id, contentBlocks: [{ type: 'text', text: 'Reply briefly' }] });
    if (cancel) { await new Promise(resolve => setTimeout(resolve, 500)); await runtime.control('session/cancel', { sessionId: id }); }
    await runtime.rpc('session/wait', { sessionId: id });
    const history = await runtime.control('session/history', { sessionId: id, after: -1, limit: 200 });
    const end = history.events.findLast(event => event.type === 'turn/end');
    const elapsedMs = Date.now() - start;
    results.push({ id, elapsedMs, reason: end.data.reason });
    return { end, elapsedMs };
  } finally { await runtime.close(); }
}
try {
  delayFirst = true;
  assert.equal((await run('first-response', 30000)).end.data.reason.kind, 'completed');
  delayFirst = false; delayStream = true;
  assert.equal((await run('stream-wait', 30000)).end.data.reason.kind, 'completed');
  delayFirst = true; delayStream = false;
  const timeout = await run('explicit-timeout', 800);
  assert.equal(timeout.end.data.reason.kind, 'error'); assert.ok(timeout.elapsedMs < 5000);
  let closed = false; disconnected = () => { closed = true; };
  const cancelled = await run('cancel', 30000, true);
  assert.equal(cancelled.end.data.reason.kind, 'aborted'); assert.ok(closed); assert.ok(cancelled.elapsedMs < 5000);
  console.log(JSON.stringify({ baseline, fixed, results }));
} finally {
  for (const socket of sockets) socket.destroy(); tls.close();
  http.closeAllConnections(); http.close(); await rm(directory, { recursive: true });
}
