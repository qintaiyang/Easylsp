import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as wait } from 'node:timers/promises';
import WebSocket from 'ws';
import { startHost } from './host.mjs';
const directory = await mkdtemp(join(tmpdir(), 'easylsp-idle-'));
try {
  const host = await startHost({ token: 'idle-test', directory, idleTimeoutMs: 100 });
  const socket = new WebSocket('ws://127.0.0.1:' + host.port + '/session', { headers: { Authorization: 'Bearer idle-test' } });
  await new Promise(resolve => socket.once('open', resolve));
  const frames = [];
  socket.on('message', data => frames.push(JSON.parse(data)));
  socket.send(JSON.stringify({ id: 1, method: 'host/work', params: { active: true } }));
  await wait(350);
  assert.equal(socket.readyState, WebSocket.OPEN);
  socket.send(JSON.stringify({ id: 2, method: 'host/work', params: { active: false } }));
  await new Promise(resolve => socket.once('close', resolve));
  assert.ok(frames.some(frame => frame.method === 'host.idle'));
  await Promise.all([host.close(), host.close()]);
  console.log('idle shutdown waits for Android work lease and closes idempotently');
} finally { await rm(directory, { recursive: true, force: true }); }
