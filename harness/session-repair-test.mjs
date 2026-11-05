import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, mkdir, copyFile, readFile, rm } from 'node:fs/promises';
import { zstdDecompressSync } from 'node:zlib';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { repairLimitHistory } from './session-repair.mjs';
import { HarnessRuntime } from './runtime.mjs';

// Optional private fixture; never include conversation content or credentials in the repo.
const original = process.env.EASYLSP_REPAIR_FIXTURE;
assert.ok(original, 'Set EASYLSP_REPAIR_FIXTURE to the recorded session artifact');
const id = 'a4d4c081-f27f-4dd8-9cf4-af00ae8963f6';
const directory = await mkdtemp(join(tmpdir(), 'easylsp-limit-repair-'));
const path = join(directory, 'sessions', `--var-lib-easylsp-harness-${id}--`, id, 'session.v4.jsonl.zstd');
await mkdir(join(directory, 'sessions', `--var-lib-easylsp-harness-${id}--`, id), { recursive: true }); await copyFile(original, path);
let requests = 0, calls = 0;
const server = createServer(async (request, response) => {
  if (request.method !== 'POST') { response.writeHead(405).end(); return; }
  let raw = ''; for await (const chunk of request) raw += chunk;
  if (request.url !== '/mcp') { requests++; response.writeHead(500).end(); return; }
  const frame = JSON.parse(raw);
  if (frame.id == null) { response.writeHead(202).end(); return; }
  if (frame.method === 'tools/call') calls++;
  const result = frame.method === 'initialize' ? { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'repair-fixture', version: '1' } } : { tools: [] };
  response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify({ jsonrpc: '2.0', id: frame.id, result }));
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;
const runtime = new HarnessRuntime();
try {
  assert.equal(await repairLimitHistory(directory, id), true);
  assert.equal(await repairLimitHistory(directory, id), false);
  await runtime.start({ directory, api: 'openai-completions', baseURL: `${base}/v1`, model: 'fixture', secret: 'fixture', contextWindow: 200000, maxTokens: 4096, bridgeURL: `${base}/mcp`, bridgeToken: 'fixture' });
  await runtime.rpc('session/resume', { sessionId: id });
  const events = []; let after = -1, page;
  do { page = await runtime.control('session/history', { sessionId: id, after, limit: 200 }); events.push(...page.events); after = page.next; } while (page.hasMore);
  const source = await readFile(original); const decoded = []; let offset = 0;
  while (offset < source.length) { const frame = zstdDecompressSync(source.subarray(offset), { info: true }); decoded.push(frame.buffer.toString()); offset += frame.engine.bytesWritten; }
  const originalEvents = decoded.join('').trimEnd().split('\n').slice(1).map(JSON.parse);
  originalEvents[236].ignorable = true;
  assert.deepEqual(events.slice(0, 311), originalEvents); assert.ok(after >= 310);
  assert.ok(events.slice(311).every(e => !['tool/call', 'step/start', 'user/message', 'assistant/message'].includes(e.type)));
  assert.equal(events[236].type, 'easylsp/limit'); assert.equal(events[236].ignorable, true);
  assert.equal(requests, 0); assert.equal(calls, 0);
  console.log(JSON.stringify({ events: events.length, cursor: after, restoredThrough: 310, resumeEvents: events.slice(311).map(e => e.type), repairedSeq: 236, modelRequests: requests, toolsReplayed: calls, restored: true }));
} finally { await runtime.close(); server.closeAllConnections(); server.close(); await rm(directory, { recursive: true }); }
