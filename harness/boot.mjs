import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { mkdirSync, openSync, closeSync } from 'node:fs';

const lines = createInterface({ input: process.stdin });
const config = await new Promise(resolve => lines.once('line', line => resolve(JSON.parse(line))));
lines.close();
mkdirSync(config.directory, { recursive: true });
const log = openSync(`${config.directory}/host.log`, 'a', 0o600);
const child = spawn(process.execPath, [fileURLToPath(new URL('daemon.mjs', import.meta.url))], {
  detached: true, stdio: ['pipe', 'ignore', log, 'ipc'],
});
closeSync(log);
child.stdin.end(JSON.stringify(config) + '\n');
child.once('message', result => {
  process.stdout.write(JSON.stringify({ pid: child.pid, ...result }) + '\n');
  child.unref();
});
child.once('exit', code => { if (code) process.exitCode = code; });
