import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, rm } from 'node:fs/promises';
import { HarnessRuntime } from './runtime.mjs';

let active = 0, peak = 0, requests = 0, cancelledTurn = false;
const order = [], events = [];
const tools = ['read_plugin', 'read_plugin_logs', 'edit_plugin', 'read_manifest'];
const server = createServer(async (request, response) => {
  if (request.method !== 'POST') { response.writeHead(405).end(); return; }
  let raw = ''; for await (const chunk of request) raw += chunk;
  const frame = JSON.parse(raw);
  if (request.url === '/mcp') {
    if (frame.id == null) { response.writeHead(202).end(); return; }
    let result;
    if (frame.method === 'initialize') result = { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'fixture', version: '1' } };
    if (frame.method === 'tools/list') result = { tools: tools.map(name => ({ name, description: name, annotations: { readOnlyHint: ['read_plugin', 'read_plugin_logs'].includes(name) }, inputSchema: { type: 'object', properties: {} } })) };
    if (frame.method === 'tools/call') {
      const name = frame.params.name;
      if (name === 'edit_plugin' || name === 'read_manifest') assert.equal(active, 0, 'exclusive tool must wait for reads');
      active++; peak = Math.max(peak, active); order.push(name);
      await new Promise(resolve => setTimeout(resolve, cancelledTurn ? 200 : 40));
      active--; result = { content: [{ type: 'text', text: name }] };
    }
    response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify({ jsonrpc: '2.0', id: frame.id, ...(result ? {result} : {error:{code:-32601,message:'Unknown method'}}) })); return;
  }
  requests++;
  const names = frame.messages.at(-1).role === 'tool' ? [] : ['read_plugin', 'read_plugin_logs', 'read_plugin', 'edit_plugin', 'read_manifest', 'read_plugin'];
  const delta = names.length ? { tool_calls: names.map((name, index) => ({ index, id: `call-${requests}-${index}`, type: 'function', function: { name: `mcp__easylsp__${name}`, arguments: '{}' } })) } : { content: 'All actual results received' };
  response.setHeader('Content-Type', 'text/event-stream');
  response.end(`data: ${JSON.stringify({ id: 'fixture', choices: [{ index: 0, delta, finish_reason: names.length ? 'tool_calls' : 'stop' }] })}\n\ndata: [DONE]\n\n`);
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const directory = await mkdtemp('/tmp/easylsp-scheduling-');
const runtime = new HarnessRuntime();
runtime.on('notification', frame => { if (frame.method === 'session.event') events.push(frame.params.event); });
const base = `http://127.0.0.1:${server.address().port}`;
try {
  await runtime.start({ maxParallelToolCalls: 3, directory, api: 'openai-completions', baseURL: `${base}/v1`, secret: 'fixture', model: 'fixture', contextWindow: 1000000, contextBudget: 200000, maxTokens: 1024, bridgeURL: `${base}/mcp`, bridgeToken: 'fixture' });
  const sessionId = 'scheduling';
  await runtime.rpc('session/prompt', { sessionId, contentBlocks: [{ type: 'text', text: 'Run independent reads, then write, then analysis' }] });
  await runtime.rpc('session/wait', { sessionId });
  assert.equal(peak, 3); assert.deepEqual(order, ['read_plugin', 'read_plugin_logs', 'read_plugin', 'edit_plugin', 'read_manifest', 'read_plugin']);
  assert.equal(events.filter(event => event.type === 'tool/result').length, 6);
  cancelledTurn = true;
  const before = order.length;
  await runtime.rpc('session/prompt', { sessionId, contentBlocks: [{ type: 'text', text: 'Cancel pending batch' }] });
  while (order.length < before + 3) await new Promise(resolve => setTimeout(resolve, 5));
  await runtime.control('session/cancel', { sessionId });
  await runtime.rpc('session/wait', { sessionId });
  assert.equal(order.length, before + 3, 'cancel must not start queued tools');
  console.log(JSON.stringify({ maxConcurrentReads: peak, exclusiveWriteAndAnalysis: true, orderedResults: 6, queuedToolsStartedAfterCancel: 0 }));
} finally { await runtime.close(); server.closeAllConnections(); server.close(); await rm(directory, { recursive: true }); }
