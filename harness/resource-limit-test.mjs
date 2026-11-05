import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { HarnessRuntime } from './runtime.mjs';

let requests = 0, calls = 0, target = 22, waitMs = 0, toolName = 'read_execution';
const server = createServer(async (request, response) => {
  if (request.method !== 'POST') { response.writeHead(405).end(); return; }
  let raw = ''; for await (const chunk of request) raw += chunk;
  const frame = JSON.parse(raw);
  if (request.url === '/mcp') {
    if (frame.id == null) { response.writeHead(202).end(); return; }
    let result;
    if (frame.method === 'initialize') result = { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'limit-fixture', version: '1' } };
    if (frame.method === 'tools/list') result = { tools: [{ name: toolName, description: 'Read next progress milestone or wait for the user', inputSchema: { type: 'object', properties: {} } }] };
    if (frame.method === 'tools/call') {
      if (waitMs) await new Promise(resolve => setTimeout(resolve, waitMs));
      result = { content: [{ type: 'text', text: JSON.stringify({ milestone: ++calls }) }] };
    }
    response.setHeader('Content-Type', 'application/json');
    response.end(JSON.stringify({ jsonrpc: '2.0', id: frame.id, ...(result ? { result } : { error: { code: -32601, message: 'Unknown method' } }) })); return;
  }
  const index = ++requests, tool = index <= target;
  response.setHeader('Content-Type', 'text/event-stream');
  const send = (delta, finish_reason) => response.write(`data: ${JSON.stringify({ id: `reply-${index}`, model: 'fixture', choices: [{ index: 0, delta, finish_reason }] })}\n\n`);
  send(tool ? { tool_calls: [{ index: 0, id: `read-${index}`, type: 'function', function: { name: `mcp__easylsp__${toolName}`, arguments: '{}' } }] } : { content: 'All milestones complete' }, null);
  send({}, tool ? 'tool_calls' : 'stop'); response.end('data: [DONE]\n\n');
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const bridge = createServer(server.listeners('request')[0]);
await new Promise(resolve => bridge.listen(0, '127.0.0.1', resolve));
const bridgeURL = `http://127.0.0.1:${bridge.address().port}/mcp`;
const directory = await mkdtemp(join(tmpdir(), 'easylsp-limits-'));
const base = `http://127.0.0.1:${server.address().port}`;
let runtime;
const deadline = setTimeout(() => { throw new Error('Resource limit test timed out'); }, 180000);
async function run(sessionId, maxRequests) {
  requests = 0; calls = 0;
  runtime = new HarnessRuntime();
  await runtime.start({ directory: join(directory, sessionId), api: 'openai-completions', baseURL: `${base}/v1`, model: 'fixture', secret: 'fixture-key',
    contextWindow: 32768, maxTokens: 1024, timeoutMs: 1000, bridgeURL, bridgeToken: 'fixture-token', ...(maxRequests == null ? {} : { maxRequests }) });
  await runtime.rpc('session/prompt', { sessionId, contentBlocks: [{ type: 'text', text: 'Read all progress milestones' }] });
  await runtime.rpc('session/wait', { sessionId });
  const events = []; let after = -1, page;
  do {
    page = await runtime.control('session/history', { sessionId, after, limit: 200 });
    events.push(...page.events); after = page.next;
  } while (page.hasMore);
  await runtime.close(); runtime = undefined;
  return events;
}
try {
  const limited = await run('limited', 2);
  assert.equal(requests, 2); assert.equal(calls, 2);
  assert.ok(limited.some(e => e.type === 'plugin:easylsp/limit' && e.data.maxRequests === 2 && e.data.requestCount === 2));
  assert.equal(limited.findLast(e => e.type === 'turn/end').data.reason.kind, 'blocked');
  runtime = new HarnessRuntime();
  await runtime.start({ directory: join(directory, 'limited'), api: 'openai-completions', baseURL: `${base}/v1`, model: 'fixture', secret: 'fixture-key',
    contextWindow: 32768, maxTokens: 1024, timeoutMs: 1000, maxRequests: 2, bridgeURL, bridgeToken: 'fixture-token' });
  await runtime.rpc('session/resume', { sessionId: 'limited' });
  assert.equal(requests, 2); assert.equal(calls, 2); // Restoring never executes anything.
  target = 2;
  await runtime.rpc('session/prompt', { sessionId: 'limited', contentBlocks: [{ type: 'text', text: 'Continue the same task' }] });
  await runtime.rpc('session/wait', { sessionId: 'limited' });
  assert.equal(requests, 3); assert.equal(calls, 2);
  await runtime.close(); runtime = undefined; target = 22;
  const unbounded = await run('unbounded');
  assert.equal(requests, 23); assert.equal(calls, 22);
  assert.equal(unbounded.findLast(e => e.type === 'turn/end').data.reason.kind, 'completed');
  assert.ok(!unbounded.some(e => e.type === 'plugin:easylsp/limit'));
  target = 1; waitMs = 65000; toolName = 'ask_user';
  const waited = await run('user-wait');
  assert.equal(requests, 2); assert.equal(calls, 1);
  assert.equal(waited.findLast(e => e.type === 'turn/end').data.reason.kind, 'completed');
  console.log(JSON.stringify({ explicitLimit: 2, limitedRequests: 2, limitedToolCalls: 2, noImplicitTwentyStepLimit: true, completedRequests: 23, completedToolCalls: 22,
    userWaitMs: waitMs, modelTimeoutMs: 1000, waitOutlivesMcpDefault: true }));
} finally {
  clearTimeout(deadline); await runtime?.close(); bridge.closeAllConnections(); bridge.close(); server.closeAllConnections(); server.close(); await rm(directory, { recursive: true });
}
