import { glob, readFile, copyFile, writeFile, rename } from 'node:fs/promises';
import { constants as zstd, zstdDecompressSync, zstdCompressSync } from 'node:zlib';
import { constants } from 'node:fs';
import { KNOWN_SESSION_EVENT_TYPES } from '@deepseek-ai/dsh-session';
import { validateStoredEvents } from '@deepseek-ai/dsh-session-persistence';
import { releasedV4SessionFormatCodec, restoreReleasedV4Artifact } from '@deepseek-ai/dsh-session-format-v3-to-v4';

function framesOf(source) {
  const frames = []; let offset = 0;
  while (offset < source.length) {
    const decoded = zstdDecompressSync(source.subarray(offset), { info: true });
    frames.push(decoded.buffer.toString()); offset += decoded.engine.bytesWritten;
  }
  return frames;
}
const rowsOf = frames => frames.join('').trimEnd().split('\n').map(JSON.parse);

// Called only after the old SDK owner has released this session's files.
export async function repairLimitHistory(directory, sessionId) {
  for await (const path of glob(`${directory}/sessions/*/${sessionId}/session.v4.jsonl.zstd`)) {
    const rows = rowsOf(framesOf(await readFile(path))); let changed = false;
    for (const row of rows) if (row.type === 'easylsp/limit' && row.ignorable !== true) { row.ignorable = true; changed = true; }
    if (!changed) return false;
    // The pinned persistence reader requires a header-only first frame.
    const compress = text => zstdCompressSync(text, { params: { [zstd.ZSTD_c_checksumFlag]: 1 } });
    const encoded = Buffer.concat([compress(JSON.stringify(rows[0]) + '\n'), compress(rows.slice(1).map(row => JSON.stringify(row)).join('\n') + '\n')]);
    const temporary = `${path}.limit-repair`;
    await writeFile(temporary, encoded);
    // Parse the actual replacement before preserving and replacing the original artifact.
    const frames = framesOf(await readFile(temporary));
    if (frames[0].trimEnd().split('\n').length !== 1) throw new Error('Limit repair requires a header-only first frame');
    const candidate = rowsOf(frames), events = [];
    const decoder = releasedV4SessionFormatCodec.createDecoder(candidate[0], 'strict');
    const context = { emitEvent: event => events.push(event), emitRun: run => events.push(...run.expand()) };
    for (const row of candidate.slice(1)) decoder.decodeRow(row, context);
    const inheritedEventCount = decoder.finish(context);
    restoreReleasedV4Artifact({ header: decoder.header, events, inheritedEventCount }, KNOWN_SESSION_EVENT_TYPES);
    validateStoredEvents(decoder.header, events, temporary);
    await copyFile(path, `${path}.before-limit-repair`, constants.COPYFILE_EXCL);
    await rename(temporary, path);
    return true;
  }
  return false;
}
