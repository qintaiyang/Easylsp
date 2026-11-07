import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import assert from 'node:assert/strict';

let requests = 0;
let calls = 0;
let initializations = 0;
let rejectedRequests = 0;
const server = createServer(async (request, response) => {
  if (request.method !== 'POST') { response.writeHead(405).end(); return; }
  let data = ''; for await (const chunk of request) data += chunk;
  const frame = JSON.parse(data);
  if (request.url.startsWith('/rejected/')) { rejectedRequests++; response.writeHead(401, { 'Content-Type': 'application/json' }).end(JSON.stringify({ error: { message: 'fixture credential rejected', type: 'authentication_error' } })); return; }
  if (request.url === '/mcp') {
    assert.equal(request.headers.authorization, 'Bearer probe-token');
    if (frame.id == null) { response.writeHead(202).end(); return; }
    let result;
    if (frame.method === 'initialize') { initializations++; result = { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'fixture', version: '1' } }; }
    if (frame.method === 'tools/list') result = { tools: [{ name: 'get_project_info', description: 'Read project binding', inputSchema: { type: 'object', properties: {}, additionalProperties: false } }] };
    if (frame.method === 'tools/call') { calls++; result = { content: [{ type: 'text', text: '{"packageName":"fixture.demo","sampleRevision":1}' }] }; }
    if (frame.method === 'ping') result = {};
    response.setHeader('Content-Type', 'application/json');
    response.end(JSON.stringify({ jsonrpc: '2.0', id: frame.id, ...(result ? { result } : { error: { code: -32601, message: `Unknown method: ${frame.method}` } }) })); return;
  }
  requests++;
  const tool = frame.tools.find(tool => tool.function.name === 'mcp__easylsp__get_project_info');
  assert.ok(tool, `MCP tool must be available to the real Harness runtime: ${frame.tools.map(tool => tool.function.name).join(', ')}`);
  const delta = requests % 2 ? { role: 'assistant', tool_calls: [{ index: 0, id: `call-${requests}`, type: 'function', function: { name: tool.function.name, arguments: '{}' } }] } : { role: 'assistant', content: 'fixture.demo，样本 r1，已读取实际结果。' };
  const event = { id: `reply-${requests}`, object: 'chat.completion.chunk', model: 'fixture', choices: [{ index: 0, delta, finish_reason: requests % 2 ? 'tool_calls' : 'stop' }], usage: { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 } };
  response.setHeader('Content-Type', 'text/event-stream');
  response.end(`data: ${JSON.stringify(event)}\n\ndata: [DONE]\n\n`);
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const port = server.address().port;
const directory = await mkdtemp(join(tmpdir(), 'easylsp-harness-probe-'));
try {
  async function run(resume, rejected = false) {
    const process = spawn(globalThis.process.execPath, [new URL('probe.mjs', import.meta.url).pathname], { stdio: ['pipe', 'pipe', 'pipe'] });
    let output = ''; let error = '';
    process.stdout.on('data', value => output += value); process.stderr.on('data', value => error += value);
    process.stdin.write(JSON.stringify({ directory, api: 'openai-completions', baseURL: `http://127.0.0.1:${port}/${rejected ? 'rejected' : 'v1'}`, model: 'fixture', secret: 'fixture-key', ...(rejected ? {} : { bridgeURL: `http://127.0.0.1:${port}/mcp`, bridgeToken: 'probe-token' }), sessionId: rejected ? 'easylsp-probe-rejected' : 'easylsp-h0-controlled', resume }) + '\n');
    const timeout = setTimeout(() => process.kill(), 60000);
    const code = await new Promise(resolve => process.once('exit', resolve)); clearTimeout(timeout);
    if (rejected) { assert.notEqual(code, 0); assert.ok(!output.includes('"probe":"complete"')); assert.ok(error.includes('模型协议测试未完成'), error); return; }
    assert.equal(code, 0, error + '\n' + output);
    assert.ok(output.includes('"probe":"complete"'), output);
    assert.ok(output.includes('fixture.demo'), output);
    return output.split('\n').filter(line => line.includes('"probe"')).map(JSON.parse);
  }
  const cold = await run(false);
  const resumed = await run(true);
  assert.equal(calls, 2); assert.equal(requests, 4); assert.equal(initializations, 2);
  await run(false, true); assert.equal(rejectedRequests, 1);
  console.log(JSON.stringify({ runtime: '0.2.2-alpha.3', source: 'controlled model; real Harness SDK and MCP transport', requests, calls, initializations, cold, resumed }, null, 2));
} finally { server.closeAllConnections(); server.close(); await rm(directory, { recursive: true }); }
