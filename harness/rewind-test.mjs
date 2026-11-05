import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, rm } from 'node:fs/promises';
import WebSocket from 'ws';
import { startHost } from './host.mjs';
let calls = 0;
const requests = [];
const server = createServer(async (request, response) => {
  if (request.method !== 'POST') { response.writeHead(405).end(); return; }
  let raw = ''; for await (const chunk of request) raw += chunk;
  const body = JSON.parse(raw);
  if (request.url === '/mcp') {
    if (body.id == null) { response.writeHead(202).end(); return; }
    const result = body.method === 'initialize' ? { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'fixture', version: '1' } }
      : body.method === 'tools/list' ? { tools: [{ name: 'write_fixture', description: 'Count one write', inputSchema: { type: 'object', properties: {} } }] }
      : body.method === 'tools/call' ? { content: [{ type: 'text', text: `write ${++calls}` }] } : {}; 
    response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify({ jsonrpc: '2.0', id: body.id, result })); return;
  }
  requests.push(body);
  const last = body.messages.at(-1);
  const tool = requests.length === 1;
  const delta = tool ? { tool_calls: [{ index: 0, id: 'write-once', type: 'function', function: { name: 'mcp__easylsp__write_fixture', arguments: '{}' } }] } : { content: 'reply ' + requests.length };
  response.setHeader('Content-Type', 'text/event-stream');
  response.end('data: ' + JSON.stringify({ id: 'fixture', choices: [{ index: 0, delta, finish_reason: tool ? 'tool_calls' : 'stop' }] }) + '\n\ndata: [DONE]\n\n');
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const directory = await mkdtemp('/tmp/easylsp-rewind-');
const config = { api: 'openai-completions', baseURL: `http://127.0.0.1:${server.address().port}/v1`, bridgeURL: `http://127.0.0.1:${server.address().port}/mcp`, secret: 'fixture', model: 'fixture', bridgeToken: 'fixture' };
let host, socket, seq = 0;
const pending = new Map();
const responseStarts = new Map();
const settledResponses = [];
let stableResponses = 0;
async function connect() {
  host = await startHost({ token: 'fixture', directory });
  socket = new WebSocket(`ws://127.0.0.1:${host.port}/session`, { headers: { Authorization: 'Bearer fixture' } });
  socket.on('message', raw => {
    const f = JSON.parse(raw);
    if (f.method === 'session.stream') {
      const {sessionId, frame} = f.params, key = `${sessionId}/${frame.attemptId}`;
      if (frame.type === 'start') responseStarts.set(key, frame);
      if (frame.type === 'end' && frame.outcome.kind === 'committed') {
        settledResponses.push({sessionId, start: responseStarts.get(key), seq: frame.outcome.seq});
      }
    }
    if (f.id != null) { const p = pending.get(f.id); pending.delete(f.id); f.error ? p.reject(new Error(f.error.message)) : p.resolve(f.result); }
  });
  await new Promise(resolve => socket.once('open', resolve));
}
function rpc(method, params) { return new Promise((resolve, reject) => { const id = ++seq; pending.set(id, { resolve, reject }); socket.send(JSON.stringify({ id, method, params })); }); }
async function send(sessionId, text, requestId = text) {
  const idle = new Promise(resolve => { const listen = raw => { const f = JSON.parse(raw); if (f.method === 'session.status' && f.params.sessionId === sessionId && f.params.status === 'idle') { socket.off('message', listen); resolve(); } }; socket.on('message', listen); });
  const receipt = await rpc('session/prompt', { sessionId, requestId, contentBlocks: [{ type: 'text', text }] }); await idle;
  const {events} = await rpc('session/history', {sessionId, after: -1, limit: 200});
  for (const response of settledResponses.splice(0)) {
    assert.equal(response.sessionId, sessionId);
    const matching = events.filter(event => (event.type === 'assistant/message' || event.type === 'assistant/attempt') && event.data.turn === response.start.turn && event.data.step === response.start.step && event.seq <= response.seq);
    assert.equal(response.start.responseIndex, matching.length, 'live and committed response identities agree');
    stableResponses++;
  }
  return receipt;
}
try {
  await connect(); await rpc('session/open', { sessionId: 'source', config });
  const first = await send('source', 'first'); const second = await send('source', 'discard-this');
  const cut = await rpc('session/rewind', { sessionId: 'source', branchId: 'edited', messageId: second.messageId });
  assert.ok(cut.boundary >= 0);
  assert.deepEqual(await rpc('session/rewind', { sessionId: 'source', branchId: 'edited', messageId: second.messageId }), cut);
  await send('edited', 'replacement');
  const context = requests.at(-1).messages;
  assert.ok(JSON.stringify(context).includes('first')); assert.ok(!JSON.stringify(context).includes('discard-this')); assert.ok(JSON.stringify(context).includes('write 1')); assert.equal(calls, 1);
  socket.terminate(); await host.close(); await connect();
  await rpc('session/open', { sessionId: 'edited', config, resume: true }); await send('edited', 'after-restart');
  assert.ok(JSON.stringify(requests.at(-1)).includes('replacement')); assert.ok(!JSON.stringify(requests.at(-1)).includes('discard-this')); assert.equal(calls, 1);
  await rpc('session/open', { sessionId: 'source', config, resume: true });
  await rpc('session/rewind', { sessionId: 'source', branchId: 'empty-edit', messageId: first.messageId }); await send('empty-edit', 'new-first');
  assert.ok(!JSON.stringify(requests.at(-1)).includes('write 1')); assert.ok(!JSON.stringify(requests.at(-1)).includes('discard-this')); assert.equal(calls, 1);
  assert.ok(stableResponses >= 6);
  console.log(JSON.stringify({ middlePrefix: true, emptyFirstTurn: true, resumed: true, idempotentFork: true, oldWritesReplayed: 0, stableResponses }));
} finally { socket?.terminate(); await host?.close(); server.closeAllConnections(); server.close(); await rm(directory, { recursive: true }); }
