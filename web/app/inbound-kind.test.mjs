import test from 'node:test';
import assert from 'node:assert/strict';
import { classifyInboundKind } from './inbound-kind.mjs';

test('stores only text and key drop', () => {
  assert.equal(classifyInboundKind(1), 'store');
  assert.equal(classifyInboundKind(4), 'store');
  for (const kind of [0, 2, 3, 5, 6, 7, 99]) {
    assert.equal(classifyInboundKind(kind), 'discard');
  }
});
