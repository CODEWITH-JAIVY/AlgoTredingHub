export type BacktestBar = {
  time: string;
  timestampMs: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
};

export type BacktestOptions = {
  startingCapital: number;
  riskPercent: number;
  lotSize: number;
  tickSize: number;
  feeBpsPerSide: number;
  slippageTicks: number;
  evaluationStartIndex?: number;
  evaluationEndIndex?: number;
};

export type BacktestTrade = {
  entryTime: string;
  exitTime: string;
  entryPrice: number;
  exitPrice: number;
  stopPrice: number;
  quantity: number;
  grossPnl: number;
  fees: number;
  netPnl: number;
  exitReason: "ATR_STOP" | "EMA_CROSS" | "SESSION_END" | "END_OF_DATA";
  equityAfter: number;
};

export type BacktestResult = {
  strategy: "EMA20/50 crossover + 2×ATR stop";
  strategyVersion: "basic-1.1";
  barsUsed: number;
  trades: BacktestTrade[];
  startingCapital: number;
  endingCapital: number;
  netPnl: number;
  returnPercent: number;
  winRatePercent: number;
  averageTradePnl: number;
  expectancy: number;
  profitFactor: number | null;
  positiveTradeNetPnl: number;
  negativeTradeNetPnl: number;
  maxExposureRupees: number;
  dailySharpeRatio: number | null;
  totalFees: number;
  maxDrawdownRupees: number;
  maxDrawdownPercent: number;
  equityCurve: Array<{ time: string; equity: number }>;
  warmupBars: number;
  evaluationStart: string;
  evaluationEnd: string;
  warnings: string[];
};

export type WalkForwardWindow = { window: number; start: string; end: string; result: BacktestResult };
export type WalkForwardResult = { method: string; windows: WalkForwardWindow[] };

const MAX_CSV_BYTES = 5 * 1024 * 1024;
const MAX_CSV_ROWS = 50_000;
const MAX_PRICE = 1_000_000_000_000;
const SESSION_OPEN_MINUTE = 9 * 60 + 15;
const SESSION_CLOSE_MINUTE = 15 * 60 + 30;
const MAX_FUTURE_MS = 5 * 60_000;

export function parseBacktestCsv(csv: string, tickSize: number, nowMs = Date.now()): BacktestBar[] {
  if (typeof csv !== "string" || csv.length === 0) throw new Error("CSV file is empty.");
  if (new TextEncoder().encode(csv).byteLength > MAX_CSV_BYTES) throw new Error("CSV file exceeds the 5 MB limit.");
  if (!Number.isFinite(tickSize) || tickSize <= 0 || tickSize > 10_000) throw new Error("Instrument tick size is invalid.");

  const lines = csv.replace(/^\uFEFF/, "").split(/\r?\n/).filter((line) => line.trim().length > 0);
  if (lines.length < 3) throw new Error("CSV needs a header and at least two data rows.");
  if (lines.length - 1 > MAX_CSV_ROWS) throw new Error(`CSV exceeds the ${MAX_CSV_ROWS.toLocaleString()}-row limit.`);

  const headers = splitCsvLine(lines[0]).map((value) => value.trim().toLowerCase());
  const required = ["timestamp", "open", "high", "low", "close", "volume"];
  const indexes = new Map<string, number>();
  headers.forEach((header, index) => {
    if (indexes.has(header)) throw new Error(`Duplicate CSV header: ${header || "(blank)"}.`);
    indexes.set(header, index);
  });
  const missing = required.filter((header) => !indexes.has(header));
  if (missing.length) throw new Error(`CSV needs these columns: ${missing.join(", ")}.`);

  const timeIndex = indexes.get("timestamp")!;
  const output: BacktestBar[] = [];
  let previous: BacktestBar | null = null;
  for (let lineIndex = 1; lineIndex < lines.length; lineIndex += 1) {
    const rowNumber = lineIndex + 1;
    const fields = splitCsvLine(lines[lineIndex]);
    if (fields.length !== headers.length) throw new Error(`Row ${rowNumber} has ${fields.length} columns; expected ${headers.length}.`);
    const time = parseTimestamp(fields[timeIndex].trim(), rowNumber);
    const values = ["open", "high", "low", "close"].map((key) => parsePositive(fields[indexes.get(key)!], key, rowNumber));
    const [open, high, low, close] = values;
    const rawVolume = fields[indexes.get("volume")!].trim();
    if (!rawVolume) throw new Error(`Row ${rowNumber} volume is required.`);
    const volume = Number(rawVolume);
    if (!Number.isSafeInteger(volume) || volume < 0) throw new Error(`Row ${rowNumber} volume must be a non-negative whole number.`);
    if (high < Math.max(open, close) || low > Math.min(open, close) || high < low) {
      throw new Error(`Row ${rowNumber} has inconsistent OHLC values.`);
    }
    if ([open, high, low, close].some((price) => !isTickAligned(price, tickSize))) {
      throw new Error(`Row ${rowNumber} prices do not align to the selected instrument tick size (${tickSize}).`);
    }
    const minuteOfDay = istMinuteOfDay(time.timestampMs);
    if (minuteOfDay < SESSION_OPEN_MINUTE || minuteOfDay >= SESSION_CLOSE_MINUTE) {
      throw new Error(`Row ${rowNumber} falls outside the regular 09:15–15:30 IST session.`);
    }
    if (time.timestampMs > nowMs + MAX_FUTURE_MS) throw new Error(`Row ${rowNumber} is in the future.`);
    const bar: BacktestBar = { time: new Date(time.timestampMs).toISOString(), timestampMs: time.timestampMs, open, high, low, close, volume };
    if (previous) {
      if (bar.timestampMs <= previous.timestampMs) throw new Error(`Row ${rowNumber} timestamps must be strictly increasing and unique.`);
      if (istSessionDate(bar.timestampMs) === istSessionDate(previous.timestampMs) && bar.timestampMs - previous.timestampMs !== 60_000) {
        throw new Error(`Gap or duplicate interval before row ${rowNumber}. Complete 1-minute candles are required within each session.`);
      }
    }
    output.push(bar);
    previous = bar;
  }
  if (output.length < 100) throw new Error("Import at least 100 valid 1-minute bars before running the backtest.");
  return output;
}

export function runBacktest(bars: BacktestBar[], options: BacktestOptions): BacktestResult {
  validateOptions(bars, options);
  const evaluationStartIndex = options.evaluationStartIndex ?? 51;
  const evaluationEndIndex = options.evaluationEndIndex ?? bars.length;
  const closes = bars.map((bar) => bar.close);
  const ema20 = ema(closes, 20);
  const ema50 = ema(closes, 50);
  const atr14 = atr(bars, 14);
  const feeRate = options.feeBpsPerSide / 10_000;
  const slippage = options.slippageTicks * options.tickSize;
  const trades: BacktestTrade[] = [];
  const equityCurve: Array<{ time: string; equity: number }> = [{ time: bars[evaluationStartIndex].time, equity: options.startingCapital }];
  let equity = options.startingCapital;
  let peakEquity = equity;
  let maxDrawdownRupees = 0;
  let maxExposureRupees = 0;
  let position: { entryTime: string; entryPrice: number; stopPrice: number; quantity: number; entryFee: number } | null = null;

  const closePosition = (bar: BacktestBar, rawExit: number, reason: BacktestTrade["exitReason"]) => {
    if (!position) return;
    const exitPrice = roundDown(rawExit, options.tickSize);
    const exitFee = exitPrice * position.quantity * feeRate;
    const grossPnl = (exitPrice - position.entryPrice) * position.quantity;
    const netPnl = grossPnl - position.entryFee - exitFee;
    equity += grossPnl - exitFee;
    trades.push({ entryTime: position.entryTime, exitTime: bar.time, entryPrice: position.entryPrice, exitPrice,
      stopPrice: position.stopPrice, quantity: position.quantity, grossPnl: roundMoney(grossPnl),
      fees: roundMoney(position.entryFee + exitFee), netPnl: roundMoney(netPnl), exitReason: reason, equityAfter: roundMoney(equity) });
    peakEquity = Math.max(peakEquity, equity);
    maxDrawdownRupees = Math.max(maxDrawdownRupees, peakEquity - equity);
    equityCurve.push({ time: bar.time, equity: roundMoney(equity) });
    position = null;
  };

  for (let index = 51; index < evaluationEndIndex; index += 1) {
    const current = bars[index];
    const previous = bars[index - 1];
    const signalIndex = index - 1;
    const newSession = istSessionDate(current.timestampMs) !== istSessionDate(previous.timestampMs);
    if (newSession && position) closePosition(previous, previous.close - slippage, "SESSION_END");

    const crossUp = !newSession && ema20[signalIndex] !== null && ema20[signalIndex - 1] !== null && ema50[signalIndex] !== null && ema50[signalIndex - 1] !== null &&
      ema20[signalIndex]! > ema50[signalIndex]! && ema20[signalIndex - 1]! <= ema50[signalIndex - 1]!;
    const crossDown = !newSession && ema20[signalIndex] !== null && ema20[signalIndex - 1] !== null && ema50[signalIndex] !== null && ema50[signalIndex - 1] !== null &&
      ema20[signalIndex]! < ema50[signalIndex]! && ema20[signalIndex - 1]! >= ema50[signalIndex - 1]!;

    if (position) {
      if (current.open <= position.stopPrice) closePosition(current, current.open - slippage, "ATR_STOP");
      else if (current.low <= position.stopPrice) closePosition(current, position.stopPrice - slippage, "ATR_STOP");
      else if (crossDown) closePosition(current, current.open - slippage, "EMA_CROSS");
    }
    if (!position && index >= evaluationStartIndex && !newSession && crossUp && atr14[signalIndex] !== null && equity > 0) {
      const entryPrice = roundUp(current.open + slippage, options.tickSize);
      const stopPrice = roundDown(entryPrice - 2 * atr14[signalIndex]!, options.tickSize);
      const stopFill = stopPrice - slippage;
      const feePerUnit = (entryPrice + stopPrice) * feeRate;
      const riskPerUnit = entryPrice - stopFill + feePerUnit;
      if (stopPrice > 0 && riskPerUnit > 0) {
        const riskBudget = equity * options.riskPercent / 100;
        const riskQuantity = floorToLot(riskBudget / riskPerUnit, options.lotSize);
        const exposureQuantity = floorToLot(equity / (entryPrice * (1 + feeRate)), options.lotSize);
        const quantity = Math.min(riskQuantity, exposureQuantity);
        if (quantity > 0 && Number.isSafeInteger(quantity)) {
          const entryFee = entryPrice * quantity * feeRate;
          if (entryFee < equity) {
            equity -= entryFee;
            position = { entryTime: current.time, entryPrice, stopPrice, quantity, entryFee };
            maxExposureRupees = Math.max(maxExposureRupees, entryPrice * quantity);
            if (current.low <= stopPrice) closePosition(current, stopPrice - slippage, "ATR_STOP");
          }
        }
      }
    }
  }
  if (position) closePosition(bars[evaluationEndIndex - 1], bars[evaluationEndIndex - 1].close - slippage, "END_OF_DATA");

  const netPnl = equity - options.startingCapital;
  const winners = trades.filter((trade) => trade.netPnl > 0).length;
  const positiveTradeNetPnl = trades.filter((trade) => trade.netPnl > 0).reduce((sum, trade) => sum + trade.netPnl, 0);
  const negativeTradeNetPnl = trades.filter((trade) => trade.netPnl < 0).reduce((sum, trade) => sum + trade.netPnl, 0);
  const dailySharpeRatio = calculateDailySharpe(bars, trades, evaluationStartIndex, evaluationEndIndex, options.startingCapital);
  return {
    strategy: "EMA20/50 crossover + 2×ATR stop", strategyVersion: "basic-1.1", trades,
    startingCapital: roundMoney(options.startingCapital), endingCapital: roundMoney(equity), netPnl: roundMoney(netPnl),
    returnPercent: roundMoney(netPnl / options.startingCapital * 100),
    winRatePercent: trades.length ? roundMoney(winners / trades.length * 100) : 0,
    averageTradePnl: trades.length ? roundMoney(trades.reduce((sum, trade) => sum + trade.netPnl, 0) / trades.length) : 0,
    expectancy: trades.length ? roundMoney(trades.reduce((sum, trade) => sum + trade.netPnl, 0) / trades.length) : 0,
    profitFactor: negativeTradeNetPnl < 0 ? roundMoney(positiveTradeNetPnl / Math.abs(negativeTradeNetPnl)) : null,
    positiveTradeNetPnl: roundMoney(positiveTradeNetPnl), negativeTradeNetPnl: roundMoney(negativeTradeNetPnl),
    maxExposureRupees: roundMoney(maxExposureRupees), dailySharpeRatio,
    totalFees: roundMoney(trades.reduce((sum, trade) => sum + trade.fees, 0)), maxDrawdownRupees: roundMoney(maxDrawdownRupees),
    maxDrawdownPercent: peakEquity > 0 ? roundMoney(maxDrawdownRupees / peakEquity * 100) : 0,
    equityCurve, barsUsed: evaluationEndIndex - evaluationStartIndex, warmupBars: evaluationStartIndex,
    evaluationStart: bars[evaluationStartIndex].time, evaluationEnd: bars[evaluationEndIndex - 1].time, warnings: [
      "CSV is processed in this browser tab and is not uploaded or saved by the app.",
      "The strategy fills at the next bar open, models configurable slippage and per-side fees, and uses a 2×ATR stop.",
      "Full notional is reserved; margin, taxes, statutory charges, corporate actions, dividends, auction sessions, and broker-specific fills are not modeled.",
      "This run has no benchmark or survivorship-bias adjustment. Daily Sharpe uses sample daily returns, zero risk-free rate, and √252 annualization; it is not meaningful with fewer than two variable-return sessions.",
      "Separate fixed-strategy walk-forward windows can show performance stability, but they do not remove data bias or predict future results.",
    ],
  };
}

export function runWalkForwardBacktest(bars: BacktestBar[], options: BacktestOptions, windowCount = 3): WalkForwardResult {
  validateOptions(bars, options);
  if (!Number.isSafeInteger(windowCount) || windowCount < 2 || windowCount > 4) throw new Error("Walk-forward requires 2–4 chronological windows.");
  const boundaries = Array.from({ length: windowCount + 1 }, (_, index) => Math.floor(bars.length * (index + 1) / (windowCount + 1)));
  if (boundaries[0] < 51) throw new Error("Walk-forward needs at least 51 warm-up bars before its first evaluation window.");
  const windows: WalkForwardWindow[] = [];
  for (let index = 0; index < windowCount; index += 1) {
    const start = boundaries[index];
    const end = index + 1 < windowCount ? boundaries[index + 1] : bars.length;
    if (end - start < 25) throw new Error("Each walk-forward window needs at least 25 evaluation bars.");
    const result = runBacktest(bars, { ...options, evaluationStartIndex: start, evaluationEndIndex: end });
    windows.push({ window: index + 1, start: result.evaluationStart, end: result.evaluationEnd, result });
  }
  return { method: `${windowCount} chronological anchored windows; fixed strategy and parameters; each window resets to the configured starting capital`, windows };
}

function validateOptions(bars: BacktestBar[], options: BacktestOptions) {
  if (!Array.isArray(bars) || bars.length < 100 || bars.length > MAX_CSV_ROWS) throw new Error("Backtest requires 100–50,000 bars.");
  if (!Number.isFinite(options.startingCapital) || options.startingCapital < 1 || options.startingCapital > 1_000_000_000) throw new Error("Capital must be between ₹1 and ₹1,000,000,000.");
  if (!Number.isFinite(options.riskPercent) || options.riskPercent <= 0 || options.riskPercent > 5) throw new Error("Risk must be greater than 0% and no more than 5% per trade.");
  if (!Number.isSafeInteger(options.lotSize) || options.lotSize < 1 || options.lotSize > 100_000) throw new Error("Lot size is invalid.");
  if (!Number.isFinite(options.tickSize) || options.tickSize <= 0) throw new Error("Tick size is invalid.");
  if (!Number.isFinite(options.feeBpsPerSide) || options.feeBpsPerSide < 0 || options.feeBpsPerSide > 100) throw new Error("Fee estimate must be between 0 and 100 basis points per side.");
  if (!Number.isSafeInteger(options.slippageTicks) || options.slippageTicks < 0 || options.slippageTicks > 50) throw new Error("Slippage must be between 0 and 50 ticks per side.");
  const start = options.evaluationStartIndex ?? 51, end = options.evaluationEndIndex ?? bars.length;
  if (!Number.isSafeInteger(start) || start < 51 || !Number.isSafeInteger(end) || end > bars.length || end <= start) throw new Error("Evaluation window must start after 51 warm-up bars and end within the dataset.");
  let prior = -Infinity;
  for (const bar of bars) {
    if (!Number.isFinite(bar.timestampMs) || bar.timestampMs <= prior || bar.timestampMs > Date.now() + MAX_FUTURE_MS ||
      new Date(bar.timestampMs).getUTCSeconds() !== 0 || new Date(bar.timestampMs).getUTCMilliseconds() !== 0 ||
      [bar.open, bar.high, bar.low, bar.close].some((price) => !Number.isFinite(price) || price <= 0 || price > MAX_PRICE) ||
      !Number.isSafeInteger(bar.volume) || bar.volume < 0) {
      throw new Error("Backtest bars must have increasing timestamps and finite positive OHLC prices.");
    }
    if (bar.high < Math.max(bar.open, bar.close) || bar.low > Math.min(bar.open, bar.close) || bar.high < bar.low ||
      [bar.open, bar.high, bar.low, bar.close].some((price) => !isTickAligned(price, options.tickSize))) {
      throw new Error("Backtest OHLC data is inconsistent or off tick size.");
    }
    if (istMinuteOfDay(bar.timestampMs) < SESSION_OPEN_MINUTE || istMinuteOfDay(bar.timestampMs) >= SESSION_CLOSE_MINUTE) throw new Error("Backtest bars must fall within the regular IST exchange session.");
    if (prior !== -Infinity && istSessionDate(bar.timestampMs) === istSessionDate(prior) && bar.timestampMs - prior !== 60_000) throw new Error("Backtest bars contain a missing or duplicate intraday minute.");
    prior = bar.timestampMs;
  }
}

function calculateDailySharpe(bars: BacktestBar[], trades: BacktestTrade[], start: number, end: number, startingCapital: number): number | null {
  const sessions = [...new Set(bars.slice(start, end).map((bar) => istSessionDate(bar.timestampMs)))];
  if (sessions.length < 2) return null;
  const bySession = new Map<string, number>();
  for (const trade of trades) {
    const session = istSessionDate(Date.parse(trade.exitTime));
    bySession.set(session, (bySession.get(session) ?? 0) + trade.netPnl);
  }
  let equity = startingCapital;
  const returns = sessions.map((session) => {
    const pnl = bySession.get(session) ?? 0;
    const dailyReturn = equity > 0 ? pnl / equity : 0;
    equity += pnl;
    return dailyReturn;
  });
  const mean = returns.reduce((sum, value) => sum + value, 0) / returns.length;
  const variance = returns.reduce((sum, value) => sum + (value - mean) ** 2, 0) / (returns.length - 1);
  const deviation = Math.sqrt(variance);
  return deviation > 0 ? roundMoney(mean / deviation * Math.sqrt(252)) : null;
}

function ema(values: number[], period: number): Array<number | null> {
  const result: Array<number | null> = Array(values.length).fill(null);
  if (values.length < period) return result;
  let current = values.slice(0, period).reduce((sum, value) => sum + value, 0) / period;
  result[period - 1] = current;
  const factor = 2 / (period + 1);
  for (let index = period; index < values.length; index += 1) {
    current = values[index] * factor + current * (1 - factor);
    result[index] = current;
  }
  return result;
}

function atr(bars: BacktestBar[], period: number): Array<number | null> {
  const result: Array<number | null> = Array(bars.length).fill(null);
  if (bars.length <= period) return result;
  const trueRange = bars.map((bar, index) => index === 0 ? bar.high - bar.low :
    Math.max(bar.high - bar.low, Math.abs(bar.high - bars[index - 1].close), Math.abs(bar.low - bars[index - 1].close)));
  let current = trueRange.slice(1, period + 1).reduce((sum, value) => sum + value, 0) / period;
  result[period] = current;
  for (let index = period + 1; index < bars.length; index += 1) {
    current = (current * (period - 1) + trueRange[index]) / period;
    result[index] = current;
  }
  return result;
}

function splitCsvLine(line: string): string[] {
  const cells: string[] = [];
  let value = "", quoted = false, closedQuote = false;
  for (let index = 0; index < line.length; index += 1) {
    const char = line[index];
    if (char === '"') {
      if (quoted && line[index + 1] === '"') { value += '"'; index += 1; }
      else if (quoted) { quoted = false; closedQuote = true; }
      else if (value.length === 0 && !closedQuote) quoted = true;
      else throw new Error("CSV contains a quote inside an unquoted field.");
    } else if (char === "," && !quoted) { cells.push(value); value = ""; closedQuote = false; }
    else if (closedQuote && !/\s/.test(char)) throw new Error("CSV contains characters after a quoted field.");
    else if (!closedQuote) value += char;
  }
  if (quoted) throw new Error("CSV contains an unclosed quoted field.");
  cells.push(value);
  return cells;
}

function parseTimestamp(value: string, row: number): { timestampMs: number } {
  const zoneSpecified = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(value);
  let timestampMs: number;
  const match = zoneSpecified
    ? /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?(Z|[+-]\d{2}:?\d{2})$/i.exec(value)
    : /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?$/.exec(value);
  if (!match) throw new Error(`Row ${row} timestamp must be ISO 8601 or YYYY-MM-DD HH:mm[:ss] IST.`);
  const [, yearText, monthText, dayText, hourText, minuteText, secondText = "0", fraction = "", zone] = match;
  const year = Number(yearText), month = Number(monthText), day = Number(dayText);
  const hour = Number(hourText), minute = Number(minuteText), second = Number(secondText);
  if (!validDateParts(year, month, day) || hour > 23 || minute > 59 || second !== 0 || /[1-9]/.test(fraction)) {
    throw new Error(`Row ${row} timestamp is invalid or not aligned to a whole minute.`);
  }
  if (zoneSpecified) {
    if (zone?.toUpperCase() !== "Z") {
      const offsetMatch = /([+-])(\d{2}):?(\d{2})/.exec(zone ?? "");
      if (!offsetMatch) throw new Error(`Row ${row} timestamp has an invalid timezone offset.`);
      const offsetHour = Number(offsetMatch[2]), offsetMinute = Number(offsetMatch[3]);
      if (offsetHour > 14 || offsetMinute > 59 || (offsetHour === 14 && offsetMinute !== 0)) throw new Error(`Row ${row} timestamp has an invalid timezone offset.`);
    }
    timestampMs = Date.parse(value);
  } else timestampMs = Date.UTC(year, month - 1, day, hour, minute) - 330 * 60_000;
  const parsed = new Date(timestampMs);
  if (!Number.isFinite(timestampMs) || parsed.getUTCSeconds() !== 0 || parsed.getUTCMilliseconds() !== 0) {
    throw new Error(`Row ${row} timestamp is invalid or not aligned to a whole minute.`);
  }
  return { timestampMs };
}

function validDateParts(year: number, month: number, day: number) {
  if (!Number.isInteger(year) || year < 1970 || year > 9999 || month < 1 || month > 12 || day < 1 || day > 31) return false;
  const date = new Date(0);
  date.setUTCFullYear(year, month - 1, day);
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day;
}

function parsePositive(value: string, name: string, row: number) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed <= 0 || parsed > MAX_PRICE) throw new Error(`Row ${row} ${name} must be finite and positive.`);
  return parsed;
}

function isTickAligned(value: number, tickSize: number) {
  const units = value / tickSize;
  return Number.isFinite(units) && Math.abs(units - Math.round(units)) < 1e-7;
}
function floorToLot(quantity: number, lotSize: number) { return Math.floor(Math.max(0, quantity) / lotSize) * lotSize; }
function roundDown(price: number, tickSize: number) { return Math.floor(price / tickSize + 1e-9) * tickSize; }
function roundUp(price: number, tickSize: number) { return Math.ceil(price / tickSize - 1e-9) * tickSize; }
function roundMoney(value: number) { return Math.round((value + Number.EPSILON) * 100) / 100; }
function istMinuteOfDay(timestampMs: number) { const date = new Date(timestampMs + 330 * 60_000); return date.getUTCHours() * 60 + date.getUTCMinutes(); }
function istSessionDate(timestampMs: number) { return new Date(timestampMs + 330 * 60_000).toISOString().slice(0, 10); }
