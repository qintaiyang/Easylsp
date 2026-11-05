import { createInterface } from 'node:readline';
import { startHost } from './host.mjs';

// Bootstrap credentials arrive through a pipe, never command arguments or files.
const lines = createInterface({ input: process.stdin });
const config = await new Promise(resolve => lines.once('line', line => resolve(JSON.parse(line))));
lines.close();
const host = await startHost(config);
if (process.send) { process.send({ port: host.port }); process.disconnect(); }
process.on('SIGTERM', async () => { await host.close(); process.exit(0); });
