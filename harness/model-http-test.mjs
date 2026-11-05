import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { execFile } from 'node:child_process';

process.env.EASYLSP_MODEL_ORIGIN = 'http://127.0.0.1';
process.env.EASYLSP_HTTP_TIMEOUT_MS = '5000';
const { httpErrorDetails } = await import('./model-http.mjs');
const attempts = new AggregateError([
  Object.assign(new Error('connect timed out'), { code: 'ETIMEDOUT', syscall: 'connect', address: '192.0.2.1', port: 443 }),
  Object.assign(new Error('IPv6 unreachable'), { code: 'ENETUNREACH', syscall: 'connect', address: '2001:db8::1', port: 443 }),
]);
const nested = httpErrorDetails(Object.assign(new Error('connection failed'), { cause: attempts }));
assert.deepEqual(nested.cause.errors.map(error => error.code), ['ETIMEDOUT', 'ENETUNREACH']);
assert.equal(nested.cause.errors[0].address, '192.0.2.1');

const server = createServer((request, response) => {
  if (request.url === '/disconnect') { request.socket.destroy(); return; }
  response.writeHead(200);
  if (request.url.startsWith('/cancel')) response.write('first chunk');
  else response.end('complete');
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const script = `
  const controller = new AbortController();
  try {
    const response = await fetch(process.env.TEST_URL, { signal: controller.signal });
    if (process.env.TEST_URL.endsWith('/cancel')) controller.abort();
    if (process.env.TEST_URL.endsWith('/cancel-reason')) controller.abort('pi-ai stream consumer stopped');
    await response.text();
  } catch {}
`;
async function run(path) {
  const stderr = await new Promise((resolve, reject) => execFile(process.execPath,
    ['--import', new URL('model-http.mjs', import.meta.url).pathname, '--input-type=module', '-e', script],
    { env: { ...process.env, EASYLSP_MODEL_ORIGIN: origin, EASYLSP_HTTP_SESSION: 'diagnostic-session', TEST_URL: origin + path } },
    (error, _stdout, stderr) => error ? reject(error) : resolve(stderr)));
  return stderr.split('\n').filter(line => line.startsWith('[model-http] ')).map(line => JSON.parse(line.slice(13)));
}
try {
  const [failure] = await run('/disconnect');
  assert.equal(failure.event, 'failed');
  assert.equal(failure.session, 'diagnostic-session');
  assert.equal(failure.phase, 'before-headers');
  assert.equal(failure.error.code, 'UND_ERR_SOCKET');
  assert.ok(Number.isFinite(Date.parse(failure.at)));
  assert.equal(failure.requestId, 1);
  const [cancelled] = await run('/cancel');
  assert.equal(cancelled.event, 'cancelled');
  assert.equal(cancelled.phase, 'response');
  assert.equal(cancelled.statusCode, 200);
  const [sdkCancellation] = await run('/cancel-reason');
  assert.equal(sdkCancellation.event, 'cancelled');
  assert.equal(sdkCancellation.error.message, 'pi-ai stream consumer stopped');
  assert.deepEqual(await run('/success'), []);
  console.log('Model HTTP diagnostics: nested address failures, cancellation and request correlation passed.');
} finally {
  server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
}
