import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, rm } from 'node:fs/promises';
import WebSocket from 'ws';
import { startHost } from './host.mjs';

const requests = [], gates = new Map();
let calls = 0;
const server = createServer(async (request, response) => {
  if (request.method !== 'POST') { response.writeHead(405).end(); return; }
  let raw = ''; for await (const chunk of request) raw += chunk;
  const body = JSON.parse(raw);
  if (request.url === '/mcp') {
    if (body.id == null) { response.writeHead(202).end(); return; }
    const result = body.method === 'initialize' ? { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'fixture', version: '1' } } :
      body.method === 'tools/list' ? { tools: [{ name: 'read_file', description: 'Read file', inputSchema: { type: 'object', properties: {} } }] } :
      body.method === 'tools/call' ? { content: [{ type: 'text', text: `read-${++calls}` }] } : {};
    response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify({ jsonrpc: '2.0', id: body.id, result })); return;
  }
  requests.push(body);
  const text = JSON.stringify(body.messages);
  const pendingTool = body.messages.at(-1)?.role === 'tool';
  response.setHeader('Content-Type', 'text/event-stream');
  const finish = () => {
    if (response.destroyed) return;
    const tool = text.includes('parent hold') && !pendingTool && !text.includes('branch followup') && !text.includes('read-');
    const delta = tool ? { tool_calls: [{ index: 0, id: 'read-once', type: 'function', function: { name: 'mcp__easylsp__read_file', arguments: '{}' } }] } : { content: 'completed' };
    response.write(`data: ${JSON.stringify({ id: 'response', object: 'chat.completion.chunk', model: 'fixture', choices: [{ index: 0, delta, finish_reason: null }] })}\n\n`);
    response.write(`data: ${JSON.stringify({ id: 'response', object: 'chat.completion.chunk', model: 'fixture', choices: [{ index: 0, delta: {}, finish_reason: tool ? 'tool_calls' : 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 4, total_tokens: 14 } })}\n\n`);
    response.end('data: [DONE]\n\n');
  };
  if (text.includes('parent hold') && !pendingTool && requests.length === 1) gates.set('parent', finish); else finish();
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const directory = await mkdtemp('/tmp/easylsp-platform-');
const host = await startHost({ token: 'fixture', directory, concurrency: 1 });
const socket = new WebSocket(`ws://127.0.0.1:${host.port}/session`, { headers: { Authorization: 'Bearer fixture' } });
const pending = new Map(); let sequence = 0;
socket.on('message', raw => { const frame = JSON.parse(raw); if (frame.id != null) { const p = pending.get(frame.id); pending.delete(frame.id); frame.error ? p.reject(new Error(frame.error.message)) : p.resolve(frame.result); } });
await new Promise(resolve => socket.once('open', resolve));
const rpc = (method, params = {}) => new Promise((resolve, reject) => { const id = ++sequence; pending.set(id, { resolve, reject }); socket.send(JSON.stringify({ id, method, params })); });
const until = async predicate => { for (let i = 0; i < 400; i++) { if (await predicate()) return; await new Promise(resolve => setTimeout(resolve, 25)); } throw new Error('Timed out waiting for state'); };
const config = { api: 'openai-completions', baseURL: `http://127.0.0.1:${server.address().port}/v1`, model: 'fixture', secret: 'key', contextWindow: 100000, contextBudget: 100000, bridgeURL: `http://127.0.0.1:${server.address().port}/mcp`, bridgeToken: 'token' };
const prompt = (sessionId, requestId, text) => rpc('session/prompt', { sessionId, requestId, contentBlocks: [{ type: 'text', text }] });
const timeout = setTimeout(() => { throw new Error('Platform integration timed out'); }, 90000);
try {
  await rpc('session/open', { sessionId: 'parent', config });
  await rpc('session/open', { sessionId: 'child', parentSessionId: 'parent', config });
  await prompt('parent', 'p-one', 'parent hold'); await until(() => gates.has('parent'));
  assert.equal((await prompt('child', 'c-one', 'child work')).status, 'QUEUED');
  assert.equal((await rpc('session/state', { sessionId: 'child' })).status, 'queued');
  await rpc('session/yield', { sessionId: 'parent' });
  await until(async () => (await rpc('session/receipt', { sessionId: 'child', requestId: 'c-one' })).status === 'ACCEPTED');
  await rpc('session/wait', { sessionId: 'child' });
  await rpc('session/acquire', { sessionId: 'parent' });
  const steer = await rpc('session/steer', { sessionId: 'parent', requestId: 'p-steer', contentBlocks: [{ type: 'text', text: 'supplement now' }] });
  assert.equal(steer.status, 'ACCEPTED'); assert.equal(steer.effectiveFrom, 'next_step');
  gates.get('parent')(); await rpc('session/wait', { sessionId: 'parent' });
  assert.ok(requests.some(body => JSON.stringify(body.messages).includes('supplement now')));
  const original = await rpc('session/history', { sessionId: 'parent', after: -1, limit: 200 });
  assert.ok(original.events.some(event => event.type === 'user/message' && event.data.id === steer.messageId));
  const callsBefore = calls;
  const fork = await rpc('session/fork', { sessionId: 'parent', branchId: 'branch' });
  assert.ok(fork.boundary >= 0); assert.equal(calls, callsBefore);
  assert.equal((await rpc('session/state', { sessionId: 'parent' })).status, 'idle');
  await prompt('branch', 'branch-one', 'branch followup'); await rpc('session/wait', { sessionId: 'branch' });
  assert.equal(calls, callsBefore);
  const branch = await rpc('session/history', { sessionId: 'branch', after: -1, limit: 200 });
  assert.ok(branch.events.length > original.events.length);
  assert.deepEqual(await rpc('session/receipt', { sessionId: 'parent', requestId: 'p-steer' }), steer);
  await rpc('session/open', { sessionId: 'empty', config }); await rpc('session/close', { sessionId: 'empty' });
  console.log(JSON.stringify({ unifiedSessions: true, queuedAdmission: true, parentYieldReacquire: true, realSteer: true, independentFork: true, sourceRemainsOpen: true, inheritedToolsReplayed: 0, detachedClose: true }));
} finally {
  clearTimeout(timeout); socket.terminate(); await host.close(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); await rm(directory, { recursive: true });
}
