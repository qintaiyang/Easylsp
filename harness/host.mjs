import { createServer } from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';
import { mkdir, readFile, rename, writeFile, cp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { availableParallelism, totalmem } from 'node:os';
import { Extensions } from './extensions.mjs';
import { HarnessRuntime } from './runtime.mjs';
import { repairLimitHistory } from './session-repair.mjs';

// SDK processes own execution history. The host only persists admission and command receipts.
export async function startHost({ token, directory, port = 0, idleTimeoutMs = 60000, concurrency = Math.max(1, Math.min(availableParallelism(), Math.floor(totalmem() / (768 * 1024 * 1024)))) }) {
  const server = createServer(async (request, response) => {
    const url = new URL(request.url, 'http://127.0.0.1');
    if (!url.pathname.startsWith('/oauth/')) { response.writeHead(404).end(); return; }
    try { await extensions.callback(decodeURIComponent(url.pathname.slice(7)), url); response.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' }).end('登录成功，可以返回 Easylsp。'); }
    catch (error) { response.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' }).end(error.message); }
  });
  const sockets = new WebSocketServer({ noServer: true, maxPayload: 40 * 1024 * 1024 });
  const sessions = new Map(), opening = new Map();
  let focused, closing = false, scheduling = false, idleSince = Date.now(), activeRequests = 0, workLeaseUntil = 0;
  let closePromise;
  const hasWork = () => Date.now() < workLeaseUntil || opening.size || scheduling || activeRequests || [...sessions.values()].some(s =>
    !s.exited && (!s.ready || s.slot || s.submitting || s.maintenance || s.queue.length || s.interactions.size || s.yielded || s.reacquire || s.status === 'running'));
  const idleTimer = setInterval(() => {
    if (closing) return;
    if (hasWork()) { idleSince = Date.now(); return; }
    if (Date.now() - idleSince >= idleTimeoutMs) {
      void close(true);
    }
  }, Math.min(10000, idleTimeoutMs));
  idleTimer.unref();
  const broadcast = frame => {
    const text = JSON.stringify(frame);
    for (const socket of sockets.clients) if (socket.readyState === WebSocket.OPEN) socket.send(text);
  };
  const extensions = new Extensions({ broadcast, callbackURL: () => `http://127.0.0.1:${server.address().port}` });
  const validId = id => { if (!/^[a-zA-Z0-9_-]{1,100}$/.test(id)) throw new Error('Invalid session or request ID'); };
  const record = id => { const value = sessions.get(id); if (!value) throw new Error('Session is not open'); return value; };
  const used = () => [...sessions.values()].filter(s => s.slot).length;
  server.on('upgrade', (request, socket, head) => {
    if (request.url !== '/session' || request.headers.authorization !== `Bearer ${token}`) {
      socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n'); return;
    }
    sockets.handleUpgrade(request, socket, head, ws => sockets.emit('connection', ws, request));
  });
  async function save(s) {
    // Serialize updates to the same receipt file, including tool/command admissions.
    s.saving = s.saving.then(async () => {
      await writeFile(`${s.receiptPath}.tmp`, JSON.stringify(s.receipts));
      await rename(`${s.receiptPath}.tmp`, s.receiptPath);
    });
    return s.saving;
  }
  async function state(id = focused) {
    const s = sessions.get(id);
    if (!s) return { status: 'empty', concurrency };
    const raw = s.exited ? { status: 'exited', exit: s.exited } : await s.runtime.control('session/state', { sessionId: id });
    return { sessionId: id, ...raw, ...(s.maintenance ? { status: 'running', activity: 'maintenance' } : {}), ...(!s.maintenance && s.queue.length && raw.status !== 'running' ? { status: 'queued', reason: '等待执行资源' } : {}),
      parentSessionId: s.parentSessionId, interactions: [...s.interactions.values()], stream: s.stream ?? null, concurrency };
  }
  async function open(params) {
    validId(params.sessionId);
    const id = params.sessionId;
    if (opening.has(id)) return opening.get(id);
    const operation = (async () => {
      let s = sessions.get(id);
      if (s && !s.exited && !params.reconfigure) { focused = id; return state(id); }
      if (s && !s.exited) {
        if ((await state(id)).status === 'running' || s.queue.length) throw new Error('Configuration applies after the active task');
        s.replacing = true; await s.runtime.close();
      }
      const sessionDirectory = join(directory, id), receiptPath = join(sessionDirectory, 'admissions.json');
      await mkdir(sessionDirectory, { recursive: true });
      let receipts;
      try { receipts = JSON.parse(await readFile(receiptPath, 'utf8')); }
      catch (error) { if (error.code !== 'ENOENT') throw error; receipts = {}; }
      const runtime = new HarnessRuntime();
      s = { id, runtime, config: params.config, parentSessionId: params.parentSessionId, receipts, receiptPath, saving: Promise.resolve(),
        interactions: new Map(), queue: [], slot: false, yielded: false, submitting: false };
      // Unsubmitted queued inputs remain durable; UNKNOWN inputs are never replayed.
      for (const receipt of Object.values(receipts)) if (receipt.status === 'RUNNING') receipt.status = 'UNKNOWN';
      for (const [requestId, receipt] of Object.entries(receipts)) if (receipt.status === 'QUEUED') s.queue.push({ requestId, contentBlocks: receipt.contentBlocks });
      sessions.set(id, s); focused = id;
      runtime.on('notification', frame => {
        if (frame.method === 'user-question.cancelled' || (frame.method === 'session.status' && frame.params.status === 'idle')) {
          for (const [key, interaction] of s.interactions) if (frame.method === 'session.status' || interaction.params.requestId === frame.params.requestId) s.interactions.delete(key);
        }
        if (frame.method === 'session.stream') {
          const part = frame.params.frame;
          if (part.type === 'start') s.stream = { start: part, chunks: [] };
          if (part.type === 'chunk') s.stream?.chunks.push(part);
          if (part.type === 'end') s.stream = undefined;
        }
        if (frame.method === 'session.status') {
          s.status = frame.params.status;
          if (s.status === 'idle') { s.slot = false; void schedule(); }
        }
        broadcast(frame);
      });
      runtime.on('interaction', frame => {
        // JSON-RPC request numbers are local to each SDK process.
        const routed = { ...frame, params: { ...frame.params, sessionId: id } };
        s.interactions.set(frame.id, routed); broadcast({ method: 'session.interaction', params: { ...routed, sessionId: id } });
      });
      runtime.on('exit', result => {
        s.exited = result; s.slot = false; s.interactions.clear(); s.stream = undefined;
        if (!s.replacing) broadcast({ method: 'host.engine-exit', params: { sessionId: id, ...result } });
        void schedule();
      });
      try {
        if (params.resume) await repairLimitHistory(sessionDirectory, id);
        await runtime.start({ ...params.config, directory: sessionDirectory });
        if (params.seed) await runtime.control('session/seed', { ...params.seed, sessionId: id, cwd: params.config.workspace ?? sessionDirectory });
        if (params.resume || params.seed) await runtime.rpc('session/resume', { sessionId: id });
      } catch (error) { sessions.delete(id); await runtime.close(); throw error; }
      s.ready = true; await schedule(); return state(id);
    })();
    opening.set(id, operation);
    try { return await operation; } finally { opening.delete(id); }
  }
  async function admit(s, input, steer = false) {
    s.receipts[input.requestId] = { status: 'UNKNOWN' }; await save(s);
    const receipt = steer ? await s.runtime.control('session/steer', { sessionId: s.id, contentBlocks: input.contentBlocks }) :
      await s.runtime.rpc('session/prompt', { sessionId: s.id, contentBlocks: input.contentBlocks });
    const accepted = { status: 'ACCEPTED', ...receipt };
    s.receipts[input.requestId] = accepted; await save(s);
    broadcast({ method: 'session.admitted', params: { sessionId: s.id, requestId: input.requestId, ...accepted } });
    return accepted;
  }
  async function schedule() {
    if (scheduling || closing) return;
    scheduling = true;
    try {
      for (const s of sessions.values()) {
        if (used() >= concurrency) break;
        if (!s.ready || s.exited || s.submitting || s.slot) continue;
        if (s.reacquire) { s.slot = true; s.yielded = false; const resolve = s.reacquire; s.reacquire = undefined; resolve(); continue; }
        if (!s.queue.length || s.yielded) continue;
        const status = await s.runtime.control('session/state', { sessionId: s.id });
        if (status.status === 'running') continue;
        s.slot = true; s.submitting = true;
        const input = s.queue.shift();
        try { await admit(s, input); }
        catch (error) { s.slot = false; broadcast({ method: 'session.admission-error', params: { sessionId: s.id, requestId: input.requestId, message: error.message } }); }
        finally { s.submitting = false; }
      }
    } finally {
      scheduling = false;
      if (!closing && used() < concurrency && [...sessions.values()].some(s => s.ready && !s.exited && !s.slot && !s.submitting && (s.reacquire || (s.queue.length && !s.yielded && s.status === 'idle')))) queueMicrotask(() => { void schedule(); });
    }
  }
  async function cancelTree(id) {
    const s = record(id);
    for (const child of sessions.values()) if (child.parentSessionId === id) await cancelTree(child.id);
    for (const input of s.queue) { s.receipts[input.requestId] = { status: 'STOPPED' }; broadcast({ method: 'session.admission-stopped', params: { sessionId: id, requestId: input.requestId } }); }
    s.queue = []; await save(s);
    if (s.reacquire) { s.reacquire(); s.reacquire = undefined; }
    s.yielded = false;
    if (s.status !== 'running') { s.slot = false; broadcast({ method: 'session.status', params: { sessionId: id, status: 'idle' } }); }
    return s.exited ? { status: 'exited' } : s.runtime.control('session/cancel', { sessionId: id });
  }
  async function dispatch(method, params) {
    if (method.startsWith('mcp/')) return extensions.dispatch(method, params);
    if (method === 'host/status') return state(params.sessionId);
    if (method === 'host/work') { workLeaseUntil = params.active ? Date.now() + 30000 : 0; return {}; }
    if (method === 'host/sessions') return { sessions: await Promise.all([...sessions.keys()].map(id => state(id))) };
    if (method === 'host/configure') {
      if (!Number.isSafeInteger(params.concurrency) || params.concurrency < 1) throw new Error('Expected a positive concurrency');
      concurrency = params.concurrency; await schedule(); return { concurrency };
    }
    if (method === 'host/stop') return { status: 'stopping' };
    if (method === 'session/open' || method === 'child/open') return open(params);
    // Legacy callers use the same registry and scheduler during migration.
    if (method.startsWith('child/')) method = method.replace('child/', 'session/');
    if (method === 'session/remove-history') {
      validId(params.sessionId);
      if (sessions.has(params.sessionId)) throw new Error('Close the session before removing history');
      await rm(join(directory, params.sessionId), { recursive: true, force: true });
      return {};
    }
    const s = record(params.sessionId);
    if (method === 'session/state') return state(s.id);
    if (method === 'session/close') { await cancelTree(s.id); if ((await state(s.id)).status === 'running') await s.runtime.rpc('session/wait', { sessionId: s.id }); await s.runtime.close(); sessions.delete(s.id); return {}; }
    if (method === 'session/cancel') return cancelTree(s.id);
    if (method === 'session/yield') { s.slot = false; s.yielded = true; await schedule(); return {}; }
    if (method === 'session/acquire') {
      if (s.yielded) await new Promise(resolve => { s.reacquire = resolve; void schedule(); });
      return {};
    }
    if (method === 'session/wait') return s.runtime.rpc(method, params);
    if (['session/history', 'session/context/read'].includes(method)) return s.runtime.control(method, params);
    if (method === 'session/answer') {
      if (!s.interactions.has(params.requestId)) throw new Error('Interaction is no longer pending');
      s.runtime.answer(params.requestId, params.result); s.interactions.delete(params.requestId); return {};
    }
    if (method === 'session/receipt') return s.receipts[params.requestId] ?? { status: 'NOT_SUBMITTED' };
    if (method === 'session/command') {
      validId(params.requestId);
      if (s.receipts[params.requestId]) return s.receipts[params.requestId];
      if (s.maintenance || s.submitting || s.queue.length || (await state(s.id)).status === 'running' || used() >= concurrency) {
        const busy = { status: 'ERROR', result: { kind: 'error', text: 'BUSY: finish the active work before compaction.' } };
        s.receipts[params.requestId] = busy; await save(s); return busy;
      }
      s.maintenance = true; s.slot = true;
      s.receipts[params.requestId] = { status: 'RUNNING', command: params.command }; await save(s);
      try {
        const result = await s.runtime.control(method, params);
        s.receipts[params.requestId] = { status: result.result?.kind === 'error' ? 'ERROR' : 'COMPLETE', ...result };
      } catch (error) { s.receipts[params.requestId] = { status: 'ERROR', message: error.message }; }
      await save(s);
      s.maintenance = false; s.slot = false;
      broadcast({ method: 'session.command-complete', params: { sessionId: s.id, requestId: params.requestId, receipt: s.receipts[params.requestId] } });
      await schedule();
      return s.receipts[params.requestId];
    }
    if (method === 'session/fork' || method === 'session/rewind') {
      validId(params.branchId);
      const path = join(directory, params.branchId, 'fork.json');
      let seed;
      try { seed = JSON.parse(await readFile(path, 'utf8')); }
      catch (error) { if (error.code !== 'ENOENT') throw error; }
      if (seed) {
        if (seed.parentSession !== s.id || seed.messageId !== params.messageId) throw new Error('Branch identity belongs to another fork');
      } else {
        seed = { ...await s.runtime.control('session/fork-seed', params), ...(params.messageId ? { messageId: params.messageId } : {}) };
        await mkdir(join(directory, params.branchId), { recursive: true }); await writeFile(path, JSON.stringify(seed));
      }
      try { await cp(join(directory, s.id, "attachments"), join(directory, params.branchId, "attachments"), { recursive: true }); }
      catch (error) { if (error.code !== "ENOENT") throw error; }
      const destination = sessions.get(params.branchId);
      if (destination) {
        if ((await destination.runtime.control('session/state', { sessionId: destination.id })).status !== 'detached') return { sessionId: params.branchId, boundary: seed.boundary };
        await destination.runtime.control('session/seed', { ...seed, sessionId: destination.id, cwd: destination.config.workspace ?? join(directory, destination.id) });
        await destination.runtime.rpc('session/resume', { sessionId: destination.id });
      } else await open({ sessionId: params.branchId, config: params.config ?? s.config, parentSessionId: params.parentSessionId, seed });
      return { sessionId: params.branchId, boundary: seed.boundary };
    }
    if (method === 'session/prompt' || method === 'session/steer') {
      const requestId = params.requestId; validId(requestId);
      if (s.receipts[requestId]) return s.receipts[requestId];
      if (s.submitting) throw new Error('A prompt is already being accepted');
      s.submitting = true;
      try {
        const status = await s.runtime.control('session/state', { sessionId: s.id });
        if (method === 'session/steer' && !s.maintenance && status.status === 'running') return await admit(s, params, true);
        if (s.maintenance || status.status === 'running' || used() >= concurrency) {
          s.receipts[requestId] = { status: 'QUEUED', contentBlocks: params.contentBlocks }; s.queue.push(params); await save(s);
          broadcast({ method: 'session.status', params: { sessionId: s.id, status: status.status === 'running' ? 'running' : 'queued' } });
          return { status: 'QUEUED', requestId, reason: status.status === 'running' ? '等待当前轮结束' : '等待执行资源' };
        }
        s.slot = true; return await admit(s, params);
      } finally { s.submitting = false; queueMicrotask(() => { void schedule(); }); }
    }
    throw new Error(`Unknown host method: ${method}`);
  }
  sockets.on('connection', socket => socket.on('message', async raw => {
    let frame;
    activeRequests++;
    try {
      frame = JSON.parse(raw.toString());
      if (closing) throw new Error('Host is shutting down; reconnect before submitting new work');
      if (!['host/status', 'host/sessions', 'session/state', 'session/history', 'session/context/read', 'host/work'].includes(frame.method)) idleSince = Date.now();
      const result = await dispatch(frame.method, frame.params ?? {});
      if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ id: frame.id, result }));
      if (frame.method === 'host/stop') await close();
    } catch (error) {
      if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ id: frame?.id ?? null, error: { message: error.message } }));
    } finally { activeRequests--; }
  }));
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); });
  function close(idle = false) {
    return closePromise ??= shutdown(idle);
  }
  async function shutdown(idle) {
    closing = true;
    clearInterval(idleTimer);
    await Promise.all([...sessions.values()].map(s => s.saving));
    for (const s of sessions.values()) if (!s.exited) await s.runtime.control('session/cancel', { sessionId: s.id });
    await Promise.all([...sessions.values()].filter(s => !s.exited).map(async s => { if ((await state(s.id)).status === 'running') await s.runtime.rpc('session/wait', { sessionId: s.id }); await s.runtime.close(); }));
    await Promise.all([...sessions.values()].map(s => s.saving));
    sessions.clear();
    await extensions.close();
    if (idle) broadcast({ method: 'host.idle', params: { idleTimeoutMs } });
    for (const socket of sockets.clients) socket.terminate();
    sockets.close(); await new Promise(resolve => server.close(resolve));
  }
  return { port: server.address().port, close };
}
