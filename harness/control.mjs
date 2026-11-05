// Pinned Harness runtime APIs missing from its public stdio transport.
// fd 3 belongs to our host; stdout remains the upstream SDK protocol.
import { Socket } from 'node:net';
import { createInterface } from 'node:readline';
import { buildForkSeed, SessionId, SessionLogOffset } from '@deepseek-ai/dsh-session';
import { createUserMessage } from '@deepseek-ai/dsh-llm';
import { admitEncodedImages } from '@deepseek-ai/dsh-attachment';
import { bridgeControl } from './bridge-control.mjs';

export const name = 'easylsp-control';
export const inject = ['agents', 'sessionQuery', 'sessionPersistence', 'commands', 'compaction', 'attachments', 'tokenMeter'];
export async function apply(ctx, config) {
  const socket = new Socket({ fd: 3, readable: true, writable: true });
  const lines = createInterface({ input: socket });
  const commands = new Map();
  const send = frame => socket.write(JSON.stringify(frame) + '\n');
  ctx.on('agent/assistant-stream', ({ agent, frame }) => {
    const responseIndex = frame.type === 'start' ? agent.session.snapshotEvents().filter(event =>
      (event.type === 'assistant/message' || event.type === 'assistant/attempt') && event.data.turn === frame.turn && event.data.step === frame.step).length + 1 : undefined;
    send({ method: 'session.stream', params: { sessionId: String(agent.session.id), frame: frame.type === 'start' ? { ...frame, responseIndex } : frame } });
  });
  lines.on('line', async line => {
    let request;
    try {
      request = JSON.parse(line);
      const { method, params } = request;
      let result;
      if (method === 'session/cancel') {
        commands.get(params.sessionId)?.abort();
        const agent = ctx.agents.get(params.sessionId);
        if (!agent) { send({ id: request.id, result: { status: 'detached' } }); return; }
        agent.cancel({ kind: 'user' });
        result = { status: agent.status };
      } else if (method === 'session/history') {
        const { after = -1, limit = 80 } = params;
        if (!Number.isSafeInteger(after) || after < -1 || !Number.isSafeInteger(limit) || limit < 1 || limit > 200) {
          throw new Error('Expected after >= -1 and limit between 1 and 200');
        }
        const observation = await ctx.sessionQuery.observeSession(params.sessionId, { projectionMode: 'none' });
        try {
          const events = observation.events.slice(after + 1, after + 1 + limit);
          const next = events.at(-1)?.seq ?? after;
          result = { session: observation.header, events, cursor: observation.cursor, next, hasMore: next < observation.cursor };
        } finally { observation[Symbol.dispose](); }
      } else if (method === 'session/fork-seed') {
        const agent = ctx.agents.get(params.sessionId);
        if (!agent) throw new Error('Session is not attached');
        if (params.messageId && agent.status === 'running') throw new Error('Finish or stop the active session before editing');
        const observation = await ctx.sessionQuery.observeSession(params.sessionId, { projectionMode: 'none' });
        try {
          let boundary;
          if (params.messageId) {
            const message = observation.events.find(e => e.type === 'user/message' && e.data.id === params.messageId && e.data.source.kind === 'user');
            if (!message) throw new Error('User message is not in this branch');
            const start = observation.events.filter(e => e.type === 'turn/start' && e.seq < message.seq).at(-1);
            if (!start) throw new Error('User turn boundary is unavailable');
            boundary = start.seq - 1;
          } else boundary = observation.events.filter(e => e.type === 'turn/end').at(-1)?.seq ?? -1;
          result = { parentSession: params.sessionId, boundary, seed: boundary < 0 ? [] : buildForkSeed(observation.events, boundary) };
        } finally { observation[Symbol.dispose](); }
      } else if (method === 'session/seed') {
        const id = SessionId(params.sessionId);
        if (!await ctx.sessionPersistence.stat(id)) {
          const handle = await ctx.agents.create({ sessionId: id, seed: params.seed,
            inheritedEventCount: SessionLogOffset(params.boundary + 1),
            meta: { cwd: params.cwd, parentSession: SessionId(params.parentSession), isSeeded: params.boundary >= 0 } });
          try { await ctx.sessionPersistence.flush(); } finally { await handle.dispose(); }
        }
        result = { sessionId: params.sessionId };
      } else if (method === 'session/steer') {
        const agent = ctx.agents.get(params.sessionId);
        if (!agent || agent.status !== 'running') throw new Error('Session is not running');
        const images = params.contentBlocks.filter(block => block.type === 'image' && 'data' in block);
        const refs = images.length ? await admitEncodedImages(ctx.attachments, images.map(image => ({ data: image.data, mediaType: image.mimeType }))) : [];
        let next = 0;
        const content = params.contentBlocks.map(block => block.type === 'image' && 'data' in block ? { type: 'image', attachment: refs[next++] } : block);
        const message = createUserMessage({ content, source: { kind: 'user' } });
        agent.steer(message);
        await ctx.sessionPersistence.flush();
        result = { messageId: message.id, effectiveFrom: 'next_step' };
      } else if (method === 'session/command') {
        const agent = ctx.agents.get(params.sessionId);
        if (!agent) throw new Error('Session is not attached');
        if (params.command !== '/compact') throw new Error('Unsupported maintenance command');
        if (commands.has(params.sessionId)) throw new Error('A maintenance command is already running');
        const controller = new AbortController(); commands.set(params.sessionId, controller);
        try {
          result = await ctx.commands.execute(agent, params.command, [], controller.signal);
          await ctx.sessionPersistence.flush();
        } finally { commands.delete(params.sessionId); }
      } else if (method === 'session/context/read') {
        if (!ctx.agents.get(params.sessionId)) throw new Error('Session is not attached');
        if (!config.capabilities) throw new Error('This session uses a fixed tool catalog');
        result = await bridgeControl(config.bridgeURL, 'easylsp/capabilities/read');
        const agent = ctx.agents.get(params.sessionId);
        const events = agent.session.snapshotEvents();
        result.lastRequest = events.filter(e => e.type === 'request/header').at(-1)?.data;
        result.requestContext = events.filter(e => e.type === 'request/context').at(-1)?.data;
        const messages = agent.session.deriveMessages();
        const sources = new Map(events.filter(e => e.data?.id || e.data?.message?.id).map(e => [e.data.id ?? e.data.message.id, { seq: e.seq, type: e.type }]));
        result.system = messages.filter(message => message.role === 'system');
        result.sources = messages.map(message => ({ id: message.id, role: message.role, source: message.source,
          event: sources.get(message.id), blocks: message.content.map(block => ({ type: block.type,
            ...(block.type === 'text' ? { characters: block.text.length } : {}),
            ...(block.type === 'image' ? { attachment: block.attachment } : {}) })) }));
        result.tokenMeasurement = ctx.tokenMeter.measure(agent.session);
        result.tokenMeasurementNote = 'SDK estimate calibrated against recorded usage when available; this is context pressure, not a bill.';
        result.capacity = { configuredContextWindow: config.contextWindow, contextBudget: config.contextBudget,
          effectiveContextWindow: result.requestContext?.contextWindow };
        result.size = { messages: messages.length, textCharacters: messages.reduce((sum, message) => sum +
          message.content.reduce((count, block) => count + (block.type === 'text' ? block.text.length : 0), 0), 0),
          toolSchemaCharacters: JSON.stringify(result.lastRequest?.header?.tools ?? []).length,
          note: 'Character counts describe the current derived history, not token usage. Images and provider framing are excluded.' };
        result.compaction = events.filter(e => e.type === 'compaction/summary').at(-1)?.data;
        result.cursor = events.at(-1)?.seq ?? -1;
      } else if (method === 'session/state') {
        const agent = ctx.agents.get(params.sessionId);
        result = { status: agent?.status ?? 'detached' };
      } else throw new Error(`Unknown control method: ${method}`);
      send({ id: request.id, result });
    } catch (error) { send({ id: request?.id ?? null, error: { message: error.message } }); }
  });
  ctx.on('dispose', () => { lines.close(); socket.destroy(); });
}
