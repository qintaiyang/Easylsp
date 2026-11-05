import { Client, StreamableHTTPClientTransport, SSEClientTransport, UnauthorizedError } from '@modelcontextprotocol/client';
import { StdioClientTransport } from '@modelcontextprotocol/client/stdio';
import { randomUUID } from 'node:crypto';

// One mature MCP client implementation for HTTP, SSE and local stdio.
export class Extensions {
  entries = new Map();
  constructor({ broadcast, callbackURL }) { this.broadcast = broadcast; this.callbackURL = callbackURL; }
  async open(config) {
    const old = this.entries.get(config.id);
    if (old && old.config.revision === config.revision) return old;
    if (old) await old.client.close();
    const entry = { config, credentials: config.credentials ?? {}, status: 'connecting' };
    const persist = () => this.broadcast({ method: 'mcp.credentials', params: { serverId: config.id, credentials: entry.credentials } });
    const provider = {
      get redirectUrl() { return entry.callbackURL; },
      get clientMetadata() { return { client_name: 'Easylsp', redirect_uris: [entry.callbackURL], grant_types: ['authorization_code', 'refresh_token'], response_types: ['code'], token_endpoint_auth_method: 'none' }; },
      state: () => entry.state ??= randomUUID(),
      clientInformation: () => entry.credentials.client,
      saveClientInformation: value => { entry.credentials.client = value; persist(); },
      tokens: () => entry.credentials.tokens,
      saveTokens: value => { entry.credentials.tokens = value; persist(); },
      saveCodeVerifier: value => { entry.credentials.verifier = value; persist(); },
      codeVerifier: () => entry.credentials.verifier,
      redirectToAuthorization: url => { entry.authorizationURL = url.toString(); entry.status = 'authorization_required'; },
      invalidateCredentials: scope => {
        if (scope === 'all') entry.credentials = {};
        else delete entry.credentials[scope === 'client' ? 'client' : scope === 'tokens' ? 'tokens' : scope === 'verifier' ? 'verifier' : 'discovery'];
        persist();
      },
      saveDiscoveryState: value => { entry.credentials.discovery = value; persist(); },
      discoveryState: () => entry.credentials.discovery,
    };
    entry.callbackURL = `${this.callbackURL()}/oauth/${encodeURIComponent(config.id)}`;
    const client = new Client({ name: 'easylsp-extensions', version: '1.0.0' }, { listChanged: {
      tools: { onChanged: (error, tools) => {
        if (error) { entry.error = error.message; return; }
        entry.tools = tools;
        this.broadcast({ method: 'mcp.catalog-changed', params: { serverId: config.id } });
      } },
    } });
    const options = { ...(config.token ? { requestInit: { headers: { Authorization: `Bearer ${config.token}` } } } : {}), ...(config.oauth ? { authProvider: provider } : {}) };
    const createTransport = () => config.transport === 'stdio' ? new StdioClientTransport({ command: config.command, args: config.args ?? [], cwd: config.cwd || undefined, env: { ...process.env, ...config.env }, stderr: 'pipe' }) :
      config.transport === 'sse' ? new SSEClientTransport(new URL(config.url), options) : new StreamableHTTPClientTransport(new URL(config.url), { ...options, reconnectionOptions: { maxRetries: 0 } });
    const transport = createTransport();
    entry.createTransport = createTransport; entry.client = client; entry.transport = transport; this.entries.set(config.id, entry);
    try { await client.connect(transport); entry.status = 'connected'; }
    catch (error) { entry.error = error.message; if (!(error instanceof UnauthorizedError) || !entry.authorizationURL) { entry.status = 'error'; throw error; } }
    return entry;
  }
  async callback(id, url) {
    const entry = this.entries.get(id);
    if (!entry || !entry.state || url.searchParams.get('state') !== entry.state) throw new Error('Invalid OAuth state');
    entry.state = undefined;
    if (url.searchParams.has('error')) throw new Error(url.searchParams.get('error_description') ?? url.searchParams.get('error'));
    await entry.transport.finishAuth(url.searchParams);
    await entry.client.close();
    entry.transport = entry.createTransport();
    await entry.client.connect(entry.transport);
    entry.status = 'connected'; entry.authorizationURL = undefined; entry.error = undefined;
    this.broadcast({ method: 'mcp.status', params: { serverId: id, status: entry.status } });
  }
  async dispatch(method, params) {
    if (method === 'mcp/close' || method === 'mcp/logout') {
      const entry = this.entries.get(params.serverId);
      if (entry) { await entry.client.close(); this.entries.delete(params.serverId); }
      if (method === 'mcp/logout') this.broadcast({ method: 'mcp.credentials', params: { serverId: params.serverId, credentials: {} } });
      return { status: 'closed' };
    }
    const entry = await this.open(params.config);
    if (method === 'mcp/status' || method === 'mcp/login') return { status: entry.status, authorizationURL: entry.authorizationURL, error: entry.error, capabilities: entry.client.getServerCapabilities() };
    if (entry.status !== 'connected') throw new Error(entry.status + ': ' + (entry.error ?? 'Login required'));
    const options = { timeout: params.config.timeoutMs ?? 60000 };
    if (method === 'mcp/tools') { const result = await entry.client.listTools({}, { ...options, cacheMode: 'refresh' }); entry.tools = result.tools; return result; }
    if (method === 'mcp/call') return entry.client.callTool({ name: params.name, arguments: params.arguments }, undefined, options);
    if (method === 'mcp/resources') return entry.client.listResources(params.arguments ?? {}, options);
    if (method === 'mcp/templates') return entry.client.listResourceTemplates(params.arguments ?? {}, options);
    if (method === 'mcp/read') return entry.client.readResource({ uri: params.uri }, options);
    throw new Error(`Unknown MCP method: ${method}`);
  }
  async close() { await Promise.all([...this.entries.values()].map(entry => entry.client.close())); this.entries.clear(); }
}
