import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { Context } from '@deepseek-ai/cordis';
import { Session } from '@deepseek-ai/dsh-session';
import { createMessage, createToolResultMessage } from '@deepseek-ai/dsh-llm';
import SessionProjectionRegistry from '@deepseek-ai/dsh-session-projection';
import TokenMeter from '@deepseek-ai/dsh-token-meter';
import ToolResultPruner from '@deepseek-ai/dsh-compaction-tool-result-pruner';

// Input is emitted by the actual Android ToolOutputStore JVM test, not a JS reimplementation.
const fixture = JSON.parse(await readFile(new URL('../app/build/tmp/optimization-output-pages.json', import.meta.url), 'utf8'));
const ctx = new Context();
new SessionProjectionRegistry(ctx);
new TokenMeter(ctx);
const pruner = new ToolResultPruner(ctx);
const session = Session.create('android-output-continuity');
function append(turn, id, text) {
  session.append('turn/start', { turn });
  session.append('step/start', { turn, step: 1 });
  session.append('assistant/message', { stream: [], turn, step: 1, message: createMessage({ role: 'assistant', content: [{ type: 'tool-call', id, name: 'read_tool_output', arguments: '{}' }], source: { kind: 'model', provider: 'fixture', model: 'fixture' } }) }, { surfaceOp: 'append' });
  session.append('tool/call', { turn, step: 1, callId: id, name: 'read_tool_output', arguments: '{}' });
  const result = session.append('tool/result', { turn, step: 1, message: createToolResultMessage({ callId: id, content: [{ type: 'text', text }], isError: false }) }, { surfaceOp: 'append' });
  session.append('step/end', { turn, step: 1 });
  session.append('turn/end', { turn, reason: { kind: 'completed' } });
  return result.seq;
}
const oversized = append(1, 'legacy-large', 'legacy '.repeat(3000));
const pages = fixture.pages.map((page, index) => append(index + 2, 'page-' + index, JSON.stringify(page)));
const result = pruner.pruneSession(session);
assert.equal(result.pruned.length, 1);
assert.equal(result.pruned[0].originalSeq, oversized);
assert.equal(session.eventAt(result.pruned[0].replacementSeq).surfaceOp.op, 'replace');
let restored = '';
for (const seq of pages) {
  assert.ok(session.surface.nodes.includes(seq), 'real pruning keeps the complete JSON page');
  const page = JSON.parse(session.eventAt(seq).data.message.content[0].text);
  assert.equal(page.unit, 'utf16');
  assert.equal(page.offset, restored.length);
  restored += page.text;
  assert.equal(page.nextOffset, restored.length === fixture.original.length ? null : restored.length);
}
assert.equal(restored, fixture.original);
assert.deepEqual(JSON.parse(restored), JSON.parse(fixture.original));
console.log(JSON.stringify({ actualPrunedResults: result.pruned.length, androidPages: pages.length, restoredUtf16Units: restored.length, omittedIntervals: 0 }));
