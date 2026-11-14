import test from "node:test";
import assert from "node:assert/strict";
import { formatIndicator } from "./analysisDisplay.ts";

test("indicator warm-up and malformed values render unavailable instead of zero", () => {
  for (const value of [null, undefined, "", "   ", Number.NaN, Number.POSITIVE_INFINITY, "Infinity", "not-a-number"]) {
    assert.equal(formatIndicator(value), "—");
  }
});

test("indicator formatting preserves real zero, finite strings, and Indian grouping", () => {
  assert.equal(formatIndicator(0), "0");
  assert.equal(formatIndicator("29.567"), "29.57");
  assert.equal(formatIndicator(1234567), "12,34,567");
  assert.equal(formatIndicator(1.234567, 4), "1.2346");
});

test("indicator precision is bounded to six decimal places", () => {
  assert.equal(formatIndicator(1.123456789, 20), "1.123457");
  assert.equal(formatIndicator(1.123456789, -1), "1");
  assert.equal(formatIndicator(1.123456789, 1.5), "1.12");
});
