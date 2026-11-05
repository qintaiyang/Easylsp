import { createServer } from 'node:http';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import assert from 'node:assert/strict';
import { HarnessRuntime } from './runtime.mjs';

let requests = 0, calls = 0, initializations = 0, listings = 0;
let releaseReply, firstDelta, cancelledRequest;
const startedStream = new Promise(resolve => { firstDelta = resolve; });
const cancellation = new Promise(resolve => { cancelledRequest = resolve; });
const server = createServer(async (request, response) => {
  if (request.method !== 'POST') { response.writeHead(405).end(); return; }
  let raw = ''; for await (const chunk of request) raw += chunk;
  const frame = JSON.parse(raw);
  if (request.url === '/mcp') {
    assert.equal(request.headers.authorization, 'Bearer runtime-token');
    if (frame.id == null) { response.writeHead(202).end(); return; }
    let result;
    if (frame.method === 'initialize') { initializations++; result = { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'fixture', version: '1' } }; }
    if (frame.method === 'tools/list') { listings++; result = { tools: [{ name: 'get_project_info', description: 'Read project', inputSchema: { type: 'object', properties: {}, additionalProperties: false } }] }; }
    if (frame.method === 'tools/call') { calls++; result = { content: [{ type: 'text', text: '{"packageName":"fixture.demo","sampleRevision":1}' }] }; }
    if (frame.method === 'ping') result = {};
    response.setHeader('Content-Type', 'application/json');
    response.end(JSON.stringify({ jsonrpc: '2.0', id: frame.id, ...(result ? { result } : { error: { code: -32601, message: 'Unknown method' } }) })); return;
  }
  const current = ++requests;
  assert.deepEqual(frame.tools.map(tool => tool.function.name), ['mcp__easylsp__get_project_info']);
  const delta = current === 1 ? { role: 'assistant', tool_calls: [{ index: 0, id: 'read-1', type: 'function', function: { name: 'mcp__easylsp__get_project_info', arguments: '{}' } }] } : { role: 'assistant', content: '实时中文输出' };
  const send = (delta, finish_reason = null) => response.write(`data: ${JSON.stringify({ id: `reply-${current}`, object: 'chat.completion.chunk', model: 'fixture', choices: [{ index: 0, delta, finish_reason }] })}\n\n`);
  const end = () => { send({}, current === 1 ? 'tool_calls' : 'stop'); response.end('data: [DONE]\n\n'); };
  response.setHeader('Content-Type', 'text/event-stream');
  send(delta);
  if (current === 2) { releaseReply = end; firstDelta(); }
  else if (current === 4) response.once('close', cancelledRequest);
  else end();
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const port = server.address().port;
const directory = await mkdtemp(join(tmpdir(), 'easylsp-runtime-'));
const config = { directory, api: 'openai-completions', baseURL: `http://127.0.0.1:${port}/v1`, model: 'fixture', secret: 'fixture-key', bridgeURL: `http://127.0.0.1:${port}/mcp`, bridgeToken: 'runtime-token' };
const sessionId = 'easylsp-runtime-controlled';
const runtime = new HarnessRuntime();
const notifications = [];
runtime.on('notification', value => notifications.push(value));
runtime.on('interaction', value => runtime.answer(value.id, { outcome: 'allowed-once' }));
const timeout = setTimeout(() => { runtime.child?.kill(); server.closeAllConnections(); throw new Error('Runtime test timed out'); }, 60000);
try {
  await runtime.start(config);
  const live = new Promise(resolve => runtime.on('notification', value => {
    if (value.method === 'session.stream' && value.params.frame.chunk?.type === 'text-delta') resolve(value);
  }));
  const accepted = await runtime.rpc('session/prompt', { sessionId, contentBlocks: [{ type: 'text', text: '读取项目后回答' }] });
  assert.ok(accepted.messageId);
  await startedStream;
  const delta = await live;
  assert.equal(delta.params.frame.chunk.text, '实时中文输出');
  // Provider is still open: these must be live chunks, not committed replay.
  assert.ok(!notifications.some(n => n.method === 'session.event' && n.params.event.type === 'turn/end'));
  releaseReply();
  await runtime.rpc('session/wait', { sessionId });
  await runtime.rpc('session/prompt', { sessionId, contentBlocks: [{ type: 'text', text: '沿用连接再回答' }] });
  await runtime.rpc('session/wait', { sessionId });
  assert.equal(initializations, 1); assert.equal(listings, 2); assert.equal(calls, 1); assert.equal(requests, 3);
  const pendingLive = new Promise(resolve => runtime.on('notification', n => {
    if (requests === 4 && n.method === 'session.stream' && n.params.frame.chunk?.type === 'text-delta') resolve();
  }));
  await runtime.rpc('session/prompt', { sessionId, contentBlocks: [{ type: 'text', text: '等到停止' }] });
  await pendingLive;
  await runtime.control('session/cancel', { sessionId });
  await runtime.rpc('session/wait', { sessionId });
  await cancellation;
  assert.deepEqual(await runtime.control('session/state', { sessionId }), { status: 'idle' });
  async function readAll(client) {
    const events = []; let after = -1, page;
    do {
      page = await client.control('session/history', { sessionId, after, limit: 3 });
      assert.ok(page.events.length <= 3);
      events.push(...page.events); after = page.next;
    } while (page.hasMore);
    assert.deepEqual(events.map(e => e.seq), Array.from({ length: events.length }, (_, i) => i));
    return events;
  }
  const before = await readAll(runtime);
  assert.ok(before.some(e => e.type === 'assistant/message'));
  assert.ok(before.some(e => e.type === 'turn/end'));
  await runtime.close();
  const resumed = new HarnessRuntime();
  try {
    await resumed.start(config);
    const restored = await readAll(resumed);
    assert.deepEqual(restored, before);
    assert.equal(calls, 1); assert.equal(requests, 4);
    console.log(JSON.stringify({ runtime: '0.2.2-alpha.3', liveBeforeCommit: true, cancelledHttp: true, warmTurns: 2, initializationsPerRuntime: 1, historyEvents: before.length, paginatedRestartIdentical: true, toolsReplayed: 0 }));
  } finally { await resumed.close(); }
} finally {
  clearTimeout(timeout); runtime.child?.stdin.end(); server.closeAllConnections(); server.close(); await rm(directory, { recursive: true });
}
