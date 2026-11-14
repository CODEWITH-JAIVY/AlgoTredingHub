import type { BacktestBar } from "./backtest";
import { calculateFifoPnl, type RealizedMatch, type TradeSegment, type TradebookFill } from "./tradebook.ts";

export type ReplaySide = "BUY" | "SELL";
export type ReplayOrderType = "MARKET" | "LIMIT" | "STOP_MARKET";
export type ReplayTimeInForce = "DAY" | "GTC";
export type ReplayOrderStatus = "PENDING" | "PARTIALLY_FILLED" | "FILLED" | "CANCELLED" | "EXPIRED" | "REJECTED";

export type ReplayOrder = {
  id: string;
  side: ReplaySide;
  type: ReplayOrderType;
  quantity: number;
  submittedAtIndex: number;
  limitPrice?: number;
  stopPrice?: number;
  timeInForce?: ReplayTimeInForce;
  /** A cancel requested at this bar's close takes effect from the following bar. */
  cancelRequestedAtIndex?: number;
};

export type PaperReplayOptions = {
  tickSize: number;
  lotSize: number;
  feeBpsPerSide: number;
  /** Adverse price adjustment per side, in ticks. */
  spreadTicksPerSide: number;
  /** Adverse price adjustment per side, in ticks. */
  slippageTicks: number;
  participationPercent: number;
  /** Extra full bars after the mandatory next-bar delay. */
  latencyBars: number;
  /** Number of eligible bars on which an unfilled order may try to execute. */
  maxWaitBars: number;
};

export type ReplayFill = {
  orderId: string;
  sequence: number;
  time: string;
  side: ReplaySide;
  quantity: number;
  price: number;
  fee: number;
  referencePrice: number;
  reason: "MARKET_OPEN" | "LIMIT_TOUCH" | "STOP_TRIGGER";
};

export type ReplayOrderResult = {
  id: string;
  side: ReplaySide;
  type: ReplayOrderType;
  status: ReplayOrderStatus;
  requestedQuantity: number;
  filledQuantity: number;
  remainingQuantity: number;
  averageFillPrice: number | null;
  totalFees: number;
  eligibleAtIndex: number;
  reason?: string;
};

export type PaperReplayResult = {
  fills: ReplayFill[];
  orders: ReplayOrderResult[];
  totalFees: number;
  warnings: string[];
};

export type PaperReplayAccounting = {
  segment: TradeSegment;
  symbol: string;
  fillCount: number;
  totalEstimatedFees: number;
  realizedGrossPnl: number;
  realizedCharges: number;
  realizedNetPnl: number;
  realizedMatches: RealizedMatch[];
  openPositions: Array<{
    direction: "LONG" | "SHORT";
    quantity: number;
    averageEntryPrice: number;
    markPrice: number;
    grossUnrealizedPnl: number;
    unallocatedEntryFees: number;
    netUnrealizedPnl: number;
  }>;
  grossUnrealizedPnl: number;
  unallocatedEntryFees: number;
  netUnrealizedPnl: number;
  combinedNetPnl: number;
  markedAt: string;
  warnings: string[];
};

const MAX_REPLAY_BARS = 50_000;
const MAX_REPLAY_ORDERS = 500;
const MAX_REPLAY_OPERATIONS = 5_000_000;
const MAX_REPLAY_FILL_ROWS = 50_000;
const MAX_PRICE = 1_000_000_000_000;
const MAX_FILL_NOTIONAL = 1_000_000_000;
const MAX_FILL_FEE = 1_000_000;
const SESSION_OPEN_MINUTE = 9 * 60 + 15;
const SESSION_CLOSE_MINUTE = 15 * 60 + 30;
const MAX_FUTURE_MS = 5 * 60_000;

/**
 * Deterministic, browser-local historical execution replay. Input order order is
 * time priority when several orders compete for the same bar's volume budget.
 */
export function runPaperReplay(bars: BacktestBar[], orders: ReplayOrder[], options: PaperReplayOptions): PaperReplayResult {
  validateReplayInputs(bars, orders, options);

  const states: ReplayOrderResult[] = orders.map((order) => {
    const rejectionReason = validateOrder(order, bars, options);
    return {
      id: order.id,
      side: order.side,
      type: order.type,
      status: rejectionReason ? "REJECTED" : "PENDING",
      requestedQuantity: order.quantity,
      filledQuantity: 0,
      remainingQuantity: order.quantity,
      averageFillPrice: null,
      totalFees: 0,
      eligibleAtIndex: order.submittedAtIndex + 1 + options.latencyBars,
      ...(rejectionReason ? { reason: rejectionReason } : {}),
    };
  });
  const fills: ReplayFill[] = [];
  const feeRate = options.feeBpsPerSide / 10_000;
  const executionCostTicks = options.spreadTicksPerSide + options.slippageTicks;

  for (let barIndex = 0; barIndex < bars.length; barIndex += 1) {
    const bar = bars[barIndex];
    const newSession = barIndex > 0 && sessionDate(bar.timestampMs) !== sessionDate(bars[barIndex - 1].timestampMs);
    if (newSession) {
      orders.forEach((order, index) => {
        const state = states[index];
        if (state.status === "PENDING" || state.status === "PARTIALLY_FILLED") {
          if ((order.timeInForce ?? "DAY") === "DAY") expire(state, "DAY order expired at the next session boundary.");
        }
      });
    }

    let barCapacity = floorToLot(bar.volume * options.participationPercent / 100, options.lotSize);
    for (let orderIndex = 0; orderIndex < orders.length; orderIndex += 1) {
      const order = orders[orderIndex];
      const state = states[orderIndex];
      if (!isOpen(state.status)) continue;

      if (order.cancelRequestedAtIndex !== undefined && barIndex > order.cancelRequestedAtIndex) {
        state.status = "CANCELLED";
        state.reason = "Cancel request became effective before this bar opened.";
        continue;
      }
      if (barIndex < state.eligibleAtIndex) continue;
      if (barIndex >= state.eligibleAtIndex + options.maxWaitBars) {
        expire(state, "Maximum eligible-bar wait was reached.");
        continue;
      }
      if (barCapacity < options.lotSize) continue;

      const trigger = getTrigger(order, bar);
      if (!trigger) continue;
      const quantity = Math.min(state.remainingQuantity, barCapacity);
      if (quantity < options.lotSize) continue;
      const price = order.type === "LIMIT"
        ? roundToTick(trigger.referencePrice, options.tickSize, order.side === "BUY" ? "down" : "up")
        : roundToTick(trigger.referencePrice + (order.side === "BUY" ? 1 : -1) * executionCostTicks * options.tickSize,
          options.tickSize, order.side === "BUY" ? "up" : "down");
      if (!Number.isFinite(price) || price <= 0 || (order.type === "LIMIT" && !withinLimit(order, price))) {
        expire(state, "Execution price was invalid or would violate the limit price.");
        continue;
      }

      if (quantity * price > MAX_FILL_NOTIONAL) {
        state.status = "REJECTED";
        state.reason = "Fill notional exceeds the ₹1,000,000,000 simulation accounting limit.";
        continue;
      }
      const fee = roundMoney(price * quantity * feeRate);
      if (fee > MAX_FILL_FEE) {
        state.status = "REJECTED";
        state.reason = "Estimated fill fees exceed the ₹1,000,000 simulation accounting limit.";
        continue;
      }
      const previousFilled = state.filledQuantity;
      state.filledQuantity += quantity;
      state.remainingQuantity -= quantity;
      state.totalFees = roundMoney(state.totalFees + fee);
      state.averageFillPrice = roundMoney(((state.averageFillPrice ?? 0) * previousFilled + price * quantity) / state.filledQuantity);
      state.status = state.remainingQuantity === 0 ? "FILLED" : "PARTIALLY_FILLED";
      barCapacity -= quantity;
      if (fills.length >= MAX_REPLAY_FILL_ROWS) throw new Error("Replay exceeds the 50,000-fill accounting limit; reduce orders or history size.");
      fills.push({ orderId: order.id, sequence: fills.length + 1, time: bar.time, side: order.side, quantity, price,
        fee, referencePrice: trigger.referencePrice, reason: trigger.reason });
    }
  }

  states.forEach((state) => {
    if (isOpen(state.status)) expire(state, "Historical replay ended before the remaining quantity could fill.");
  });
  return {
    fills,
    orders: states,
    totalFees: roundMoney(states.reduce((sum, state) => sum + state.totalFees, 0)),
    warnings: [
      "This is a deterministic historical simulation using only the bars supplied in this browser session; no file or order is sent to a server or broker.",
      "Orders submitted on a bar cannot fill on that bar. They become eligible after the next bar plus the configured extra latency.",
      "OHLC bars do not reveal intrabar event order or queue position. Limit fills use the stated limit; stop and market fills apply adverse spread and slippage.",
      "Volume participation is shared in input order priority. A zero-volume bar cannot fill; quantities and capacity are rounded down to whole lots.",
      "DAY orders expire at a session boundary; GTC orders may continue across dates. Remaining orders expire when the imported history ends.",
      "Fees are estimates only. Taxes, statutory charges, market impact, margin, broker rules, exchange holidays, and actual liquidity are not modeled.",
    ],
  };
}

/** FIFO P&L for replay fills; open inventory is marked to the final imported close. */
export function calculatePaperReplayAccounting(replay: PaperReplayResult, bars: BacktestBar[], segment: TradeSegment, symbol: string): PaperReplayAccounting {
  if (!replay || !Array.isArray(replay.fills) || replay.fills.length > MAX_REPLAY_FILL_ROWS) throw new Error("Replay fills exceed the accounting limit.");
  if (!Array.isArray(bars) || bars.length === 0) throw new Error("A final historical close is required to mark open replay positions.");
  if (replay.fills.some((fill) => !fill || typeof fill !== "object")) throw new Error("Replay contains an invalid fill row.");
  if (!["CASH_INTRADAY", "CASH_DELIVERY", "FNO"].includes(segment) || typeof symbol !== "string" || !symbol.trim() || symbol.length > 120 || /[\u0000-\u001f]/.test(symbol)) {
    throw new Error("Replay accounting needs a supported segment and printable instrument symbol.");
  }
  const finalBar = bars[bars.length - 1];
  const markTimestampMs = finalBar && Date.parse(finalBar.time);
  if (!finalBar || !Number.isFinite(finalBar.timestampMs) || markTimestampMs !== finalBar.timestampMs || replay.fills.some((fill) => !fill || Date.parse(fill.time) > markTimestampMs)) {
    throw new Error("Replay fills and final mark must have valid, ordered timestamps.");
  }
  const markPrice = finalBar.close;
  if (!Number.isFinite(markPrice) || markPrice <= 0 || markPrice > MAX_PRICE) throw new Error("Final close is invalid for mark-to-market accounting.");
  const fills: TradebookFill[] = replay.fills.map((fill) => ({
    tradeId: `paper-${String(fill.sequence).padStart(8, "0")}-${fill.orderId}`,
    timestamp: fill.time,
    timestampMs: Date.parse(fill.time),
    segment,
    symbol,
    side: fill.side,
    quantity: fill.quantity,
    price: fill.price,
    charges: fill.fee,
  }));
  const fillFees = roundMoney(replay.fills.reduce((sum, fill) => sum + Number(fill.fee), 0));
  if (!Number.isFinite(replay.totalFees) || Math.abs(fillFees - replay.totalFees) > 0.01) throw new Error("Replay fee total does not match its fill rows.");
  const report = fills.length ? calculateFifoPnl(fills) : null;
  const openPositions = (report?.openInventory ?? []).map((position) => {
    const grossUnrealizedPnl = roundMoney((position.direction === "LONG" ? markPrice - position.averageEntryPrice : position.averageEntryPrice - markPrice) * position.quantity);
    const netUnrealizedPnl = roundMoney(grossUnrealizedPnl - position.unallocatedEntryCharges);
    return { direction: position.direction, quantity: position.quantity, averageEntryPrice: position.averageEntryPrice, markPrice,
      grossUnrealizedPnl, unallocatedEntryFees: position.unallocatedEntryCharges, netUnrealizedPnl };
  });
  const realizedGrossPnl = report?.realizedGrossPnl ?? 0;
  const realizedCharges = report?.realizedCharges ?? 0;
  const realizedNetPnl = report?.realizedNetPnl ?? 0;
  const grossUnrealizedPnl = roundMoney(openPositions.reduce((sum, position) => sum + position.grossUnrealizedPnl, 0));
  const unallocatedEntryFees = roundMoney(openPositions.reduce((sum, position) => sum + position.unallocatedEntryFees, 0));
  const netUnrealizedPnl = roundMoney(openPositions.reduce((sum, position) => sum + position.netUnrealizedPnl, 0));
  return {
    segment, symbol, fillCount: replay.fills.length, totalEstimatedFees: report?.totalCharges ?? 0,
    realizedGrossPnl, realizedCharges, realizedNetPnl, realizedMatches: report?.realizedMatches ?? [], openPositions, grossUnrealizedPnl, unallocatedEntryFees,
    netUnrealizedPnl, combinedNetPnl: roundMoney(realizedNetPnl + netUnrealizedPnl), markedAt: bars[bars.length - 1].time,
    warnings: [
      "Realized P&L uses FIFO on simulated fills and estimated per-fill fees; it is not broker-reconciled.",
      "Open positions are marked to the final imported candle close. Open P&L subtracts entry fees but excludes estimated exit fees and any liquidation slippage.",
    "Taxes, statutory charges, margin, corporate actions, delivery tax-lot rules, and tax liability are not included. This report is for reference, not tax advice.",
    ],
  };
}

function validateReplayInputs(bars: BacktestBar[], orders: ReplayOrder[], options: PaperReplayOptions) {
  if (!options || typeof options !== "object") throw new Error("Replay assumptions are required.");
  if (!Array.isArray(bars) || bars.length < 1 || bars.length > MAX_REPLAY_BARS) throw new Error("Replay needs 1–50,000 historical bars.");
  if (!Array.isArray(orders) || orders.length > MAX_REPLAY_ORDERS) throw new Error("Replay accepts at most 500 orders.");
  if (bars.length * orders.length > MAX_REPLAY_OPERATIONS) throw new Error("Replay order/history combination exceeds the safe computation limit.");
  if (!Number.isFinite(options.tickSize) || options.tickSize < 0.00000001 || options.tickSize > 10_000) throw new Error("Tick size is invalid.");
  if (!Number.isSafeInteger(options.lotSize) || options.lotSize < 1 || options.lotSize > 100_000) throw new Error("Lot size is invalid.");
  if (!Number.isFinite(options.feeBpsPerSide) || options.feeBpsPerSide < 0 || options.feeBpsPerSide > 100) throw new Error("Fee estimate must be between 0 and 100 basis points per side.");
  if (!Number.isSafeInteger(options.spreadTicksPerSide) || options.spreadTicksPerSide < 0 || options.spreadTicksPerSide > 100) throw new Error("Spread estimate must be between 0 and 100 ticks per side.");
  if (!Number.isSafeInteger(options.slippageTicks) || options.slippageTicks < 0 || options.slippageTicks > 100) throw new Error("Slippage estimate must be between 0 and 100 ticks per side.");
  if (!Number.isFinite(options.participationPercent) || options.participationPercent <= 0 || options.participationPercent > 100) throw new Error("Participation must be greater than 0% and no more than 100%.");
  if (!Number.isSafeInteger(options.latencyBars) || options.latencyBars < 0 || options.latencyBars > 100) throw new Error("Latency must be between 0 and 100 additional bars.");
  if (!Number.isSafeInteger(options.maxWaitBars) || options.maxWaitBars < 1 || options.maxWaitBars > 10_000) throw new Error("Maximum wait must be between 1 and 10,000 eligible bars.");

  let previous = -Infinity;
  for (const bar of bars) {
    if (!bar || typeof bar !== "object" || !Number.isFinite(bar.timestampMs) || bar.timestampMs <= previous || bar.timestampMs > Date.now() + MAX_FUTURE_MS ||
      typeof bar.time !== "string" || Date.parse(bar.time) !== bar.timestampMs ||
      new Date(bar.timestampMs).getUTCSeconds() !== 0 || new Date(bar.timestampMs).getUTCMilliseconds() !== 0 ||
      [bar.open, bar.high, bar.low, bar.close].some((price) => !Number.isFinite(price) || price <= 0 || price > MAX_PRICE) ||
      !Number.isSafeInteger(bar.volume) || bar.volume < 0 || bar.high < Math.max(bar.open, bar.close) ||
      bar.low > Math.min(bar.open, bar.close) || bar.high < bar.low ||
      [bar.open, bar.high, bar.low, bar.close].some((price) => !isTickAligned(price, options.tickSize)) ||
      istMinuteOfDay(bar.timestampMs) < SESSION_OPEN_MINUTE || istMinuteOfDay(bar.timestampMs) >= SESSION_CLOSE_MINUTE) {
      throw new Error("Replay bars must have increasing timestamps and valid, tick-aligned OHLCV values within the regular IST session.");
    }
    if (previous !== -Infinity && sessionDate(bar.timestampMs) === sessionDate(previous) && bar.timestampMs - previous !== 60_000) {
      throw new Error("Replay bars contain a missing or duplicate intraday minute.");
    }
    previous = bar.timestampMs;
  }
  const ids = new Set<string>();
  for (const order of orders) {
    if (!order || typeof order.id !== "string" || !order.id.trim() || order.id.length > 100 || /[\u0000-\u001f]/.test(order.id)) throw new Error("Every replay order needs a printable ID of 1–100 characters.");
    if (ids.has(order.id)) throw new Error(`Duplicate replay order ID: ${order.id}.`);
    ids.add(order.id);
  }
}

function validateOrder(order: ReplayOrder, bars: BacktestBar[], options: PaperReplayOptions): string | undefined {
  if (order.side !== "BUY" && order.side !== "SELL") return "Unsupported order side.";
  if (!["MARKET", "LIMIT", "STOP_MARKET"].includes(order.type)) return "Unsupported order type.";
  if (!Number.isSafeInteger(order.quantity) || order.quantity < options.lotSize || order.quantity > 1_000_000_000 || order.quantity % options.lotSize !== 0) return "Quantity must be a positive whole number of lots within the simulation limit.";
  if (!Number.isSafeInteger(order.submittedAtIndex) || order.submittedAtIndex < 0 || order.submittedAtIndex >= bars.length) return "Submission bar is outside the replay history.";
  if (order.timeInForce !== undefined && order.timeInForce !== "DAY" && order.timeInForce !== "GTC") return "Unsupported time in force.";
  if (order.cancelRequestedAtIndex !== undefined && (!Number.isSafeInteger(order.cancelRequestedAtIndex) || order.cancelRequestedAtIndex < order.submittedAtIndex || order.cancelRequestedAtIndex >= bars.length)) return "Cancel request bar must be within the history and no earlier than submission.";
  if (order.type === "LIMIT" && !validOrderPrice(order.limitPrice, options.tickSize)) return "Limit order needs a positive tick-aligned limit price.";
  if (order.type === "STOP_MARKET" && !validOrderPrice(order.stopPrice, options.tickSize)) return "Stop order needs a positive tick-aligned stop price.";
  return undefined;
}

function validOrderPrice(price: number | undefined, tickSize: number) {
  return typeof price === "number" && Number.isFinite(price) && price > 0 && price <= MAX_PRICE && isTickAligned(price, tickSize);
}

function getTrigger(order: ReplayOrder, bar: BacktestBar): { referencePrice: number; reason: ReplayFill["reason"] } | null {
  if (order.type === "MARKET") return { referencePrice: bar.open, reason: "MARKET_OPEN" };
  if (order.type === "LIMIT") {
    const limit = order.limitPrice!;
    const touched = order.side === "BUY" ? bar.low <= limit : bar.high >= limit;
    if (!touched) return null;
    const referencePrice = order.side === "BUY" ? Math.min(bar.open, limit) : Math.max(bar.open, limit);
    return { referencePrice, reason: "LIMIT_TOUCH" };
  }
  const stop = order.stopPrice!;
  const triggered = order.side === "BUY" ? bar.high >= stop : bar.low <= stop;
  if (!triggered) return null;
  const referencePrice = order.side === "BUY" ? Math.max(bar.open, stop) : Math.min(bar.open, stop);
  return { referencePrice, reason: "STOP_TRIGGER" };
}

function withinLimit(order: ReplayOrder, price: number) {
  return order.side === "BUY" ? price <= order.limitPrice! : price >= order.limitPrice!;
}
function isOpen(status: ReplayOrderStatus) { return status === "PENDING" || status === "PARTIALLY_FILLED"; }
function expire(state: ReplayOrderResult, reason: string) { state.status = "EXPIRED"; state.reason = reason; }
function floorToLot(quantity: number, lotSize: number) { return Math.floor(Math.max(0, quantity) / lotSize) * lotSize; }
function roundToTick(price: number, tickSize: number, direction: "up" | "down") {
  const units = price / tickSize;
  return Number(((direction === "up" ? Math.ceil(units - 1e-9) : Math.floor(units + 1e-9)) * tickSize).toPrecision(14));
}
function roundMoney(value: number) { return Math.round((value + Number.EPSILON) * 100) / 100; }
function isTickAligned(price: number, tickSize: number) { const units = price / tickSize; return Number.isFinite(units) && Math.abs(units - Math.round(units)) < 1e-7; }
function istMinuteOfDay(timestampMs: number) { const date = new Date(timestampMs + 330 * 60_000); return date.getUTCHours() * 60 + date.getUTCMinutes(); }
function sessionDate(timestampMs: number) { return new Date(timestampMs + 330 * 60_000).toISOString().slice(0, 10); }
