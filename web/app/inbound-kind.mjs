/** Inbound persistence. "store" writes localStorage. Every other kind is discarded. */
export function classifyInboundKind(kind) {
  if (kind === 1 || kind === 4) return 'store';
  return 'discard';
}
