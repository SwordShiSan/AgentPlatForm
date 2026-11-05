const prefixes = ['agentplatform:editor:', 'agentplatform:chat-draft:'];
/** Shared session-only byte budget. Failure keeps the caller's live buffer and never evicts another draft. */
export function storeSessionDraft(key: string, value: unknown): boolean {
  try {
    if (!prefixes.some(prefix => key.startsWith(prefix))) return false;
    const raw = JSON.stringify(value); let bytes = new TextEncoder().encode(raw).length;
    for (const existing of Object.keys(sessionStorage)) {
      if (existing !== key && prefixes.some(prefix => existing.startsWith(prefix))) bytes += new TextEncoder().encode(sessionStorage.getItem(existing) ?? '').length;
    }
    if (bytes > 4 * 1024 * 1024) return false;
    sessionStorage.setItem(key, raw); return true;
  } catch { return false; }
}
