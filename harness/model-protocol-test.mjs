import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { HarnessRuntime } from './runtime.mjs';

const image = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=';
const toolName = 'mcp__easylsp__get_project_info';
let activeApi, requests, calls, switching = false;
const server = createServer(async (request, response) => {
  if (request.method !== 'POST') { response.writeHead(405).end(); return; }
  let raw = ''; for await (const chunk of request) raw += chunk;
  const body = JSON.parse(raw);
  if (request.url === '/mcp') {
    if (body.id == null) { response.writeHead(202).end(); return; }
    let result;
    if (body.method === 'initialize') result = { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'fixture', version: '1' } };
    if (body.method === 'tools/list') result = { tools: [{ name: 'get_project_info', description: 'Read project', inputSchema: { type: 'object', properties: {} } }] };
    if (body.method === 'tools/call') { calls++; result = { content: [{ type: 'text', text: '{"packageName":"fixture.demo"}' }] }; }
    response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify({ jsonrpc: '2.0', id: body.id, ...(result ? { result } : { error: { code: -32601, message: 'Unknown method' } }) })); return;
  }
  const index = ++requests;
  const first = index === 1;
  assert.match(raw, /data:image\/|"inlineData"|"inline_data"|"type":"base64"/, `${activeApi}: image must survive request history after SDK normalization`);
  if (!first) assert.ok(raw.includes('fixture.demo'), `${activeApi}: tool result must be in next request`);
  response.setHeader('Content-Type', 'text/event-stream');
  const event = (type, data) => response.write(`${type ? `event: ${type}\n` : ''}data: ${JSON.stringify(data)}\n\n`);
  if (activeApi === 'openai-completions') {
    assert.equal(body.reasoning_effort, 'low'); assert.equal(body.max_tokens, 4096);
    const delta = first ? { role: 'assistant', reasoning_content: '实际思考', tool_calls: [{ index: 0, id: 'call-1', type: 'function', function: { name: toolName, arguments: '{}' } }] } : { role: 'assistant', content: 'fixture complete' };
    event(null, { id: `reply-${index}`, object: 'chat.completion.chunk', model: 'fixture', choices: [{ index: 0, delta, finish_reason: first ? 'tool_calls' : 'stop' }], usage: { prompt_tokens: 50, completion_tokens: 10, total_tokens: 60 } });
    response.end('data: [DONE]\n\n');
  } else if (activeApi === 'anthropic-messages') {
    assert.equal(request.headers['x-api-key'], 'fixture-key');
    assert.equal(body.thinking.budget_tokens, 1024);
    if (!first && !switching) assert.ok(raw.includes('fixture-signature'), 'Anthropic thinking signature must survive tool roundtrip');
    event('message_start', { type: 'message_start', message: { id: `msg-${index}`, type: 'message', role: 'assistant', model: 'fixture', content: [], usage: { input_tokens: 50, output_tokens: 0 } } });
    if (first) {
      event('content_block_start', { type: 'content_block_start', index: 0, content_block: { type: 'thinking', thinking: '', signature: '' } });
      event('content_block_delta', { type: 'content_block_delta', index: 0, delta: { type: 'thinking_delta', thinking: '实际思考' } });
      event('content_block_delta', { type: 'content_block_delta', index: 0, delta: { type: 'signature_delta', signature: 'fixture-signature' } });
      event('content_block_stop', { type: 'content_block_stop', index: 0 });
      event('content_block_start', { type: 'content_block_start', index: 1, content_block: { type: 'tool_use', id: 'call-1', name: toolName, input: {} } });
      event('content_block_delta', { type: 'content_block_delta', index: 1, delta: { type: 'input_json_delta', partial_json: '{}' } });
      event('content_block_stop', { type: 'content_block_stop', index: 1 });
    } else {
      event('content_block_start', { type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } });
      event('content_block_delta', { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'fixture complete' } });
      event('content_block_stop', { type: 'content_block_stop', index: 0 });
    }
    event('message_delta', { type: 'message_delta', delta: { stop_reason: first ? 'tool_use' : 'end_turn', stop_sequence: null }, usage: { output_tokens: 10 } });
    event('message_stop', { type: 'message_stop' }); response.end();
  } else if (activeApi === 'gemini') {
    assert.ok(request.url.includes(':streamGenerateContent'));
    assert.equal(body.generationConfig.thinkingConfig.thinkingBudget, 1024);
    event(null, { candidates: [{ index: 0, content: { role: 'model', parts: first ? [{ functionCall: { name: toolName, args: {} }, thoughtSignature: 'Zml4dHVyZQ==' }] : [{ text: 'fixture complete' }] }, finishReason: 'STOP' }], usageMetadata: { promptTokenCount: 50, candidatesTokenCount: 10, totalTokenCount: 60 }, modelVersion: 'gemini-2.5-flash' }); response.end();
  } else {
    assert.equal(body.reasoning.effort, 'low'); assert.equal(body.max_output_tokens, 4096);
    const item = first ? { id: 'fc-1', type: 'function_call', call_id: 'call-1', name: toolName, arguments: '{}', status: 'completed' } : { id: 'msg-1', type: 'message', role: 'assistant', content: [{ type: 'output_text', text: 'fixture complete', annotations: [] }], status: 'completed' };
    const reply = { id: `resp-${index}`, object: 'response', model: 'fixture', status: 'completed', output: [item], usage: { input_tokens: 50, output_tokens: 10, total_tokens: 60 } };
    event('response.created', { type: 'response.created', response: { ...reply, status: 'in_progress', output: [] } });
    event('response.output_item.added', { type: 'response.output_item.added', output_index: 0, item: first ? { ...item, arguments: '', status: 'in_progress' } : { ...item, content: [], status: 'in_progress' } });
    if (first) event('response.function_call_arguments.delta', { type: 'response.function_call_arguments.delta', item_id: item.id, output_index: 0, delta: '{}' });
    else {
      event('response.content_part.added', { type: 'response.content_part.added', item_id: item.id, output_index: 0, content_index: 0, part: { type: 'output_text', text: '', annotations: [] } });
      event('response.output_text.delta', { type: 'response.output_text.delta', item_id: item.id, output_index: 0, content_index: 0, delta: 'fixture complete' });
    }
    event('response.output_item.done', { type: 'response.output_item.done', output_index: 0, item });
    event('response.completed', { type: 'response.completed', response: reply }); response.end();
  }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;
const directory = await mkdtemp(join(tmpdir(), 'easylsp-model-protocol-'));
const results = [];
try {
  for (const api of ['openai-completions', 'openai-responses', 'anthropic-messages', 'gemini']) {
    activeApi = api; requests = 0; calls = 0;
    const runtime = new HarnessRuntime(); const events = [];
    runtime.on('notification', frame => { if (frame.method === 'session.event') events.push(frame.params.event); });
    runtime.on('interaction', frame => runtime.answer(frame.id, { outcome: 'allowed-once' }));
    const timer = setTimeout(() => runtime.child.kill(), 30000);
    try {
      await runtime.start({ directory: join(directory, api), api, baseURL: `${base}/v1`, model: api === 'gemini' ? 'gemini-2.5-flash' : 'fixture', contextWindow: 32768, supportsImages: true, maxTokens: 4096,
        ...(api === 'openai-completions' ? { outputTokenField: 'max_tokens' } : {}),
        ...(api.startsWith('openai') ? { reasoningEffort: 'low' } : { thinkingBudget: 1024 }),
        secret: 'fixture-key', bridgeURL: `${base}/mcp`, bridgeToken: 'fixture-token' });
      await runtime.rpc('session/prompt', { sessionId: `protocol-${api}`, contentBlocks: [{ type: 'text', text: '读取项目信息后回答' }, { type: 'image', data: image, mimeType: 'image/png' }] });
      await runtime.rpc('session/wait', { sessionId: `protocol-${api}` });
      assert.equal(requests, 2, api); assert.equal(calls, 1, api);
      const replies = events.filter(e => e.type === 'assistant/message');
      assert.equal(replies.length, 2, api);
      assert.ok(JSON.stringify(replies.at(-1)).includes('fixture complete'), api);
      assert.ok(replies.at(-1).data?.usage ?? replies.at(-1).usage, `${api}: provider usage must be retained`);
      results.push({ api, requests, calls, imageTransported: true, explicitReasoning: true, usage: true });
    } finally { clearTimeout(timer); await runtime.close(); }
  }
  switching = true; requests = 0; calls = 0;
  const switched = [];
  for (const api of ['anthropic-messages', 'gemini', 'openai-completions', 'openai-responses', 'anthropic-messages']) {
    activeApi = api;
    const runtime = new HarnessRuntime();
    runtime.on('interaction', frame => runtime.answer(frame.id, { outcome: 'allowed-once' }));
    const switchDeadline = setTimeout(() => runtime.child.kill(), 30000);
    try {
      await runtime.start({ directory: join(directory, 'switch'), api, baseURL: `${base}/v1`, model: api === 'gemini' ? 'gemini-2.5-flash' : 'fixture',
        contextWindow: 32768, maxTokens: 4096, supportsImages: true, ...(api === 'openai-completions' ? { outputTokenField: 'max_tokens' } : {}), timeoutMs: 30000,
        ...(api.startsWith('openai') ? { reasoningEffort: 'low' } : { thinkingBudget: 1024 }), secret: 'fixture-key', bridgeURL: `${base}/mcp`, bridgeToken: 'fixture-token' });
      if (switched.length) await runtime.rpc('session/resume', { sessionId: 'switch' });
      const before = requests;
      await runtime.rpc('session/prompt', { sessionId: 'switch', contentBlocks: switched.length ? [{ type: 'text', text: 'Continue with selected model' }] : [{ type: 'text', text: 'Read project' }, { type: 'image', data: image, mimeType: 'image/png' }] });
      await runtime.rpc('session/wait', { sessionId: 'switch' });
      const history = await runtime.control('session/history', { sessionId: 'switch', after: -1, limit: 200 });
      assert.equal(history.events.findLast(e => e.type === 'turn/end').data.reason.kind, 'completed', api);
      assert.equal(requests - before, switched.length ? 1 : 2); assert.equal(calls, 1);
      assert.ok(JSON.stringify(history.events).includes('fixture-signature'), 'Original thinking signature remains in session history');
      switched.push(api);
    } finally { clearTimeout(switchDeadline); await runtime.close(); }
  }
  console.log(JSON.stringify({ source: 'controlled wire responses; real pinned SDK adapters', results, switched }));
} finally { server.closeAllConnections(); server.close(); await rm(directory, { recursive: true }); }
