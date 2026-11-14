import assert from "node:assert/strict";
import test from "node:test";
import { buildTradebookExportCsv, calculateFifoPnl, parseTradebookCsv } from "./tradebook.ts";

const nowMs = Date.UTC(2025, 0, 1);
const header = "trade_id,timestamp,segment,symbol,side,quantity,price,charges";

function csv(rows) { return [header, ...rows].join("\n"); }

test("tradebook CSV sorts fills and accepts explicit IST or ISO timestamps", () => {
  const fills = parseTradebookCsv(csv([
    "b,2024-01-02 09:16,CASH_INTRADAY,DEMO-EQ,SELL,1,101,1",
    "a,2024-01-02T03:45:00.000Z,CASH_INTRADAY,DEMO-EQ,BUY,1,100,1",
  ]), nowMs);
  assert.deepEqual(fills.map((fill) => fill.tradeId), ["a", "b"]);
  assert.equal(new Date(fills[0].timestampMs).toISOString(), "2024-01-02T03:45:00.000Z");
  const report = calculateFifoPnl(fills);
  assert.equal(report.realizedNetPnl, -1);
  assert.equal(report.openQuantity, 0);
});

test("FIFO allocates entry and exit charges over partial closes and keeps remaining inventory", () => {
  const fills = parseTradebookCsv(csv([
    "b1,2024-01-02 09:15,CASH_INTRADAY,DEMO-EQ,BUY,5,100,5",
    "b2,2024-01-02 09:16,CASH_INTRADAY,DEMO-EQ,BUY,5,110,10",
    "s1,2024-01-02 09:17,CASH_INTRADAY,DEMO-EQ,SELL,7,120,7",
  ]), nowMs);
  const report = calculateFifoPnl(fills);
  assert.equal(report.realizedMatches.length, 2);
  assert.deepEqual(report.realizedMatches.map((match) => [match.entryTradeId, match.quantity, match.netPnl]), [["b1", 5, 90], ["b2", 2, 14]]);
  assert.equal(report.realizedNetPnl, 104);
  assert.deepEqual(report.openInventory.map(({ quantity, averageEntryPrice, unallocatedEntryCharges }) => [quantity, averageEntryPrice, unallocatedEntryCharges]), [[3, 110, 6]]);
  assert.equal(report.openCharges, 6);
  assert.equal(report.totalCharges, 22);
  assert.equal(report.realizedCharges + report.openCharges, report.totalCharges);
});

test("FIFO handles shorts, reversals, and never matches across segments", () => {
  const fills = parseTradebookCsv(csv([
    "s1,2024-01-02 09:15,CASH_INTRADAY,DEMO-EQ,SELL,5,100,5",
    "b1,2024-01-02 09:16,CASH_INTRADAY,DEMO-EQ,BUY,8,90,8",
    "b2,2024-01-02 09:17,FNO,NIFTY-2024-01-25-22000-CE,BUY,1,100,1",
    "s2,2024-01-02 09:18,CASH_INTRADAY,NIFTY-2024-01-25-22000-CE,SELL,1,120,1",
  ]), nowMs);
  const report = calculateFifoPnl(fills);
  assert.equal(report.realizedMatches.length, 1);
  assert.equal(report.realizedMatches[0].direction, "SHORT");
  assert.equal(report.realizedMatches[0].quantity, 5);
  assert.equal(report.realizedNetPnl, 40);
  assert.deepEqual(report.openInventory.map((item) => [item.segment, item.symbol, item.direction, item.quantity]), [
    ["CASH_INTRADAY", "DEMO-EQ", "LONG", 3],
    ["FNO", "NIFTY-2024-01-25-22000-CE", "LONG", 1],
    ["CASH_INTRADAY", "NIFTY-2024-01-25-22000-CE", "SHORT", 1],
  ]);
});

test("FIFO charge allocation conserves paise across repeated partial matches", () => {
  const fills = parseTradebookCsv(csv([
    "b,2024-01-02 09:15,CASH_INTRADAY,DEMO-EQ,BUY,3,100,0.01",
    "s1,2024-01-02 09:16,CASH_INTRADAY,DEMO-EQ,SELL,1,101,0.01",
    "s2,2024-01-02 09:17,CASH_INTRADAY,DEMO-EQ,SELL,1,102,0.01",
  ]), nowMs);
  const report = calculateFifoPnl(fills);
  assert.equal(report.totalCharges, 0.03);
  assert.equal(report.realizedCharges + report.openCharges, 0.03);
  assert.equal(report.openInventory[0].quantity, 1);
});

test("tradebook rejects duplicate IDs, invalid dates, bad segment, negative charges and malformed rows", () => {
  assert.throws(() => parseTradebookCsv(csv([
    "same,2024-01-02 09:15,CASH_INTRADAY,DEMO-EQ,BUY,1,100,0",
    "same,2024-01-02 09:16,CASH_INTRADAY,DEMO-EQ,SELL,1,101,0",
  ]), nowMs), /unique/);
  assert.throws(() => parseTradebookCsv(csv(["x,2024-02-30 09:15,CASH_INTRADAY,DEMO-EQ,BUY,1,100,0"]), nowMs), /calendar time/);
  assert.throws(() => parseTradebookCsv(csv(["x,2024-01-02 09:15,UNKNOWN,DEMO-EQ,BUY,1,100,0"]), nowMs), /segment/);
  assert.throws(() => parseTradebookCsv(csv(["x,2024-01-02 09:15,CASH_INTRADAY,DEMO-EQ,BUY,1,100,-1"]), nowMs), /charges/);
  assert.throws(() => parseTradebookCsv(csv(["x,2024-01-02 09:15,CASH_INTRADAY,DEMO-EQ,BUY,1,100,0.001"]), nowMs), /two decimal places/);
  assert.throws(() => parseTradebookCsv(csv(["x,2024-01-02 09:15,CASH_INTRADAY,DEMO-EQ,BUY,10000001,100,0"]), nowMs), /notional/);
  assert.throws(() => parseTradebookCsv(csv(["x,2024-01-02 09:15,CASH_INTRADAY,DEMO-EQ,BUY,1,100,0,extra"]), nowMs), /expected 8/);
  assert.throws(() => parseTradebookCsv(csv(["x,2026-01-02 09:15,CASH_INTRADAY,DEMO-EQ,BUY,1,100,0"]), nowMs), /future/);
});

test("quoted CSV fields preserve commas and malformed quote tails fail", () => {
  const fills = parseTradebookCsv(csv(["x,2024-01-02 09:15,FNO,\"NIFTY 2025, 22000 CE\",BUY,1,100,0"]), nowMs);
  assert.equal(fills[0].symbol, "NIFTY 2025, 22000 CE");
  assert.throws(() => parseTradebookCsv(csv(["x,2024-01-02 09:15,FNO,\"NIFTY\"tail,BUY,1,100,0"]), nowMs), /characters after a quoted field/);
});

test("P&L CSV export includes summary, realized matches, open inventory and formula-safe text", () => {
  const fills = parseTradebookCsv(csv([
    'entry,2024-01-02 09:15,CASH_INTRADAY,"=2+3",BUY,1,100,1',
    'exit,2024-01-02 09:16,CASH_INTRADAY,"=2+3",SELL,1,90,1',
    'open,2024-01-02 09:17,FNO,"@unsafe",BUY,1,200,0.50',
  ]), nowMs);
  const output = buildTradebookExportCsv(calculateFifoPnl(fills), "2025-01-01T00:00:00.000Z");
  assert.match(output, /^record_type,generated_at,fills,total_charges,/);
  assert.match(output, /"'@unsafe"/);
  assert.match(output, /"'=2\+3"/);
  assert.match(output, /-10/);
  assert.match(output, /REALIZED_MATCH/);
  assert.match(output, /OPEN_INVENTORY/);
  assert.match(output, /not tax advice/);
});
