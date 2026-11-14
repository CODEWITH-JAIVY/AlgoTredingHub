import assert from "node:assert/strict";
import test from "node:test";
import { RequestEpoch } from "./requestEpoch.ts";

test("a newer load makes an older response stale", () => {
  const requests = new RequestEpoch();
  const first = requests.begin();
  const second = requests.begin();

  assert.equal(requests.isCurrent(first), false);
  assert.equal(requests.isCurrent(second), true);
});

test("cleanup invalidates a response that has not returned yet", () => {
  const requests = new RequestEpoch();
  const current = requests.begin();

  requests.invalidate();

  assert.equal(requests.isCurrent(current), false);
});
