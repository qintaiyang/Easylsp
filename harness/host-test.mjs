import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtemp, rm, access } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import WebSocket from 'ws';
import { startHost } from './host.mjs';

let requests = 0, calls = 0, initializations = 0, listings = 0, release;
const provider = createServer(async (request, response) => {
  if (request.method !== 'POST') { response.writeHead(405).end(); return; }
  let raw = ''; for await (const chunk of request) raw += chunk;
  const frame = JSON.parse(raw);
  if (request.url === '/mcp') {
    if (frame.id == null) { response.writeHead(202).end(); return; }
    let result;
    if (frame.method === 'initialize') { initializations++; result = { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'fixture', version: '1' } }; }
    if (frame.method === 'tools/list') { listings++; result = { tools: [{ name: 'get_project_info', description: 'Read project', inputSchema: { type: 'object', properties: {} } }] }; }
    if (frame.method === 'tools/call') { calls++; result = { content: [{ type: 'text', text: '{"packageName":"fixture.demo"}' }] }; }
    response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify({ jsonrpc: '2.0', id: frame.id, ...(result ? { result } : { error: { code: -32601, message: 'Unknown method' } }) })); return;
  }
  const index = ++requests;
  assert.deepEqual(frame.tools.map(tool => tool.function.name), ['mcp__easylsp__get_project_info']);
  response.setHeader('Content-Type', 'text/event-stream');
  const send = (delta, finish_reason) => response.write(`data: ${JSON.stringify({ id: `reply-${index}`, object: 'chat.completion.chunk', model: 'fixture', choices: [{ index: 0, delta, finish_reason }] })}\n\n`);
  const tool = index === 1;
  send(tool ? { tool_calls: [{ index: 0, id: 'fixture-read', type: 'function', function: { name: 'mcp__easylsp__get_project_info', arguments: '{}' } }] } : { content: '连接恢复后仍继续' }, null);
  const end = () => { send({}, tool ? 'tool_calls' : 'stop'); response.end('data: [DONE]\n\n'); };
  if (index === 2) release = end; else end();
});
await new Promise(resolve => provider.listen(0, '127.0.0.1', resolve));
const directory = await mkdtemp(join(tmpdir(), 'easylsp-host-'));
const token = 'host-test-token', sessionId = 'host-test-session';
const config = { api: 'openai-completions', baseURL: `http://127.0.0.1:${provider.address().port}/v1`, model: 'fixture', secret: 'fixture-key', bridgeURL: `http://127.0.0.1:${provider.address().port}/mcp`, bridgeToken: 'fixture-token' };
let host = await startHost({ token, directory });
async function connect(port) {
  const socket = new WebSocket(`ws://127.0.0.1:${port}/session`, { headers: { Authorization: `Bearer ${token}` } });
  const pending = new Map(); let seq = 0;
  socket.on('message', raw => {
    const frame = JSON.parse(raw);
    if (frame.id != null) {
      const p = pending.get(frame.id); pending.delete(frame.id);
      if (frame.error) p.reject(new Error(frame.error.message)); else p.resolve(frame.result);
    }
  });
  await new Promise((resolve, reject) => { socket.once('open', resolve); socket.once('error', reject); });
  return {
    socket,
    rpc(method, params = {}) { console.log(method, params.sessionId ?? sessionId); return new Promise((resolve, reject) => {
      const id = ++seq; pending.set(id, { resolve, reject }); socket.send(JSON.stringify({ id, method, params: { sessionId, ...params } }));
    }); },
    until(method, predicate) { return new Promise(resolve => {
      const listen = raw => { const frame = JSON.parse(raw); if (frame.method === method && predicate(frame.params)) { socket.off('message', listen); resolve(frame.params); } };
      socket.on('message', listen);
    }); },
  };
}
const timeout = setTimeout(() => { throw new Error('Host test timed out'); }, 60000);
try {
  let client = await connect(host.port);
  await client.rpc('session/open', { config });
  const firstText = client.until('session.stream', p => p.frame.chunk?.type === 'text-delta');
  const accepted = await client.rpc('session/prompt', { requestId: 'input-one', contentBlocks: [{ type: 'text', text: 'Read project' }] });
  assert.equal(accepted.status, 'ACCEPTED'); await firstText;
  client.socket.terminate();
  client = await connect(host.port);
  const state = await client.rpc('session/state');
  assert.equal(state.status, 'running'); assert.ok(state.stream.chunks.length > 0);
  assert.deepEqual(await client.rpc('session/prompt', { requestId: 'input-one' }), accepted);
  const idle = client.until('session.status', p => p.status === 'idle'); release(); await idle;
  const history = await client.rpc('session/history', { after: -1, limit: 200 });
  assert.ok(history.events.some(e => e.type === 'assistant/message'));
  const secondIdle = client.until('session.status', p => p.status === 'idle');
  await client.rpc('session/prompt', { requestId: 'input-two', contentBlocks: [{ type: 'text', text: 'Continue' }] }); await secondIdle;
  assert.equal(requests, 3); assert.equal(calls, 1); assert.equal(initializations, 1); assert.equal(listings, 2);
  await client.rpc('session/open', { config: { ...config, contextWindow: 1000000, contextBudget: 200000, maxTokens: 1024 }, resume: true, reconfigure: true });
  assert.ok((await client.rpc('session/history', { after: -1, limit: 200 })).events.length >= history.events.length);
  assert.equal(requests, 3); assert.equal(calls, 1);
  const child = 'readonly-child';
  await client.rpc('child/open', { sessionId: child, config });
  await client.rpc('child/prompt', { sessionId: child, requestId: 'child-input', contentBlocks: [{ type: 'text', text: 'Independent read-only task' }] });
  await client.rpc('child/wait', { sessionId: child });
  assert.ok((await client.rpc('child/history', { sessionId: child, after: -1, limit: 200 })).events.some(event => event.type === 'turn/end'));
  await assert.rejects(client.rpc('session/remove-history', { sessionId: child }), /Close the session/);
  await client.rpc('child/close', { sessionId: child });
  await client.rpc('session/remove-history', { sessionId: child });
  await assert.rejects(access(join(directory, child)), { code: 'ENOENT' });
  await assert.rejects(client.rpc('session/remove-history', { sessionId: '../escape' }), /Invalid session/);
  await client.rpc('session/remove-history', { sessionId: child });
  assert.equal(requests, 4); assert.equal(calls, 1);
  client.socket.terminate(); await host.close(); host = undefined;
  // The daemon owns SDK stdin even after the launcher has exited.
  const boot = spawn(process.execPath, [new URL('boot.mjs', import.meta.url).pathname], { stdio: ['pipe', 'pipe', 'pipe'] });
  let output = ''; boot.stdout.on('data', value => output += value);
  boot.stdin.end(JSON.stringify({ token, directory }) + '\n');
  assert.equal(await new Promise(resolve => boot.once('close', resolve)), 0);
  const daemon = JSON.parse(output);
  try {
    client = await connect(daemon.port);
    await client.rpc('session/open', { config, resume: true });
    assert.deepEqual(await client.rpc('session/receipt', { requestId: 'input-one' }), accepted);
    const restored = await client.rpc('session/history', { after: -1, limit: 200 });
    assert.ok(restored.events.length >= history.events.length); assert.equal(calls, 1); assert.equal(requests, 4);
    console.log(JSON.stringify({ reconnectContinued: true, duplicateInputDispatched: 0, warmInitialize: 1, warmToolsList: 2, launcherExitedDaemonAlive: true, restartRestoredReceipt: true, toolsReplayed: 0, configurationResumedHistory: true, childUsedSameSdk: true }));
  } finally {
    const closed = new Promise(resolve => client.socket.once('close', resolve));
    assert.equal((await client.rpc('host/stop')).status, 'stopping'); await closed;
  }
} finally {
  clearTimeout(timeout); await host?.close(); provider.closeAllConnections(); provider.close(); await rm(directory, { recursive: true });
}
