import { Agent, Pool, setGlobalDispatcher } from 'undici';
import { channel } from 'node:diagnostics_channel';

// SDK request timeouts do not override Node fetch's 10s TCP/TLS connect deadline.
const timeout = Number(process.env.EASYLSP_HTTP_TIMEOUT_MS);
const modelOrigin = process.env.EASYLSP_MODEL_ORIGIN;
setGlobalDispatcher(new Agent({ factory(origin, options) {
  return new Pool(origin, new URL(origin).origin === modelOrigin
    ? { ...options, connect: { timeout }, headersTimeout: timeout, bodyTimeout: timeout }
    : { ...options, headersTimeout: 2147483647, bodyTimeout: 2147483647 });
} }));
const started = new WeakMap();
let nextRequest = 0;
export function httpErrorDetails(error) {
  if (typeof error === 'string') return { name: 'AbortReason', message: error };
  return {
    name: error.name, code: error.code, message: error.message,
    syscall: error.syscall, address: error.address, port: error.port,
    ...(error.cause ? { cause: httpErrorDetails(error.cause) } : {}),
    ...(error.errors ? { errors: error.errors.map(httpErrorDetails) } : {}),
  };
}
channel('undici:request:create').subscribe(({ request }) => {
  if (String(request.origin) === modelOrigin) started.set(request, { at: Date.now(), requestId: ++nextRequest, phase: 'before-headers' });
});
channel('undici:request:headers').subscribe(({ request, response }) => {
  const state = started.get(request);
  if (state) { state.phase = 'response'; state.statusCode = response.statusCode; }
});
channel('undici:request:error').subscribe(({ request, error }) => {
  const state = started.get(request);
  if (!state) return;
  // SDK stream completion and user cancellation can both abort the response body.
  // They are cancellation records, not evidence of a failed model request.
  process.stderr.write(`[model-http] ${JSON.stringify({
    at: new Date().toISOString(), session: process.env.EASYLSP_HTTP_SESSION, requestId: state.requestId,
    event: error.name === 'AbortError' || error.code === 'UND_ERR_ABORTED' || error === 'pi-ai stream consumer stopped' ? 'cancelled' : 'failed',
    elapsedMs: Date.now() - state.at, timeoutMs: timeout, phase: state.phase, statusCode: state.statusCode,
    error: httpErrorDetails(error),
  })}\n`);
});
