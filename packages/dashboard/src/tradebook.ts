export type TradeSegment = "CASH_INTRADAY" | "CASH_DELIVERY" | "FNO";
export type TradeSide = "BUY" | "SELL";
export type TradebookFill = {
  tradeId: string;
  timestamp: string;
  timestampMs: number;
  segment: TradeSegment;
  symbol: string;
  side: TradeSide;
  quantity: number;
  price: number;
  charges: number;
};
export type RealizedMatch = {
  entryTradeId: string;
  exitTradeId: string;
  entryTime: string;
  exitTime: string;
  segment: TradeSegment;
  symbol: string;
  direction: "LONG" | "SHORT";
  quantity: number;
  entryPrice: number;
  exitPrice: number;
  grossPnl: number;
  charges: number;
  netPnl: number;
};
export type OpenInventory = {
  segment: TradeSegment;
  symbol: string;
  direction: "LONG" | "SHORT";
  quantity: number;
  averageEntryPrice: number;
  unallocatedEntryCharges: number;
};
export type SegmentPnl = { segment: TradeSegment; matchedQuantity: number; matches: number; grossPnl: number; charges: number; netPnl: number };
export type TradebookReport = {
  fills: number;
  totalCharges: number;
  realizedGrossPnl: number;
  realizedCharges: number;
  realizedNetPnl: number;
  realizedMatches: RealizedMatch[];
  bySegment: SegmentPnl[];
  openInventory: OpenInventory[];
  openQuantity: number;
  openCharges: number;
  warnings: string[];
};

const MAX_CSV_BYTES = 5 * 1024 * 1024;
const MAX_ROWS = 50_000;
const MAX_AMOUNT = 1_000_000_000_000;
const MAX_FILL_NOTIONAL = 1_000_000_000;
const MAX_FILL_CHARGES = 1_000_000;
const SEGMENTS = new Set<TradeSegment>(["CASH_INTRADAY", "CASH_DELIVERY", "FNO"]);

export function parseTradebookCsv(csv: string, nowMs = Date.now()): TradebookFill[] {
  if (typeof csv !== "string" || !csv.trim()) throw new Error("Tradebook CSV is empty.");
  if (new TextEncoder().encode(csv).byteLength > MAX_CSV_BYTES) throw new Error("Tradebook CSV exceeds the 5 MB limit.");
  const lines = csv.replace(/^\uFEFF/, "").split(/\r?\n/).filter((line) => line.trim());
  if (lines.length < 2) throw new Error("Tradebook needs a header and at least one fill.");
  if (lines.length - 1 > MAX_ROWS) throw new Error(`Tradebook exceeds the ${MAX_ROWS.toLocaleString()}-row limit.`);
  const headers = parseCsvLine(lines[0]).map((value) => value.trim().toLowerCase());
  const indexes = new Map<string, number>();
  headers.forEach((header, index) => {
    if (!header || indexes.has(header)) throw new Error(`Tradebook has a blank or duplicate header: ${header || "(blank)"}.`);
    indexes.set(header, index);
  });
  const required = ["trade_id", "timestamp", "segment", "symbol", "side", "quantity", "price", "charges"];
  const missing = required.filter((field) => !indexes.has(field));
  if (missing.length) throw new Error(`Tradebook needs these columns: ${missing.join(", ")}.`);
  const ids = new Set<string>();
  const fills: TradebookFill[] = [];
  for (let index = 1; index < lines.length; index += 1) {
    const row = index + 1;
    const fields = parseCsvLine(lines[index]);
    if (fields.length !== headers.length) throw new Error(`Row ${row} has ${fields.length} columns; expected ${headers.length}.`);
    const get = (name: string) => fields[indexes.get(name)!].trim();
    const tradeId = get("trade_id"), symbol = get("symbol");
    if (!tradeId || tradeId.length > 120 || /[\u0000-\u001f]/.test(tradeId) || ids.has(tradeId)) throw new Error(`Row ${row} trade_id must be present, unique, and at most 120 printable characters.`);
    if (!symbol || symbol.length > 120 || /[\u0000-\u001f]/.test(symbol)) throw new Error(`Row ${row} symbol must be present and at most 120 printable characters.`);
    ids.add(tradeId);
    const segmentValue = get("segment").toUpperCase() as TradeSegment;
    if (!SEGMENTS.has(segmentValue)) throw new Error(`Row ${row} segment must be CASH_INTRADAY, CASH_DELIVERY, or FNO.`);
    const side = get("side").toUpperCase();
    if (side !== "BUY" && side !== "SELL") throw new Error(`Row ${row} side must be BUY or SELL.`);
    const quantity = Number(get("quantity")), price = Number(get("price")), charges = Number(get("charges"));
    if (!Number.isSafeInteger(quantity) || quantity <= 0 || quantity > 1_000_000_000) throw new Error(`Row ${row} quantity must be a positive whole number.`);
    if (!Number.isFinite(price) || price <= 0 || price > MAX_AMOUNT || quantity * price > MAX_FILL_NOTIONAL) throw new Error(`Row ${row} fill notional must be finite, positive, and no more than ₹1,000,000,000.`);
    if (!Number.isFinite(charges) || charges < 0 || charges > MAX_FILL_CHARGES || Math.abs(charges * 100 - Math.round(charges * 100)) > 1e-7) throw new Error(`Row ${row} charges must be finite, non-negative, at most ₹1,000,000, and use no more than two decimal places.`);
    const timestampMs = parseTimestamp(get("timestamp"), row, nowMs);
    fills.push({ tradeId, timestamp: new Date(timestampMs).toISOString(), timestampMs, segment: segmentValue, symbol, side, quantity, price, charges });
  }
  fills.sort((a, b) => a.timestampMs - b.timestampMs || a.tradeId.localeCompare(b.tradeId));
  return fills;
}

export function calculateFifoPnl(fills: TradebookFill[]): TradebookReport {
  if (!Array.isArray(fills) || fills.length === 0 || fills.length > MAX_ROWS) throw new Error("P&L calculation requires 1–50,000 normalized trade fills.");
  const tradeIds = new Set<string>();
  for (const fill of fills) {
    if (!fill || typeof fill.tradeId !== "string" || !fill.tradeId || fill.tradeId.length > 120 || /[\u0000-\u001f]/.test(fill.tradeId) ||
      typeof fill.symbol !== "string" || !fill.symbol || fill.symbol.length > 120 || /[\u0000-\u001f]/.test(fill.symbol) ||
      !SEGMENTS.has(fill.segment) || (fill.side !== "BUY" && fill.side !== "SELL") ||
      !Number.isSafeInteger(fill.quantity) || fill.quantity <= 0 || fill.quantity > 1_000_000_000 || !Number.isFinite(fill.price) || fill.price <= 0 || fill.quantity * fill.price > MAX_FILL_NOTIONAL ||
      !Number.isFinite(fill.charges) || fill.charges < 0 || fill.charges > MAX_FILL_CHARGES || Math.abs(fill.charges * 100 - Math.round(fill.charges * 100)) > 1e-7 || !Number.isFinite(fill.timestampMs) ||
      typeof fill.timestamp !== "string" || Date.parse(fill.timestamp) !== fill.timestampMs || fill.timestampMs > Date.now() + 5 * 60_000 || tradeIds.has(fill.tradeId)) throw new Error("Trade fills are invalid or contain duplicate trade IDs.");
    tradeIds.add(fill.tradeId);
  }
  fills = [...fills].sort((a, b) => a.timestampMs - b.timestampMs || a.tradeId.localeCompare(b.tradeId));
  const positions = new Map<string, Array<{ direction: "LONG" | "SHORT"; quantity: number; price: number; chargesPaise: number; tradeId: string; time: string }>>();
  const matches: RealizedMatch[] = [];
  const totalChargesPaise = fills.reduce((sum, fill) => sum + Math.round(fill.charges * 100), 0);
  for (const fill of fills) {
    const key = `${fill.segment}\u0000${fill.symbol}`;
    const lots = positions.get(key) ?? [];
    positions.set(key, lots);
    let remainingQuantity = fill.quantity;
    let remainingChargesPaise = Math.round(fill.charges * 100);
    const incomingDirection = fill.side === "BUY" ? "LONG" : "SHORT";
    while (remainingQuantity > 0 && lots.length > 0 && lots[0].direction !== incomingDirection) {
      const lot = lots[0];
      const matchedQuantity = Math.min(remainingQuantity, lot.quantity);
      const exitChargesPaise = matchedQuantity === remainingQuantity ? remainingChargesPaise : Math.min(remainingChargesPaise, Math.round(Math.round(fill.charges * 100) * matchedQuantity / fill.quantity));
      const entryChargesPaise = matchedQuantity === lot.quantity ? lot.chargesPaise : Math.round(lot.chargesPaise * matchedQuantity / lot.quantity);
      const grossPnl = (lot.direction === "LONG" ? fill.price - lot.price : lot.price - fill.price) * matchedQuantity;
      const charges = (entryChargesPaise + exitChargesPaise) / 100;
      matches.push({ entryTradeId: lot.tradeId, exitTradeId: fill.tradeId, entryTime: lot.time, exitTime: fill.timestamp,
        segment: fill.segment, symbol: fill.symbol, direction: lot.direction, quantity: matchedQuantity,
        entryPrice: lot.price, exitPrice: fill.price, grossPnl: roundMoney(grossPnl), charges: roundMoney(charges), netPnl: roundMoney(grossPnl - charges) });
      lot.quantity -= matchedQuantity; lot.chargesPaise = Math.max(0, lot.chargesPaise - entryChargesPaise);
      remainingQuantity -= matchedQuantity; remainingChargesPaise = Math.max(0, remainingChargesPaise - exitChargesPaise);
      if (lot.quantity === 0) lots.shift();
    }
    if (remainingQuantity > 0) lots.push({ direction: incomingDirection, quantity: remainingQuantity, price: fill.price,
      chargesPaise: remainingChargesPaise, tradeId: fill.tradeId, time: fill.timestamp });
  }

  const segmentMap = new Map<TradeSegment, SegmentPnl>();
  for (const match of matches) {
    const summary = segmentMap.get(match.segment) ?? { segment: match.segment, matchedQuantity: 0, matches: 0, grossPnl: 0, charges: 0, netPnl: 0 };
    summary.matchedQuantity += match.quantity; summary.matches += 1; summary.grossPnl += match.grossPnl; summary.charges += match.charges; summary.netPnl += match.netPnl;
    segmentMap.set(match.segment, summary);
  }
  const openInventory: OpenInventory[] = [];
  for (const [key, lots] of positions) {
    const [segment] = key.split("\u0000") as [TradeSegment, string];
    const grouped = new Map<string, { direction: "LONG" | "SHORT"; quantity: number; value: number; charges: number }>();
    for (const lot of lots) {
      const groupKey = lot.direction;
      const group = grouped.get(groupKey) ?? { direction: lot.direction, quantity: 0, value: 0, charges: 0 };
      group.quantity += lot.quantity; group.value += lot.quantity * lot.price; group.charges += lot.chargesPaise / 100; grouped.set(groupKey, group);
    }
    const symbol = key.slice(key.indexOf("\u0000") + 1);
    for (const group of grouped.values()) openInventory.push({ segment, symbol, direction: group.direction, quantity: group.quantity,
      averageEntryPrice: roundMoney(group.value / group.quantity), unallocatedEntryCharges: roundMoney(group.charges) });
  }
  const realizedGrossPnl = matches.reduce((sum, match) => sum + match.grossPnl, 0);
  const realizedCharges = matches.reduce((sum, match) => sum + match.charges, 0);
  const openQuantity = openInventory.reduce((sum, item) => sum + item.quantity, 0);
  const openCharges = openInventory.reduce((sum, item) => sum + item.unallocatedEntryCharges, 0);
  return {
    fills: fills.length, totalCharges: roundMoney(totalChargesPaise / 100), realizedGrossPnl: roundMoney(realizedGrossPnl),
    realizedCharges: roundMoney(realizedCharges), realizedNetPnl: roundMoney(matches.reduce((sum, match) => sum + match.netPnl, 0)),
    realizedMatches: matches, bySegment: [...segmentMap.values()].map((summary) => ({ ...summary,
      grossPnl: roundMoney(summary.grossPnl), charges: roundMoney(summary.charges), netPnl: roundMoney(summary.netPnl) })),
    openInventory, openQuantity, openCharges: roundMoney(openCharges),
    warnings: [
      "P&L uses FIFO matching on the supplied executed fills and user-provided charges; it is not reconciled against a broker account.",
      "Open inventory is not marked to market. Only matched quantity is included in realized P&L; remaining entry charges are shown separately.",
      "Segment labels and full instrument keys are trusted from the CSV. The report is for reference, not tax advice, and does not calculate taxable income or statutory charges.",
    ],
  };
}

export function buildTradebookExportCsv(report: TradebookReport, generatedAt = new Date().toISOString()): string {
  const columns = ["record_type", "generated_at", "fills", "total_charges", "segment", "symbol", "direction", "quantity",
    "entry_trade_id", "exit_trade_id", "entry_time", "exit_time", "entry_price", "exit_price", "gross_pnl", "charges",
    "net_pnl", "average_entry_price", "open_entry_charges", "warning"] as const;
  const rows: Record<(typeof columns)[number], string | number | null>[] = [];
  const empty = (): Record<(typeof columns)[number], string | number | null> => Object.fromEntries(columns.map((column) => [column, null])) as Record<(typeof columns)[number], string | number | null>;
  rows.push({ ...empty(), record_type: "SUMMARY", generated_at: generatedAt, fills: report.fills, total_charges: report.totalCharges,
    quantity: report.openQuantity, gross_pnl: report.realizedGrossPnl, charges: report.realizedCharges, net_pnl: report.realizedNetPnl,
    open_entry_charges: report.openCharges });
  for (const match of report.realizedMatches) rows.push({ ...empty(), record_type: "REALIZED_MATCH", segment: match.segment,
    symbol: match.symbol, direction: match.direction, quantity: match.quantity, entry_trade_id: match.entryTradeId,
    exit_trade_id: match.exitTradeId, entry_time: match.entryTime, exit_time: match.exitTime, entry_price: match.entryPrice,
    exit_price: match.exitPrice, gross_pnl: match.grossPnl, charges: match.charges, net_pnl: match.netPnl });
  for (const item of report.openInventory) rows.push({ ...empty(), record_type: "OPEN_INVENTORY", segment: item.segment, symbol: item.symbol,
    direction: item.direction, quantity: item.quantity, average_entry_price: item.averageEntryPrice,
    open_entry_charges: item.unallocatedEntryCharges });
  for (const warning of report.warnings) rows.push({ ...empty(), record_type: "NOTE", warning });
  return [columns.join(","), ...rows.map((row) => columns.map((column) => csvCell(row[column])).join(","))].join("\r\n");
}

function parseTimestamp(value: string, row: number, nowMs: number) {
  const zoned = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(value);
  const match = zoned
    ? /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,3}))?)?(Z|[+-]\d{2}:?\d{2})$/i.exec(value)
    : /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,3}))?)?$/.exec(value);
  if (!match) throw new Error(`Row ${row} timestamp must be ISO 8601 with a timezone or local YYYY-MM-DD HH:mm[:ss] IST.`);
  const [, yearText, monthText, dayText, hourText, minuteText, secondText = "0", fraction = "", zone] = match;
  const year = Number(yearText), month = Number(monthText), day = Number(dayText), hour = Number(hourText), minute = Number(minuteText), second = Number(secondText);
  if (!validDate(year, month, day) || hour > 23 || minute > 59 || second > 59) throw new Error(`Row ${row} timestamp is not a valid calendar time.`);
  if (zone && zone.toUpperCase() !== "Z") {
    const offset = /[+-](\d{2}):?(\d{2})/.exec(zone);
    const offsetHour = Number(offset?.[1]), offsetMinute = Number(offset?.[2]);
    if (offsetHour > 14 || offsetMinute > 59 || (offsetHour === 14 && offsetMinute !== 0)) throw new Error(`Row ${row} timezone offset is invalid.`);
  }
  const timestampMs = zoned ? Date.parse(value) : Date.UTC(year, month - 1, day, hour, minute, second, Number((fraction + "000").slice(0, 3))) - 330 * 60_000;
  if (!Number.isFinite(timestampMs) || timestampMs > nowMs + 5 * 60_000) throw new Error(`Row ${row} timestamp is invalid or in the future.`);
  return timestampMs;
}

function validDate(year: number, month: number, day: number) {
  if (year < 1970 || year > 9999 || month < 1 || month > 12 || day < 1 || day > 31) return false;
  const date = new Date(0); date.setUTCFullYear(year, month - 1, day);
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day;
}

function parseCsvLine(line: string) {
  const cells: string[] = []; let value = "", quoted = false, closedQuote = false;
  for (let index = 0; index < line.length; index += 1) {
    const char = line[index];
    if (char === '"') {
      if (quoted && line[index + 1] === '"') { value += '"'; index += 1; }
      else if (quoted) { quoted = false; closedQuote = true; }
      else if (!value && !closedQuote) quoted = true;
      else throw new Error("Tradebook CSV contains an invalid quote.");
    } else if (char === "," && !quoted) { cells.push(value); value = ""; closedQuote = false; }
    else if (closedQuote && !/\s/.test(char)) throw new Error("Tradebook CSV contains characters after a quoted field.");
    else if (!closedQuote) value += char;
  }
  if (quoted) throw new Error("Tradebook CSV contains an unclosed quoted field.");
  cells.push(value); return cells;
}

function roundMoney(value: number) { return Math.round((value + Number.EPSILON) * 100) / 100; }
function csvCell(value: string | number | null) {
  if (value === null) return "";
  if (typeof value === "number") return Number.isFinite(value) ? String(value) : "";
  const safe = /^[\u0009\u000a\u000d\u0020]*[=+\-@]/.test(value) ? `'${value}` : value;
  return `"${safe.replaceAll('"', '""')}"`;
}
