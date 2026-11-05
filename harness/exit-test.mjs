import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import WebSocket from 'ws';

// Real SDK/daemon termination, controlled write result withheld after its effect.
let requests = 0, effects = 0, releaseWrite, writeStarted;
const started = new Promise(resolve => { writeStarted = resolve; });
const provider = createServer(async (request, response) => {
  if (request.method !== 'POST') { response.writeHead(405).end(); return; }
  let raw = ''; for await (const chunk of request) raw += chunk;
  const frame = JSON.parse(raw);
  if (request.url === '/mcp') {
    if (frame.id == null) { response.writeHead(202).end(); return; }
    let result;
    if (frame.method === 'initialize') result = { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'fixture', version: '1' } };
    if (frame.method === 'tools/list') result = { tools: [{ name: 'load_plugin', description: 'Controlled write', inputSchema: { type: 'object', properties: {} } }] };
    if (frame.method === 'tools/call') {
      effects++; writeStarted(); await new Promise(resolve => { releaseWrite = resolve; });
      result = { content: [{ type: 'text', text: 'write applied' }] };
    }
    response.setHeader('Content-Type', 'application/json');
    response.end(JSON.stringify({ jsonrpc: '2.0', id: frame.id, result: result ?? {} })); return;
  }
  requests++;
  const event = (delta, finish_reason) => `data: ${JSON.stringify({ id: 'reply', model: 'fixture', choices: [{ index: 0, delta, finish_reason }] })}\n\n`;
  response.setHeader('Content-Type', 'text/event-stream');
  response.end(event({ tool_calls: [{ index: 0, id: 'write-one', type: 'function', function: { name: 'mcp__easylsp__load_plugin', arguments: '{}' } }] }, null) + event({}, 'tool_calls') + 'data: [DONE]\n\n');
});
await new Promise(resolve => provider.listen(0, '127.0.0.1', resolve));
const directory = await mkdtemp(join(tmpdir(), 'easylsp-exit-'));
const token = 'exit-fixture', sessionId = 'exit-session';
const config = { api: 'openai-completions', baseURL: `http://127.0.0.1:${provider.address().port}/v1`, model: 'fixture', secret: 'fixture-key', bridgeURL: `http://127.0.0.1:${provider.address().port}/mcp`, bridgeToken: token };
let daemon, client;
async function boot() {
  const launcher = spawn(process.execPath, [new URL('boot.mjs', import.meta.url).pathname], { stdio: ['pipe', 'pipe', 'inherit'] });
  let output = ''; launcher.stdout.on('data', value => { output += value; });
  launcher.stdin.end(JSON.stringify({ token, directory }) + '\n');
  assert.equal(await new Promise(resolve => launcher.once('close', resolve)), 0);
  return JSON.parse(output);
}
async function connect(port) {
  const socket = new WebSocket(`ws://127.0.0.1:${port}/session`, { headers: { Authorization: `Bearer ${token}` } });
  const pending = new Map(); let sequence = 0;
  socket.on('message', raw => {
    const frame = JSON.parse(raw);
    if (frame.id != null) {
      const call = pending.get(frame.id); pending.delete(frame.id);
      if (frame.error) call.reject(new Error(frame.error.message)); else call.resolve(frame.result);
    }
  });
  await new Promise((resolve, reject) => { socket.once('open', resolve); socket.once('error', reject); });
  return {
    socket,
    rpc(method, params = {}) { return new Promise((resolve, reject) => {
      const id = ++sequence; pending.set(id, { resolve, reject }); socket.send(JSON.stringify({ id, method, params: { sessionId, ...params } }));
    }); },
    until(method) { return new Promise(resolve => {
      const listener = raw => { const frame = JSON.parse(raw); if (frame.method === method) { socket.off('message', listener); resolve(frame.params); } };
      socket.on('message', listener);
    }); },
  };
}
async function sdkPid() {
  const pids = (await readFile(`/proc/${daemon.pid}/task/${daemon.pid}/children`, 'utf8')).trim().split(/\s+/);
  for (const pid of pids) if ((await readFile(`/proc/${pid}/cmdline`, 'utf8')).includes('node_modules/.bin/dsh')) return Number(pid);
  throw new Error('No SDK child');
}
const timeout = setTimeout(() => { throw new Error('Exit test timed out'); }, 60000);
try {
  daemon = await boot(); client = await connect(daemon.port);
  await client.rpc('session/open', { config });
  const accepted = await client.rpc('session/prompt', { requestId: 'write-input', contentBlocks: [{ type: 'text', text: 'Apply controlled write' }] });
  await started;
  const exit = client.until('host.engine-exit');
  process.kill(await sdkPid(), 'SIGKILL');
  assert.equal((await exit).signal, 'SIGKILL');
  assert.equal((await client.rpc('host/status')).status, 'exited');
  releaseWrite();
  await client.rpc('session/open', { config, resume: true });
  const history = await client.rpc('session/history', { after: -1, limit: 200 });
  assert.equal(history.events.filter(event => event.type === 'tool/call').length, 1);
  assert.equal(effects, 1); assert.equal(requests, 1);
  assert.deepEqual(await client.rpc('session/receipt', { requestId: 'write-input' }), accepted);
  const orphan = await sdkPid();
  const closed = new Promise(resolve => client.socket.once('close', resolve));
  process.kill(daemon.pid, 'SIGKILL'); await closed;
  // EOF belongs to the killed host's pipe; wait for that SDK's actual exit.
  for (let i = 0; i < 100; i++) {
    const stat = await readFile(`/proc/${orphan}/stat`, 'utf8').catch(error => { if (error.code === 'ENOENT') return ''; throw error; });
    if (!stat || stat.split(' ')[2] === 'Z') break;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  daemon = await boot(); client = await connect(daemon.port);
  await client.rpc('session/open', { config, resume: true });
  assert.deepEqual(await client.rpc('session/receipt', { requestId: 'write-input' }), accepted);
  assert.equal(effects, 1); assert.equal(requests, 1);
  assert.ok((await client.rpc('session/history', { after: -1, limit: 200 })).events.length >= history.events.length);
  console.log(JSON.stringify({ sdkKilledDuringUnknownWrite: true, engineExitDelivered: true, appliedWrites: effects, unknownWritesReplayed: 0, daemonKilledAndRestored: true, receiptPreserved: true, resumeModelRequests: 0 }));
} finally {
  clearTimeout(timeout); releaseWrite?.();
  if (client?.socket.readyState === WebSocket.OPEN) {
    const closed = new Promise(resolve => client.socket.once('close', resolve));
    await client.rpc('host/stop'); await closed;
  }
  provider.closeAllConnections(); provider.close(); await rm(directory, { recursive: true });
}
