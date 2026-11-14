import assert from "node:assert/strict";
import test from "node:test";
import { buildJournalInsights, isJournalRecord, parseIstDateTimeLocal } from "./journalInsights.ts";

const now = new Date("2026-10-21T18:00:00+05:30");

function entry(id, tradeTime, result, overrides = {}) {
  return {
    id,
    time: tradeTime,
    tradeTime,
    symbol: "DEMO-ALPHA",
    setup: "Breakout",
    result,
    note: "Review",
    ...overrides,
  };
}

test("reports manual P&L by IST hour, weekday, setup and instrument-specific quantity", () => {
  const insights = buildJournalInsights([
    entry("loss", "2026-10-20T09:00:00+05:30", "-100.00", { quantity: 25, plannedStop: "95", actualStop: "94.50" }),
    entry("win", "2026-10-20T09:20:00+05:30", "50.00", { quantity: 50, stopRemoved: true }),
    entry("other-instrument", "2026-10-20T09:25:00+05:30", "25.00", { symbol: "DEMO-BETA", quantity: 25 }),
  ], { period: "7d", now });

  assert.equal(insights.entryCount, 3);
  assert.equal(insights.manualPnlCount, 3);
  assert.equal(insights.manualPnlTotal, -25);
  assert.deepEqual(insights.pnlByHour, [{ hour: 9, count: 3, pnl: -25 }]);
  assert.deepEqual(insights.pnlByWeekday, [{ weekday: 2, count: 3, pnl: -25 }]);
  assert.equal(insights.pnlBySetup[0].label, "Breakout");
  assert.equal(insights.pnlByQuantity.length, 3);
  assert.equal(insights.reentriesAfterLoss, 1);
  assert.equal(insights.sizeIncreasesAfterLoss, 1);
  assert.equal(insights.stopsMoved, 1);
  assert.equal(insights.stopsRemoved, 1);
});

test("uses the previous loss before the range boundary for re-entry observation", () => {
  const start = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);
  const previous = new Date(start.getTime() - 20 * 60 * 1000).toISOString();
  const current = new Date(start.getTime() + 10 * 60 * 1000).toISOString();
  const insights = buildJournalInsights([
    entry("previous", previous, "-10", { quantity: 10 }),
    entry("current", current, "", { quantity: 10 }),
  ], { period: "7d", now });

  assert.equal(insights.entryCount, 1);
  assert.equal(insights.manualPnlCount, 0);
  assert.equal(insights.manualPnlTotal, null);
  assert.equal(insights.reentriesAfterLoss, 1);
});

test("keeps old journal records readable and rejects corrupt new optional fields", () => {
  const legacy = { id: "legacy", time: "2026-10-20T10:00:00Z", symbol: "DEMO-ALPHA", setup: "Unlabeled", result: "loss", note: "Old record" };
  assert.equal(isJournalRecord(legacy), true);
  assert.equal(isJournalRecord({ ...legacy, quantity: 1.5 }), false);
  assert.equal(isJournalRecord({ ...legacy, plannedStop: "Infinity" }), false);
  assert.equal(isJournalRecord({ ...legacy, stopRemoved: true, actualStop: "95" }), false);
});

test("uses an inclusive 30-minute re-entry boundary and excludes one millisecond beyond it", () => {
  const exact = buildJournalInsights([
    entry("loss-exact", "2026-10-20T09:00:00+05:30", "-5"),
    entry("next-exact", "2026-10-20T09:30:00+05:30", ""),
  ], { period: "7d", now });
  const late = buildJournalInsights([
    entry("loss-late", "2026-10-20T09:00:00+05:30", "-5"),
    entry("next-late", "2026-10-20T09:30:00.001+05:30", ""),
  ], { period: "7d", now });

  assert.equal(exact.reentriesAfterLoss, 1);
  assert.equal(late.reentriesAfterLoss, 0);
});

test("includes the exact rolling-period boundary and excludes earlier entries", () => {
  const boundary = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000).toISOString();
  const before = new Date(Date.parse(boundary) - 1).toISOString();
  const insights = buildJournalInsights([
    entry("at-boundary", boundary, "10"),
    entry("before-boundary", before, "20"),
  ], { period: "7d", now });

  assert.equal(insights.entryCount, 1);
  assert.equal(insights.manualPnlTotal, 10);
});

test("rejects stale, future, invalid-time and unbounded P&L values from the report", () => {
  const insights = buildJournalInsights([
    entry("outside", "2026-10-01T10:00:00+05:30", "20"),
    entry("future", "2026-10-22T10:00:00+05:30", "20"),
    entry("invalid", "not-a-time", "20"),
    entry("non-finite", "2026-10-20T10:00:00+05:30", "Infinity"),
    entry("too-large", "2026-10-20T10:00:00+05:30", "1000000001"),
  ], { period: "7d", now });

  assert.equal(insights.entryCount, 2);
  assert.equal(insights.manualPnlCount, 0);
  assert.equal(insights.manualPnlTotal, null);
  assert.equal(insights.excludedTimestampEntries, 2);
});

test("counts overtrading days by IST date and applies the selected threshold", () => {
  const entries = [1, 2, 3, 4].map((index) => entry(
    `night-${index}`, `2026-10-20T19:00:00.${String(index).padStart(3, "0")}Z`, "",
  ));
  const insights = buildJournalInsights(entries, { period: "30d", now, overtradingThreshold: 4 });

  assert.deepEqual(insights.overtradingDays, [{ date: "2026-10-21", entries: 4 }]);
  assert.equal(insights.omittedOvertradingDays, 0);
});

test("parses an IST datetime-local value without inheriting the computer timezone", () => {
  assert.equal(parseIstDateTimeLocal("2026-10-20T09:15"), "2026-10-20T03:45:00.000Z");
  assert.equal(parseIstDateTimeLocal("2026-02-30T12:00"), null);
  assert.equal(parseIstDateTimeLocal("not-a-date"), null);
});
