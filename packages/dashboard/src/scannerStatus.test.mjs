import test from "node:test";
import assert from "node:assert/strict";
import { getScannerStatusMessage } from "./scannerStatus.ts";

const noProvider = "Localized: no approved market feed.";
const unavailable = "Localized: refresh market-data status.";

test("missing status uses a localized unavailable message", () => {
  assert.equal(getScannerStatusMessage(null, noProvider, unavailable), unavailable);
});

test("unconfigured provider uses UI copy instead of the backend English message", () => {
  assert.equal(getScannerStatusMessage({
    licensedSourceConfigured: false,
    statusMessage: "No licensed market-data provider is configured.",
    demoRangeStart: "2026-01-01T00:00:00Z",
    demoRangeEnd: "2026-01-01T01:00:00Z",
    sources: [],
  }, noProvider, unavailable), noProvider);
});

test("configured provider can still supply its specific status message", () => {
  assert.equal(getScannerStatusMessage({
    licensedSourceConfigured: true,
    statusMessage: "Provider feed is waiting for a fresh candle.",
    demoRangeStart: "2026-01-01T00:00:00Z",
    demoRangeEnd: "2026-01-01T01:00:00Z",
    sources: [],
  }, noProvider, unavailable), "Provider feed is waiting for a fresh candle.");
});

test("a configured provider with an empty status uses the localized fallback", () => {
  assert.equal(getScannerStatusMessage({
    licensedSourceConfigured: true,
    statusMessage: "  ",
    demoRangeStart: "2026-01-01T00:00:00Z",
    demoRangeEnd: "2026-01-01T01:00:00Z",
    sources: [],
  }, noProvider, unavailable), unavailable);
});
