// H0 probe: credentials arrive on stdin and are passed only through the child environment.
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { writeFile, mkdir } from 'node:fs/promises';
import { performance } from 'node:perf_hooks';
import { fileURLToPath } from 'node:url';

const lines = createInterface({ input: process.stdin });
const config = await new Promise(resolve => lines.once('line', line => resolve(JSON.parse(line))));
const directory = config.directory ?? '/opt/easylsp-harness/probe';
await mkdir(directory, { recursive: true });
const patch = [
  { id: 'llm-pi-ai', config: { providers: { easylsp: {
    api: config.api, baseURL: config.baseURL, apiKeyEnv: 'EASYLSP_MODEL_KEY',
    retryPolicy: { mode: 'normal', maxRetries: 0 },
    models: [{ id: config.model, ...(config.contextWindow > 0 ? { contextWindow: config.contextWindow } : {}) }],
  } } } },
  { insert: [{ id: 'easylsp-mcp', name: fileURLToPath(new URL('android-mcp.mjs', import.meta.url)), config: {
    serverName: 'easylsp', transport: 'streamable-http', url: config.bridgeURL,
    reconnect: { enabled: false }, failOnStartupError: true,
  } }] },
];
await writeFile(`${directory}/patch.json`, JSON.stringify(patch));
const started = performance.now();
const child = spawn(fileURLToPath(new URL('node_modules/.bin/dsh', import.meta.url)), ['--profile', 'sdk', '--patch', `${directory}/patch.json`], {
  env: { ...process.env, DSH_HOME: directory, EASYLSP_MODEL_KEY: config.secret, EASYLSP_BRIDGE_AUTH: `Bearer ${config.bridgeToken}` },
  stdio: ['pipe', 'pipe', 'inherit'],
});
let nextId = 0;
const pending = new Map();
const write = value => child.stdin.write(JSON.stringify(value) + '\n');
const rpc = (method, params) => new Promise((resolve, reject) => {
  const id = ++nextId; pending.set(id, { resolve, reject }); write({ jsonrpc: '2.0', id, method, params });
});
createInterface({ input: child.stdout }).on('line', line => {
  const frame = JSON.parse(line);
  if (frame.method && frame.id != null) {
    write({ jsonrpc: '2.0', id: frame.id, result: frame.method === 'approval/request' ? { outcome: 'allowed-once' } : { cancelled: true } });
  } else if (frame.id != null) {
    const waiter = pending.get(frame.id); pending.delete(frame.id);
    if (frame.error) waiter?.reject(new Error(JSON.stringify(frame.error))); else waiter?.resolve(frame.result);
  } else process.stdout.write(JSON.stringify(frame) + '\n');
});
child.on('exit', code => { for (const p of pending.values()) p.reject(new Error(`Harness exited: ${code}`)); });
try {
  const init = await rpc('initialize', { cwd: directory, provider: 'easylsp', model: config.model, approvalRequests: true, capabilities: { userQuestions: true }, maxTokens: config.maxTokens ?? 1024 });
  process.stdout.write(JSON.stringify({ probe: 'initialized', millis: performance.now() - started, result: init }) + '\n');
  const sessionId = config.sessionId;
  if (config.resume) await rpc('session/resume', { sessionId });
  const accepted = await rpc('session/prompt', { sessionId, contentBlocks: [{ type: 'text', text: '调用 mcp__easylsp__get_project_info，报告实际 packageName 和 sampleRevision。只读查询，不做其他操作。' }] });
  process.stdout.write(JSON.stringify({ probe: 'accepted', result: accepted }) + '\n');
  await rpc('session/wait', { sessionId });
  process.stdout.write(JSON.stringify({ probe: 'complete', millis: performance.now() - started }) + '\n');
  await rpc('shutdown');
} finally { child.stdin.end(); lines.close(); }
