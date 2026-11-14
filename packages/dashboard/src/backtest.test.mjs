import assert from "node:assert/strict";
import test from "node:test";
import { parseBacktestCsv, runBacktest, runWalkForwardBacktest } from "./backtest.ts";

const tickSize = 0.05;

function sampleBars(count = 300) {
  const start = Date.UTC(2024, 0, 2, 3, 45);
  return Array.from({ length: count }, (_, index) => {
    const close = Math.round((100 + 8 * Math.sin(index / 4) + 0.02 * index) / tickSize) * tickSize;
    const open = index === 0 ? close : Math.round((100 + 8 * Math.sin((index - 1) / 4) + 0.02 * (index - 1)) / tickSize) * tickSize;
    return { timestampMs: start + index * 60_000, time: new Date(start + index * 60_000).toISOString(), open,
      high: Math.max(open, close) + tickSize, low: Math.min(open, close) - tickSize, close, volume: 1000 + index };
  });
}

test("CSV import accepts ISO UTC bars and enforces complete ordered candles", () => {
  const bars = sampleBars(100);
  const csv = ["timestamp,open,high,low,close,volume", ...bars.map((bar) => `${bar.time},${bar.open},${bar.high},${bar.low},${bar.close},${bar.volume}`)].join("\n");
  assert.equal(parseBacktestCsv(csv, tickSize).length, 100);
  assert.throws(() => parseBacktestCsv(csv.replace(`${bars[2].time},`, `${bars[1].time},`), tickSize), /increasing and unique/);
  assert.throws(() => parseBacktestCsv(csv.replace(`${bars[2].time},`, `${new Date(bars[2].timestampMs + 60_000).toISOString()},`), tickSize), /Gap or duplicate/);
  assert.throws(() => parseBacktestCsv(csv.replace(`${bars[2].close},`, `${bars[2].close + 0.01},`), tickSize), /tick size/);
});

test("CSV local exchange timestamps mean IST and reject out-of-session rows", () => {
  const csv = ["timestamp,open,high,low,close,volume", "2024-01-02 09:15,100,100.10,99.95,100.05,10", "2024-01-02 09:16,100.05,100.10,100.00,100.05,11"].join("\n");
  const parsed = parseBacktestCsv(`${csv}\n${Array.from({ length: 98 }, (_, i) => {
    const minute = 17 + i, hour = 9 + Math.floor(minute / 60), minuteOfHour = minute % 60;
    const stamp = `2024-01-02 ${String(hour).padStart(2, "0")}:${String(minuteOfHour).padStart(2, "0")}`;
    return `${stamp},100.05,100.10,100.00,100.05,12`;
  }).join("\n")}`, tickSize, Date.UTC(2025, 0, 1));
  assert.equal(new Date(parsed[0].timestampMs).toISOString(), "2024-01-02T03:45:00.000Z");
  assert.throws(() => parseBacktestCsv("timestamp,open,high,low,close,volume\n2024-01-02 08:59,100,100.1,99.9,100,1\n2024-01-02 09:00,100,100.1,99.9,100,1", tickSize, Date.UTC(2025, 0, 1)), /outside the regular/);
});

test("CSV import rejects impossible dates, empty volume, and row-width mismatch", () => {
  const bars = sampleBars(100);
  const csv = ["timestamp,open,high,low,close,volume", ...bars.map((bar) => `${bar.time},${bar.open},${bar.high},${bar.low},${bar.close},${bar.volume}`)];
  const impossibleDate = [...csv];
  impossibleDate[1] = impossibleDate[1].replace("2024-01-02", "2024-02-30");
  assert.throws(() => parseBacktestCsv(impossibleDate.join("\n"), tickSize), /timestamp is invalid/);
  const emptyVolume = [...csv];
  emptyVolume[1] = emptyVolume[1].replace(/,[^,]+$/, ",");
  assert.throws(() => parseBacktestCsv(emptyVolume.join("\n"), tickSize), /volume is required/);
  const extraField = [...csv];
  extraField[1] += ",unexpected";
  assert.throws(() => parseBacktestCsv(extraField.join("\n"), tickSize), /expected 6/);
});

test("backtest fills next bar, uses whole lots, subtracts costs, and is deterministic", () => {
  const bars = sampleBars();
  const options = { startingCapital: 100_000, riskPercent: 1, lotSize: 25, tickSize, feeBpsPerSide: 2, slippageTicks: 1 };
  const first = runBacktest(bars, options);
  const second = runBacktest(bars, options);
  assert.deepEqual(first, second);
  assert.ok(first.trades.length > 0);
  assert.ok(first.trades.every((trade) => trade.quantity % options.lotSize === 0));
  assert.ok(first.trades.every((trade) => trade.entryTime > bars[0].time && trade.fees >= 0));
  assert.equal(first.strategyVersion, "basic-1.1");
  assert.ok(first.maxDrawdownPercent >= 0 && first.maxDrawdownPercent <= 100);
  assert.equal(first.expectancy, first.averageTradePnl);
  assert.equal(first.barsUsed, bars.length - 51);
  assert.equal(first.warmupBars, 51);
  assert.ok(first.maxExposureRupees > 0);
  assert.equal(first.dailySharpeRatio, null, "one session cannot produce a daily Sharpe estimate");
  assert.ok(first.profitFactor === null || Number.isFinite(first.profitFactor));
  assert.ok(Math.abs(first.netPnl - first.trades.reduce((sum, trade) => sum + trade.netPnl, 0)) < 0.05);
});

test("backtest validates resource, risk and fee bounds before simulation", () => {
  assert.throws(() => runBacktest(sampleBars(99), { startingCapital: 100_000, riskPercent: 1, lotSize: 1, tickSize, feeBpsPerSide: 0, slippageTicks: 0 }), /100–50,000/);
  assert.throws(() => runBacktest(sampleBars(), { startingCapital: 100_000, riskPercent: 5.1, lotSize: 1, tickSize, feeBpsPerSide: 0, slippageTicks: 0 }), /no more than 5%/);
  assert.throws(() => runBacktest(sampleBars(), { startingCapital: 100_000, riskPercent: 1, lotSize: 1, tickSize, feeBpsPerSide: 101, slippageTicks: 0 }), /basis points/);
  assert.throws(() => runBacktest(sampleBars(), { startingCapital: 100_000, riskPercent: 1, lotSize: 1, tickSize, feeBpsPerSide: 0, slippageTicks: 0, evaluationStartIndex: 50 }), /51 warm-up/);
});

test("walk-forward replay uses chronological, separate evaluation windows", () => {
  const bars = sampleBars(300);
  const result = runWalkForwardBacktest(bars, { startingCapital: 100_000, riskPercent: 1, lotSize: 25, tickSize, feeBpsPerSide: 2, slippageTicks: 1 });
  assert.equal(result.windows.length, 3);
  assert.ok(result.windows[0].start < result.windows[1].start && result.windows[1].start < result.windows[2].start);
  assert.ok(result.windows.every((window) => window.result.warmupBars >= 51 && window.result.barsUsed >= 25));
  assert.ok(result.windows.every((window) => window.result.trades.every((trade) => trade.entryTime >= window.start && trade.exitTime <= window.end)));
  const alteredFuture = bars.map((bar, index) => index < 150 ? bar : { ...bar, open: 100, high: 100.1, low: 99.9, close: 100 });
  const replayWithAlteredFuture = runWalkForwardBacktest(alteredFuture, { startingCapital: 100_000, riskPercent: 1, lotSize: 25, tickSize, feeBpsPerSide: 2, slippageTicks: 1 });
  assert.deepEqual(result.windows[0].result, replayWithAlteredFuture.windows[0].result, "future bars cannot change the first window result");
  assert.throws(() => runWalkForwardBacktest(sampleBars(100), { startingCapital: 100_000, riskPercent: 1, lotSize: 1, tickSize, feeBpsPerSide: 0, slippageTicks: 0 }), /51 warm-up/);
});
