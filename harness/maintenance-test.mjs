import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import WebSocket from 'ws';
import { startHost } from './host.mjs';

const png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=';
let summaries = 0, requests = 0, calls = 0, holdSummary, summaryStarted;
const waitSummary = () => new Promise(resolve => { summaryStarted = resolve; });
const server = createServer(async (request, response) => {
  if (request.method !== 'POST') { response.writeHead(405).end(); return; }
  let raw = ''; for await (const chunk of request) raw += chunk;
  const body = JSON.parse(raw);
  if (request.url === '/mcp') {
    if (body.id == null) { response.writeHead(202).end(); return; }
    const result = body.method === 'initialize' ? { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'fixture', version: '1' } }
      : body.method === 'tools/list' ? { tools: [{ name: 'screen', description: 'Real image content', inputSchema: { type: 'object', properties: {} } }] }
      : body.method === 'tools/call' ? (calls++, { content: [{ type: 'text', text: 'screen evidence' }, { type: 'image', data: png, mimeType: 'image/png' }] }) : {};
    response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify({ jsonrpc: '2.0', id: body.id, result })); return;
  }
  requests++;
  if (!raw.includes('compaction engine')) assert.ok(raw.includes('/workspace'), 'SDK cwd must use the actual workspace');
  const summary = body.messages.at(-1)?.content?.includes?.('You are now acting as a compaction engine');
  if (summary) {
    summaries++; summaryStarted?.();
    if (holdSummary) await new Promise(resolve => { holdSummary = resolve; response.on('close', resolve); });
  }
  if (raw.includes('image-fork')) assert.ok(/data:image\/(png|webp);base64,/.test(raw), 'fork must resolve inherited image references');
  const tool = requests === 1;
  const delta = tool ? { tool_calls: [{ index: 0, id: 'screen-once', type: 'function', function: { name: 'mcp__easylsp__screen', arguments: '{}' } }] }
    : { content: summary ? '## Goal\nPreserve the screen evidence.\n## Progress\nScreen observed once.\n## Pending\nContinue without repeating tools.' : 'Evidence retained. ' + 'Long task detail. '.repeat(250) };
  response.setHeader('Content-Type', 'text/event-stream');
  response.end('data: ' + JSON.stringify({ id: 'fixture', choices: [{ index: 0, delta, finish_reason: tool ? 'tool_calls' : 'stop' }], usage: { prompt_tokens: 2000, completion_tokens: 100, total_tokens: 2100 } }) + '\n\ndata: [DONE]\n\n');
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const directory = await mkdtemp('/tmp/easylsp-maintenance-');
const base = `http://127.0.0.1:${server.address().port}`;
const workspace = `${directory}/workspace`; await mkdir(workspace);
const config = { workspace, api: 'openai-completions', baseURL: `${base}/v1`, model: 'fixture', secret: 'fixture', supportsImages: true,
  bridgeURL: `${base}/mcp`, bridgeToken: 'fixture', contextWindow: 1000000, maxTokens: 4096 };
let host, socket, seq = 0;
const pending = new Map();
async function connect() {
  host = await startHost({ token: 'fixture', directory });
  socket = new WebSocket(`ws://127.0.0.1:${host.port}/session`, { headers: { Authorization: 'Bearer fixture' } });
  socket.on('message', raw => { const frame = JSON.parse(raw); if (frame.id != null) { const p = pending.get(frame.id); pending.delete(frame.id); frame.error ? p.reject(new Error(frame.error.message)) : p.resolve(frame.result); } });
  await new Promise(resolve => socket.once('open', resolve));
}
function rpc(method, params) { return new Promise((resolve, reject) => { const id = ++seq; pending.set(id, { resolve, reject }); socket.send(JSON.stringify({ id, method, params })); }); }
async function prompt(sessionId, text) {
  await rpc('session/prompt', { sessionId, requestId: text, contentBlocks: [{ type: 'text', text }] });
  await rpc('session/wait', { sessionId });
}
try {
  await connect(); await rpc('session/open', { sessionId: 'source', config });
  for (let i = 0; i < 5; i++) await prompt('source', `task-${i}`);
  await rpc('session/fork', { sessionId: 'source', branchId: 'images' });
  await prompt('images', 'image-fork'); assert.equal(calls, 1);
  const params = { sessionId: 'source', requestId: 'compact-once', command: '/compact' };
  holdSummary = true; const started = waitSummary(); const compact = rpc('session/command', params);
  await started;
  assert.equal((await rpc('session/receipt', params)).status, 'RUNNING');
  assert.equal((await rpc('session/command', params)).status, 'RUNNING');
  assert.equal((await rpc('session/state', { sessionId: 'source' })).activity, 'maintenance');
  const queued = await rpc('session/prompt', { sessionId: 'source', requestId: 'during-compact', contentBlocks: [{ type: 'text', text: 'queued-during-compact' }] });
  assert.equal(queued.status, 'QUEUED');
  holdSummary(); holdSummary = null;
  const result = await compact;
  assert.equal(result.status, 'COMPLETE'); assert.equal(summaries, 1);
  await rpc('session/wait', { sessionId: 'source' });
  assert.deepEqual(await rpc('session/command', params), result);
  socket.terminate(); await host.close(); await connect();
  await rpc('session/open', { sessionId: 'source', config, resume: true });
  assert.deepEqual(await rpc('session/receipt', params), result); assert.equal(summaries, 1);
  for (let i = 0; i < 4; i++) await prompt('source', `after-compact-${i}`);
  holdSummary = true; const cancelling = waitSummary();
  const cancelled = rpc('session/command', { ...params, requestId: 'compact-cancel' });
  await cancelling; await rpc('session/cancel', { sessionId: 'source' });
  const cancellation = await cancelled;
  assert.equal(cancellation.status, 'ERROR'); assert.match(cancellation.result?.text ?? cancellation.message, /cancel|abort/i);
  const history = await rpc('session/history', { sessionId: 'source', after: -1, limit: 200 });
  assert.ok(history.events.some(event => event.type === 'compaction/summary' && event.data.usage));
  console.log(JSON.stringify({ imageFork: true, oldToolsReplayed: 0, durableCommandReceipt: true, duplicateSuppressed: true, cancellation: true, realSummaryUsage: true, summaries }));
} finally {
  if (typeof holdSummary === 'function') holdSummary();
  socket?.terminate(); await host?.close(); server.closeAllConnections(); server.close(); await rm(directory, { recursive: true });
}
