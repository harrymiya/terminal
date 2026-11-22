export function createAutoConfirmState() {
  return { signature: '', since: 0, sent: false };
}

export function resetAutoConfirm(state) {
  state.signature = '';
  state.since = 0;
  state.sent = false;
}

export function observeAutoConfirm(state, signature, now, delay) {
  if (!signature) {
    resetAutoConfirm(state);
    return false;
  }
  if (signature !== state.signature) {
    state.signature = signature;
    state.since = now;
    state.sent = false;
    return false;
  }
  if (state.sent || now - state.since < Math.max(50, Number(delay) || 1000)) return false;
  state.sent = true;
  return true;
}
