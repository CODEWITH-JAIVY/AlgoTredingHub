import assert from "node:assert/strict";
import test from "node:test";
import { calculatePaperReplayAccounting, runPaperReplay } from "./paperReplay.ts";

const tickSize = 0.05;
const options = { tickSize, lotSize: 10, feeBpsPerSide: 2, spreadTicksPerSide: 1, slippageTicks: 1,
  participationPercent: 100, latencyBars: 0, maxWaitBars: 3 };

function bars(specs, dates = null) {
  const start = Date.UTC(2024, 0, 2, 3, 45);
  return specs.map((spec, index) => {
    const timestampMs = dates?.[index] ?? start + index * 60_000;
    return { timestampMs, time: new Date(timestampMs).toISOString(), open: spec.open ?? 100, high: spec.high ?? 100.2,
      low: spec.low ?? 99.8, close: spec.close ?? 100, volume: spec.volume ?? 100 };
  });
}

test("market orders wait for a later bar and apply adverse spread, slippage, and fees", () => {
  const history = bars([{}, { open: 100 }]);
  const order = { id: "m1", side: "BUY", type: "MARKET", quantity: 10, submittedAtIndex: 0 };
  const first = runPaperReplay(history, [order], options);
  assert.deepEqual(first, runPaperReplay(history, [order], options));
  assert.equal(first.fills.length, 1);
  assert.equal(first.fills[0].time, history[1].time, "same-bar fills would use lookahead");
  assert.equal(first.fills[0].price, 100.1);
  assert.equal(first.fills[0].fee, 0.2);
  assert.equal(first.orders[0].status, "FILLED");
  assert.equal(first.totalFees, 0.2);
  assert.ok(first.warnings.some((warning) => warning.includes("no file or order is sent")));
});

test("latency, zero volume, and shared lot-rounded participation create partial fills deterministically", () => {
  const history = bars([{ volume: 100 }, { volume: 0 }, { volume: 15 }, { volume: 30 }]);
  const replay = runPaperReplay(history, [
    { id: "first", side: "BUY", type: "MARKET", quantity: 20, submittedAtIndex: 0 },
    { id: "second", side: "SELL", type: "MARKET", quantity: 20, submittedAtIndex: 0 },
  ], { ...options, latencyBars: 1, maxWaitBars: 3 });
  assert.deepEqual(replay.fills.map((fill) => [fill.orderId, fill.time, fill.quantity]), [
    ["first", history[2].time, 10], ["first", history[3].time, 10], ["second", history[3].time, 20],
  ]);
  assert.deepEqual(replay.orders.map((order) => order.status), ["FILLED", "FILLED"]);
});

test("limit orders respect their price across a favorable opening gap; stop-market gaps pay adverse prices", () => {
  const history = bars([{}, { open: 99.9, high: 100.15, low: 99.8 }, { open: 99.5, high: 100, low: 99.3, close: 99.5 }]);
  const replay = runPaperReplay(history, [
    { id: "limit", side: "BUY", type: "LIMIT", limitPrice: 99.95, quantity: 10, submittedAtIndex: 0 },
    { id: "stop", side: "SELL", type: "STOP_MARKET", stopPrice: 99.5, quantity: 10, submittedAtIndex: 1, timeInForce: "GTC" },
  ], options);
  assert.equal(replay.fills[0].price, 99.9);
  assert.equal(replay.fills[0].reason, "LIMIT_TOUCH");
  assert.equal(replay.fills[1].price, 99.4);
  assert.equal(replay.fills[1].reason, "STOP_TRIGGER");
});

test("DAY orders expire between sessions while GTC orders can continue", () => {
  const day2 = Date.UTC(2024, 0, 3, 3, 45);
  const history = bars([{}, { volume: 100 }, { open: 100, volume: 100 }], [
    Date.UTC(2024, 0, 2, 3, 45), Date.UTC(2024, 0, 2, 3, 46), day2,
  ]);
  const replay = runPaperReplay(history, [
    { id: "day", side: "BUY", type: "MARKET", quantity: 10, submittedAtIndex: 1 },
    { id: "gtc", side: "SELL", type: "MARKET", quantity: 10, submittedAtIndex: 1, timeInForce: "GTC" },
  ], options);
  assert.equal(replay.orders[0].status, "EXPIRED");
  assert.match(replay.orders[0].reason, /session boundary/);
  assert.equal(replay.orders[1].status, "FILLED");
  assert.equal(replay.fills[0].time, history[2].time);
});

test("a close-time cancel takes effect before the next bar, and unfinished orders expire at replay end", () => {
  const history = bars([{}, {}, {}, {}]);
  const replay = runPaperReplay(history, [
    { id: "cancelled", side: "BUY", type: "MARKET", quantity: 10, submittedAtIndex: 0, cancelRequestedAtIndex: 1 },
    { id: "timeout", side: "BUY", type: "LIMIT", limitPrice: 90, quantity: 20, submittedAtIndex: 0, timeInForce: "GTC", cancelRequestedAtIndex: 1 },
    { id: "end", side: "BUY", type: "LIMIT", limitPrice: 90, quantity: 10, submittedAtIndex: 2, timeInForce: "GTC" },
  ], { ...options, latencyBars: 1, maxWaitBars: 1 });
  assert.equal(replay.orders[0].status, "CANCELLED");
  assert.equal(replay.orders[1].status, "CANCELLED");
  assert.equal(replay.orders[2].status, "EXPIRED");
  assert.match(replay.orders[2].reason, /ended/);
});

test("invalid orders are rejected without blocking valid orders; duplicate IDs and bad replay settings fail closed", () => {
  const history = bars([{}, {}, {}]);
  const replay = runPaperReplay(history, [
    { id: "bad", side: "BUY", type: "LIMIT", quantity: 10, submittedAtIndex: 0 },
    { id: "good", side: "BUY", type: "MARKET", quantity: 10, submittedAtIndex: 0 },
  ], options);
  assert.equal(replay.orders[0].status, "REJECTED");
  assert.match(replay.orders[0].reason, /limit price/);
  assert.equal(replay.orders[1].status, "FILLED");
  assert.throws(() => runPaperReplay(history, [
    { id: "same", side: "BUY", type: "MARKET", quantity: 10, submittedAtIndex: 0 },
    { id: "same", side: "SELL", type: "MARKET", quantity: 10, submittedAtIndex: 0 },
  ], options), /Duplicate replay order ID/);
  assert.throws(() => runPaperReplay(history, [], { ...options, participationPercent: 0 }), /Participation/);
  assert.throws(() => runPaperReplay(history, [], { ...options, tickSize: 0.03 }), /tick-aligned/);
  assert.throws(() => runPaperReplay(history, [], null), /assumptions are required/);
  assert.throws(() => runPaperReplay(history, [{ id: "x".repeat(101), side: "BUY", type: "MARKET", quantity: 10, submittedAtIndex: 0 }], options), /1–100 characters/);
});

test("fills beyond accounting notional or fee bounds are rejected before creating unaccountable P&L", () => {
  const highNotional = bars([{}, {}]).map((bar) => ({ ...bar, open: 200_000_000, high: 200_000_000, low: 200_000_000, close: 200_000_000 }));
  const hugeValue = runPaperReplay(highNotional, [{ id: "notional", side: "BUY", type: "MARKET", quantity: 10, submittedAtIndex: 0 }], options);
  assert.equal(hugeValue.orders[0].status, "REJECTED");
  assert.match(hugeValue.orders[0].reason, /notional exceeds/);
  assert.equal(hugeValue.fills.length, 0);

  const highFee = bars([{}, {}]).map((bar) => ({ ...bar, open: 20_000_000, high: 20_000_000, low: 20_000_000, close: 20_000_000 }));
  const excessiveFee = runPaperReplay(highFee, [{ id: "fees", side: "BUY", type: "MARKET", quantity: 10, submittedAtIndex: 0 }], { ...options, feeBpsPerSide: 100 });
  assert.equal(excessiveFee.orders[0].status, "REJECTED");
  assert.match(excessiveFee.orders[0].reason, /fees exceed/);
  assert.equal(excessiveFee.fills.length, 0);
});

test("replay rejects excessive order-history work before iterating malformed history", () => {
  const excessiveHistory = new Array(50_000);
  const manyOrders = Array.from({ length: 101 }, (_, index) => ({ id: `cap-${index}`, side: "BUY", type: "MARKET", quantity: 10, submittedAtIndex: 0 }));
  assert.throws(() => runPaperReplay(excessiveHistory, manyOrders, options), /safe computation limit/);
});

test("replay rejects bars whose display timestamp does not match their numeric timestamp", () => {
  const history = bars([{}, {}]);
  history[1] = { ...history[1], time: history[0].time };
  assert.throws(() => runPaperReplay(history, [], options), /valid, tick-aligned OHLCV/);
});

test("replay enforces the FIFO accounting fill cap before returning an unaccountable result", () => {
  const history = Array.from({ length: 50_000 }, (_, index) => {
    const day = Math.floor(index / 374), minute = index % 374;
    const timestampMs = Date.UTC(2024, 0, 2 + day, 3, 45) + minute * 60_000;
    return { timestampMs, time: new Date(timestampMs).toISOString(), open: 100, high: 100, low: 100, close: 100, volume: index === 1 ? 10_020 : 10 };
  });
  const orders = [
    { id: "first", side: "BUY", type: "MARKET", quantity: 10, submittedAtIndex: 0, timeInForce: "GTC" },
    { id: "second", side: "SELL", type: "MARKET", quantity: 10, submittedAtIndex: 0, timeInForce: "GTC" },
    ...[0, 10_000, 20_000, 30_000, 40_000].map((submittedAtIndex, index) => ({
      id: `partial-${index}`, side: "BUY", type: "MARKET", quantity: 1_000_000_000, submittedAtIndex, timeInForce: "GTC",
    })),
  ];
  assert.throws(() => runPaperReplay(history, orders, { ...options, maxWaitBars: 10_000 }), /50,000-fill accounting limit/);
});

test("paper accounting calculates FIFO realized P&L, reversals, open mark, and allocated fees", () => {
  const history = bars([{}, {}, { close: 85, open: 85, high: 85.2, low: 84.8 }]);
  const replay = { orders: [], totalFees: 0.89, warnings: [], fills: [
    { orderId: "a", sequence: 1, time: history[0].time, side: "BUY", quantity: 20, price: 100, fee: 0.40, referencePrice: 100, reason: "MARKET_OPEN" },
    { orderId: "b", sequence: 2, time: history[1].time, side: "SELL", quantity: 10, price: 110, fee: 0.22, referencePrice: 110, reason: "MARKET_OPEN" },
    { orderId: "c", sequence: 3, time: history[2].time, side: "SELL", quantity: 15, price: 90, fee: 0.27, referencePrice: 90, reason: "MARKET_OPEN" },
  ] };
  const report = calculatePaperReplayAccounting(replay, history, "FNO", "NIFTY-TEST");
  assert.equal(report.realizedGrossPnl, 0);
  assert.equal(report.realizedCharges, 0.8);
  assert.equal(report.realizedNetPnl, -0.8);
  assert.equal(report.totalEstimatedFees, 0.89);
  assert.equal(report.openPositions.length, 1);
  assert.deepEqual(report.openPositions[0], { direction: "SHORT", quantity: 5, averageEntryPrice: 90, markPrice: 85,
    grossUnrealizedPnl: 25, unallocatedEntryFees: 0.09, netUnrealizedPnl: 24.91 });
  assert.equal(report.combinedNetPnl, 24.11);
  assert.equal(report.markedAt, history[2].time);
});

test("paper accounting handles no fills and rejects a mark earlier than a replay fill", () => {
  const history = bars([{}, {}]);
  const empty = calculatePaperReplayAccounting({ orders: [], fills: [], totalFees: 0, warnings: [] }, history, "CASH_INTRADAY", "DEMO-TEST");
  assert.equal(empty.fillCount, 0);
  assert.equal(empty.realizedNetPnl, 0);
  assert.equal(empty.netUnrealizedPnl, 0);
  const futureFill = { orderId: "later", sequence: 1, time: new Date(history[1].timestampMs + 60_000).toISOString(), side: "BUY", quantity: 10,
    price: 100, fee: 0.2, referencePrice: 100, reason: "MARKET_OPEN" };
  assert.throws(() => calculatePaperReplayAccounting({ orders: [], fills: [futureFill], totalFees: 0.2, warnings: [] }, history, "CASH_INTRADAY", "DEMO-TEST"), /ordered timestamps/);
  const validFill = { ...futureFill, time: history[1].time };
  assert.throws(() => calculatePaperReplayAccounting({ orders: [], fills: [validFill], totalFees: 9, warnings: [] }, history, "CASH_INTRADAY", "DEMO-TEST"), /fee total does not match/);
});
