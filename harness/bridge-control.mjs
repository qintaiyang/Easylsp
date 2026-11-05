// Authenticated host control, separate from the model-facing MCP tool catalog.
export async function bridgeControl(url, method, signal, params = {}) {
  const response = await fetch(url, {
    method: 'POST', signal,
    headers: { 'Content-Type': 'application/json', Authorization: process.env.EASYLSP_BRIDGE_AUTH },
    body: JSON.stringify({ jsonrpc: '2.0', id: method, method, params }),
  });
  if (!response.ok) throw new Error(`Android bridge: HTTP ${response.status}`);
  const frame = await response.json();
  if (frame.error) throw new Error(frame.error.message);
  if (frame.result.isError) throw new Error(frame.result.content.map(part => part.text ?? '').join('\n'));
  return frame.result;
}
