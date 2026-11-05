import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { mkdir, writeFile } from 'node:fs/promises';
import { EventEmitter } from 'node:events';
import { fileURLToPath } from 'node:url';
import { availableParallelism } from 'node:os';
import { basename } from 'node:path';
import { modelProfile } from './model-profile.mjs';

// Owns one real SDK process. No model loop, retry, or duplicate session log.
export class HarnessRuntime extends EventEmitter {
  #pending = new Map();
  #nextId = 0;
  #exitError;
  async start(config) {
    await mkdir(config.directory, { recursive: true });
    const { provider, profile, effort } = modelProfile(config);
    const patch = [
      { id: 'agent-loop', config: { maxParallelToolCalls: config.maxParallelToolCalls ?? availableParallelism() } },
      { id: 'compaction-basic', config: { thresholdRatio: 0.8, retainRatio: 0.16, headroomTokens: Math.min(16384, Math.floor((config.contextBudget ?? config.contextWindow ?? 200000) * 0.08)), maxTokens: config.summaryMaxTokens ?? 16384, compactionRetries: 0, maxOverflowRetries: 0 } },
      { id: 'llm-pi-ai', config: { providers: { [provider]: profile } } },
      { insert: [
        { id: 'easylsp-control', name: fileURLToPath(new URL('control.mjs', import.meta.url)), config: { bridgeURL: config.bridgeURL, capabilities: config.capabilities, contextWindow: config.contextWindow, contextBudget: config.contextBudget } },
        { id: 'easylsp-policy', name: fileURLToPath(new URL('android-policy.mjs', import.meta.url)), config: { configuration: config.configuration, prompt: config.prompt, maxRequests: config.maxRequests, bridgeURL: config.bridgeURL, capabilities: config.capabilities } },
        { id: 'easylsp-mcp', name: fileURLToPath(new URL('android-mcp.mjs', import.meta.url)), config: {
          serverName: 'easylsp', transport: 'streamable-http', url: config.bridgeURL,
          // Android owns tool deadlines. User interaction must outlive the client's 60s default.
          toolCallTimeoutMs: 2147483647,
          reconnect: { enabled: false }, failOnStartupError: true,
        } },
      ] },
    ];
    const path = `${config.directory}/patch.json`;
    await writeFile(path, JSON.stringify(patch));
    this.child = spawn(process.execPath, ['--import', fileURLToPath(new URL('model-http.mjs', import.meta.url)), fileURLToPath(new URL('node_modules/.bin/dsh', import.meta.url)), '--profile', 'sdk', '--patch', path], {
      env: { ...process.env, DSH_HOME: config.directory, EASYLSP_MODEL_ORIGIN: new URL(config.baseURL).origin, EASYLSP_HTTP_SESSION: basename(config.directory), EASYLSP_HTTP_TIMEOUT_MS: String(config.timeoutMs ?? 300000), EASYLSP_MODEL_KEY: config.secret, EASYLSP_BRIDGE_AUTH: `Bearer ${config.bridgeToken}` },
      stdio: ['pipe', 'pipe', 'inherit', 'pipe'],
    });
    this.exited = new Promise(resolve => this.child.once('exit', (code, signal) => {
      const error = new Error(`Harness exited: ${signal ?? code}`);
      this.#exitError = error;
      for (const pending of this.#pending.values()) pending.reject(error);
      this.#pending.clear(); this.emit('exit', { code, signal }); resolve({ code, signal });
    }));
    for (const channel of [this.child.stdout, this.child.stdio[3]]) {
      createInterface({ input: channel }).on('line', line => {
        const frame = JSON.parse(line);
        if (frame.id != null && frame.method) {
          this.emit('interaction', frame);
        } else if (frame.id != null) {
          const pending = this.#pending.get(frame.id); this.#pending.delete(frame.id);
          if (frame.error) pending?.reject(new Error(frame.error.message)); else pending?.resolve(frame.result);
        } else this.emit('notification', frame);
      });
    }
    try {
      return await this.rpc('initialize', { cwd: config.workspace ?? config.directory, provider, model: config.model,
        approvalRequests: true, capabilities: { userQuestions: true }, ...(config.maxTokens ? { maxTokens: config.maxTokens } : {}),
        ...(effort ? { reasoningEffort: effort } : {}) });
    } catch (error) { this.child.stdin.end(); await this.exited; throw error; }
  }
  rpc(method, params) { return this.#request(this.child.stdin, method, params); }
  control(method, params) { return this.#request(this.child.stdio[3], method, params); }
  answer(id, result) { this.child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, result }) + '\n'); }
  #request(channel, method, params) {
    if (this.#exitError) return Promise.reject(this.#exitError);
    const id = ++this.#nextId;
    return new Promise((resolve, reject) => {
      this.#pending.set(id, { resolve, reject });
      channel.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
    });
  }
  async close() {
    if (this.#exitError) { await this.exited; return; }
    await this.rpc('shutdown');
    this.child.stdin.end();
    await this.exited;
  }
}
