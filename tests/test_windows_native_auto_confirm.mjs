import assert from 'node:assert/strict';
import {
  createAutoConfirmState,
  observeAutoConfirm,
} from '../windows/native/web/src/auto-confirm.js';

const state = createAutoConfirmState();

assert.equal(observeAutoConfirm(state, 'approval one', 0, 1000), false);
assert.equal(observeAutoConfirm(state, 'approval one', 999, 1000), false);
assert.equal(observeAutoConfirm(state, 'approval one', 1000, 1000), true);

// A cleared prompt rearms the same signature for a later confirmation.
assert.equal(observeAutoConfirm(state, '', 1100, 1000), false);
assert.equal(observeAutoConfirm(state, 'approval one', 1200, 1000), false);
assert.equal(observeAutoConfirm(state, 'approval one', 2200, 1000), true);

// A distinct prompt that follows immediately also gets its own delay and Enter.
assert.equal(observeAutoConfirm(state, 'approval two', 2300, 1000), false);
assert.equal(observeAutoConfirm(state, 'approval two', 3300, 1000), true);
