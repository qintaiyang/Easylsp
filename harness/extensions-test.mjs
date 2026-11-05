import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { Extensions } from './extensions.mjs';
let origin, redirect, catalogVersion = 1, tokenRequests = 0;
const credentials = [], notices = [];
const server = createServer(async (request, response) => {
  const url = new URL(request.url, origin);
  const json = (status, value, headers = {}) => { response.writeHead(status, { 'Content-Type': 'application/json', ...headers }).end(JSON.stringify(value)); };
  let raw = ''; for await (const chunk of request) raw += chunk;
  if (url.pathname === '/.well-known/oauth-protected-resource') return json(200, { resource: origin + '/mcp', authorization_servers: [origin] });
  if (url.pathname.startsWith('/.well-known/oauth-authorization-server')) return json(200, { issuer: origin, authorization_endpoint: origin + '/authorize', token_endpoint: origin + '/token', registration_endpoint: origin + '/register', response_types_supported: ['code'], grant_types_supported: ['authorization_code', 'refresh_token'], code_challenge_methods_supported: ['S256'], token_endpoint_auth_methods_supported: ['none'] });
  if (url.pathname === '/register') { const body = JSON.parse(raw); redirect = body.redirect_uris[0]; return json(201, { ...body, client_id: 'test-client' }); }
  if (url.pathname === '/token') { const body = new URLSearchParams(raw); assert.equal(body.get('code'), 'accepted-code'); assert.ok(body.get('code_verifier')); tokenRequests++; return json(200, { access_token: 'access-token', token_type: 'Bearer', expires_in: 3600 }); }
  if (url.pathname === '/mcp' && request.headers.authorization !== 'Bearer access-token') return json(401, { error: 'unauthorized' }, { 'WWW-Authenticate': `Bearer resource_metadata="${origin}/.well-known/oauth-protected-resource"` });
  if (request.method !== 'POST') { response.writeHead(405).end(); return; }
  const frame = JSON.parse(raw); if (frame.id == null) { response.writeHead(202).end(); return; }
  let result;
  if (frame.method === 'initialize') result = { protocolVersion: '2025-11-25', capabilities: { tools: {}, resources: {} }, serverInfo: { name: 'fixture', version: '1' } };
  if (frame.method === 'tools/list') result = { tools: [{ name: 'echo', description: `revision-${catalogVersion}`, inputSchema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] } }] };
  if (frame.method === 'tools/call') result = { content: [{ type: 'text', text: frame.params.arguments.text }] };
  if (frame.method === 'resources/list') result = { resources: [{ uri: 'fixture://one', name: 'one' }] };
  if (frame.method === 'resources/templates/list') result = { resourceTemplates: [{ uriTemplate: 'fixture://{id}', name: 'template' }] };
  if (frame.method === 'resources/read') result = { contents: [{ uri: frame.params.uri, text: 'resource evidence' }] };
  return json(200, { jsonrpc: '2.0', id: frame.id, result });
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); origin = `http://127.0.0.1:${server.address().port}`;
const extensions = new Extensions({ callbackURL: () => origin, broadcast: frame => { notices.push(frame); if (frame.method === 'mcp.credentials') credentials.push(structuredClone(frame.params.credentials)); } });
const directory = await mkdtemp('/tmp/easylsp-mcp-');
const timeout = setTimeout(() => { throw new Error('Extensions test timed out'); }, 45000);
try {
  const config = { id: 'oauth', revision: 1, transport: 'http', url: origin + '/mcp', oauth: true };
  const login = await extensions.dispatch('mcp/login', { config });
  assert.equal(login.status, 'authorization_required');
  const auth = new URL(login.authorizationURL); assert.equal(auth.searchParams.get('code_challenge_method'), 'S256');
  assert.equal(auth.searchParams.get('redirect_uri'), redirect);
  const callback = new URL(redirect); callback.searchParams.set('code', 'accepted-code'); callback.searchParams.set('state', auth.searchParams.get('state'));
  await assert.rejects(extensions.callback('oauth', new URL(redirect + '?state=wrong&code=no')), /Invalid OAuth state/);
  await extensions.callback('oauth', callback);
  assert.equal(tokenRequests, 1); assert.equal(credentials.at(-1).tokens.access_token, 'access-token');
  assert.equal((await extensions.dispatch('mcp/tools', { config })).tools[0].description, 'revision-1');
  catalogVersion++;
  assert.equal((await extensions.dispatch('mcp/tools', { config })).tools[0].description, 'revision-2');
  assert.equal((await extensions.dispatch('mcp/call', { config, name: 'echo', arguments: { text: 'actual result' } })).content[0].text, 'actual result');
  assert.equal((await extensions.dispatch('mcp/resources', { config })).resources[0].uri, 'fixture://one');
  assert.equal((await extensions.dispatch('mcp/templates', { config })).resourceTemplates.length, 1);
  assert.equal((await extensions.dispatch('mcp/read', { config, uri: 'fixture://one' })).contents[0].text, 'resource evidence');
  await extensions.dispatch('mcp/logout', { serverId: 'oauth' }); assert.deepEqual(credentials.at(-1), {}); assert.equal(extensions.entries.size, 0);
  const fixture = directory + '/server.mjs';
  await writeFile(fixture, `import { createInterface } from 'node:readline';\ncreateInterface({ input: process.stdin }).on('line', line => { const r = JSON.parse(line); if (r.id == null) return; const result = r.method === 'initialize' ? { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'stdio', version: '1' } } : r.method === 'tools/list' ? { tools: [{ name: 'environment', inputSchema: { type: 'object', properties: {} } }] } : { content: [{ type: 'text', text: process.env.FIXTURE_VALUE + ':' + process.cwd() }] }; process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: r.id, result }) + '\\n'); });`);
  const local = { id: 'stdio', revision: 1, transport: 'stdio', command: process.execPath, args: [fixture], cwd: directory, env: { FIXTURE_VALUE: 'credential-value' } };
  assert.equal((await extensions.dispatch('mcp/tools', { config: local })).tools[0].name, 'environment');
  assert.equal((await extensions.dispatch('mcp/call', { config: local, name: 'environment', arguments: {} })).content[0].text, `credential-value:${directory}`);
  await extensions.dispatch('mcp/close', { serverId: 'stdio' });
  console.log(JSON.stringify({ oauthLoginPKCE: true, oauthCallbackState: true, oauthCallLogout: true, httpRefresh: true, resourcesAndTemplates: true, stdioCommandCwdEnv: true }));
} finally { clearTimeout(timeout); await extensions.close(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); await rm(directory, { recursive: true }); }
