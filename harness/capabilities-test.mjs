import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, rm } from 'node:fs/promises';
import { HarnessRuntime } from './runtime.mjs';

const defaults = ['run_command', 'search_tools', 'select_tools'];
const catalog = [...defaults, 'read_plugin', 'future_tool'];
const selected = new Set();
let active = new Set(), version = 0, resolutions = 0, requests = 0, listings = 0, initializations = 0;
const invoked = [], events = [];
const snapshot = () => ({ toolSetVersion: version, selectedTools: [...new Set([...defaults, ...selected])], activeTools: [...active] });
const server = createServer(async (request, response) => {
  if (request.method !== 'POST') { response.writeHead(405).end(); return; }
  let raw = ''; for await (const chunk of request) raw += chunk;
  const frame = JSON.parse(raw);
  if (request.url === '/mcp') {
    assert.equal(request.headers.authorization, 'Bearer fixture');
    if (frame.id == null) { response.writeHead(202).end(); return; }
    let result;
    if (frame.method === 'initialize') { initializations++; result = { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'fixture', version: '1' } }; }
    if (frame.method === 'tools/list') {
      listings++;
      result = { tools: catalog.map(name => ({ name, description: name, inputSchema: name === 'select_tools'
        ? { type: 'object', properties: { names: { type: 'array', items: { type: 'string' } }, mode: { type: 'string' } }, required: ['names'] }
        : { type: 'object', properties: {} } })) };
    }
    if (frame.method === 'easylsp/capabilities/resolve') {
      resolutions++; active = new Set([...defaults, ...selected]); result = snapshot();
    }
    if (frame.method === 'easylsp/capabilities/read') result = snapshot();
    if (frame.method === 'tools/call') {
      const { name, arguments: args } = frame.params;
      assert.ok(active.has(name), `Android must only receive tools admitted for this step: ${name}`);
      invoked.push(name);
      if (name === 'select_tools') {
        for (const item of args.names) { if (args.mode === 'remove') selected.delete(item); else selected.add(item); }
        version++;
      }
      result = { content: [{ type: 'text', text: name === 'read_plugin' ? 'important original evidence' : JSON.stringify(snapshot()) }] };
    }
    response.setHeader('Content-Type', 'application/json');
    response.end(JSON.stringify({ jsonrpc: '2.0', id: frame.id, ...(result ? { result } : { error: { code: -32601, message: frame.method } }) })); return;
  }
  const step = ++requests;
  const declared = frame.tools.map(tool => tool.function.name.replace('mcp__easylsp__', '')).sort();
  assert.deepEqual(declared, [...active].sort());
  if (step === 1) {
    assert.deepEqual(declared, defaults);
    assert.ok(!JSON.stringify(frame.messages).includes('read_plugin'), 'unselected directory must not leak into the prompt');
  }
  if (step === 2) assert.ok(declared.includes('read_plugin'));
  if (step === 3) assert.ok(declared.includes('read_plugin') && declared.includes('future_tool'));
  if (step >= 4) {
    assert.ok(!declared.includes('read_plugin') && declared.includes('future_tool'));
    assert.ok(raw.includes('important original evidence'), 'releasing tools must preserve earlier results');
  }
  const calls = step === 1 ? [['run_command', {}], ['select_tools', { names: ['read_plugin'] }], ['read_plugin', {}]]
    : step === 2 ? [['read_plugin', {}], ['select_tools', { names: ['future_tool'] }]]
    : step === 3 ? [['select_tools', { names: ['read_plugin'], mode: 'remove' }], ['read_plugin', {}]] : [];
  const delta = calls.length ? { tool_calls: calls.map(([name, args], index) => ({ index, id: `call-${step}-${index}`, type: 'function', function: { name: `mcp__easylsp__${name}`, arguments: JSON.stringify(args) } })) } : { content: 'Completed with original evidence' };
  response.setHeader('Content-Type', 'text/event-stream');
  response.end(`data: ${JSON.stringify({ id: `reply-${step}`, choices: [{ index: 0, delta, finish_reason: calls.length ? 'tool_calls' : 'stop' }] })}\n\ndata: [DONE]\n\n`);
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const directory = await mkdtemp('/tmp/easylsp-capabilities-');
const base = `http://127.0.0.1:${server.address().port}`;
const config = { directory, api: 'openai-completions', baseURL: `${base}/v1`, secret: 'fixture', model: 'fixture', contextWindow: 1000000, contextBudget: 200000, maxTokens: 1024, bridgeURL: `${base}/mcp`, bridgeToken: 'fixture', capabilities: true };
let runtime = new HarnessRuntime();
const sessionId = 'capabilities';
const timeout = setTimeout(() => { runtime.child?.kill(); server.closeAllConnections(); throw new Error('Capability test timed out'); }, 60000);
try {
  runtime.on('notification', frame => { if (frame.method === 'session.event') events.push(frame.params.event); });
  await runtime.start(config);
  await runtime.rpc('session/prompt', { sessionId, contentBlocks: [{ type: 'text', text: 'Use tools without any Skill and keep the evidence' }] });
  await runtime.rpc('session/wait', { sessionId });
  assert.equal(requests, 4);
  assert.equal(invoked.filter(name => name === 'read_plugin').length, 2, 'same-batch selection must not admit an undeclared call');
  assert.ok(events.some(event => event.type === 'tool/result' && event.data.message.isError), 'undeclared call must produce a real error');
  const context = await runtime.control('session/context/read', { sessionId });
  assert.deepEqual(context.selectedTools.sort(), [...defaults, 'future_tool'].sort());
  assert.ok(context.system.length > 0);
  assert.ok(context.sources.some(source => source.role === 'tool' && source.event.type === 'tool/result'));
  assert.equal(context.capacity.configuredContextWindow, 1000000);
  assert.equal(context.capacity.contextBudget, 200000);
  assert.ok(context.size.textCharacters > 0);
  assert.equal(initializations, 1); assert.equal(listings, 2, 'one SDK listing plus the Android effect metadata read; selections add no listings');
  await runtime.close();
  runtime = new HarnessRuntime();
  await runtime.start(config);
  await runtime.rpc('session/resume', { sessionId });
  await runtime.rpc('session/prompt', { sessionId, contentBlocks: [{ type: 'text', text: 'Continue from the original evidence' }] });
  await runtime.rpc('session/wait', { sessionId });
  assert.equal(requests, 5); assert.equal(resolutions, requests, 'one admission per step');
  assert.equal(invoked.filter(name => name === 'read_plugin').length, 2, 'resume must not replay tool effects');
  console.log(JSON.stringify({ requests, oneSnapshotPerStep: true, nextStepSelection: true, sameBatchDenied: true, historyRetained: true, resumedSelection: true, noToolReplay: true }));
} finally {
  clearTimeout(timeout); await runtime.close(); server.closeAllConnections(); server.close(); await rm(directory, { recursive: true });
}
