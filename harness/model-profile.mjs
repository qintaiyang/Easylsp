// Translate explicit model capabilities without guessing absent parameters.
export function modelProfile(config) {
  const provider = config.api === 'gemini' ? 'google' : 'easylsp';
  const effort = config.reasoningEffort ?? (config.thinkingBudget > 0 ? 'medium' : undefined);
  const model = { id: config.model, input: config.supportsImages ? ['text', 'image'] : ['text'] };
  if (config.contextWindow > 0) model.contextWindow = Math.min(config.contextWindow, config.contextBudget ?? config.contextWindow);
  if (config.maxTokens > 0) model.maxTokens = config.maxTokens;
  if (effort) model.reasoningEfforts = { [effort]: effort };
  if (config.outputTokenField) model.compat = { maxTokensField: config.outputTokenField };
  const profile = {
    ...(config.api === 'gemini' ? {} : { api: config.api }),
    baseURL: config.baseURL, apiKeyEnv: 'EASYLSP_MODEL_KEY', models: [model],
    timeoutMs: config.timeoutMs ?? 300000, streamIdleTimeoutMs: config.timeoutMs ?? 300000, retryPolicy: { mode: 'normal', maxRetries: 0 },
  };
  if (config.thinkingBudget > 0) profile.thinkingBudgets = { [effort]: config.thinkingBudget };
  return { provider, profile, effort };
}
