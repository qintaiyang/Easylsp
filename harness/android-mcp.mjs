// The pinned MCP client has no credential templates. Supply authentication in memory.
import { apply as connect } from '@deepseek-ai/dsh-mcp-client';
import { bridgeControl } from './bridge-control.mjs';
export { Config, inject } from '@deepseek-ai/dsh-mcp-client';
export const name = 'easylsp-android-mcp';
export async function apply(ctx, config) {
  await connect(ctx, { ...config, headers: { Authorization: process.env.EASYLSP_BRIDGE_AUTH } });
  const catalog = await bridgeControl(config.url, 'tools/list');
  for (const definition of ctx.tools.definitions()) {
    const raw = catalog.tools.find(tool => definition.name === `mcp__easylsp__${tool.name}`);
    if (raw) definition.isConcurrencySafe = () => raw.annotations?.readOnlyHint === true;
  }
}
