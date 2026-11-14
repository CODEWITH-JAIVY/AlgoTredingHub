export type JournalRecord = {
  id: string;
  time: string;
  symbol: string;
  setup: string;
  result: string;
  note: string;
  emotion?: string;
  tradeTime?: string;
  quantity?: number;
  plannedStop?: string;
  actualStop?: string;
  stopRemoved?: boolean;
  plannedTarget?: string;
  actualTarget?: string;
};

export type JournalInsightPeriod = "7d" | "30d" | "all";

export type JournalInsightGroup = { count: number; pnl: number };
export type JournalPnlByHour = JournalInsightGroup & { hour: number };
export type JournalPnlByWeekday = JournalInsightGroup & { weekday: number };
export type JournalPnlByLabel = JournalInsightGroup & { label: string };
export type JournalPnlByQuantity = JournalInsightGroup & { symbol: string; quantity: number };

export type JournalInsights = {
  entryCount: number;
  manualPnlCount: number;
  manualPnlTotal: number | null;
  pnlByHour: JournalPnlByHour[];
  pnlByWeekday: JournalPnlByWeekday[];
  pnlBySetup: JournalPnlByLabel[];
  pnlByQuantity: JournalPnlByQuantity[];
  omittedSetupGroups: number;
  omittedQuantityGroups: number;
  missingQuantityPnlCount: number;
  reentriesAfterLoss: number;
  sizeIncreasesAfterLoss: number;
  stopsMoved: number;
  stopsRemoved: number;
  overtradingDays: Array<{ date: string; entries: number }>;
  omittedOvertradingDays: number;
  excludedTimestampEntries: number;
};

export type JournalInsightOptions = {
  period: JournalInsightPeriod;
  now: Date | number;
  reentryWindowMinutes?: number;
  overtradingThreshold?: number;
};

const IST_OFFSET_MS = 330 * 60 * 1000;
const MINUTE_MS = 60 * 1000;
const DAY_MS = 24 * 60 * MINUTE_MS;
const MAX_INSIGHT_GROUPS = 8;
const MAX_REENTRY_WINDOW_MINUTES = 240;
const MAX_OVERTRADING_THRESHOLD = 100;

function parseFiniteAmount(value: unknown): number | null {
  if (typeof value !== "string" || value.trim() === "") return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) && Math.abs(parsed) <= 1_000_000_000 ? parsed : null;
}

function parsePositiveAmount(value: unknown): number | null {
  const parsed = parseFiniteAmount(value);
  return parsed !== null && parsed > 0 ? parsed : null;
}

function validQuantity(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0 && value <= 1_000_000_000;
}

export function isJournalRecord(value: unknown): value is JournalRecord {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const entry = value as Partial<JournalRecord>;
  const validOptionalAmount = (amount: unknown, allowNegative: boolean) => {
    if (amount === undefined || amount === "") return true;
    if (typeof amount !== "string" || amount.length > 24) return false;
    const parsed = Number(amount);
    return Number.isFinite(parsed) && Math.abs(parsed) <= 1_000_000_000 && (allowNegative || parsed > 0);
  };
  const validTimestamp = (timestamp: unknown) => typeof timestamp === "string" && Number.isFinite(Date.parse(timestamp));
  return typeof entry.id === "string" && entry.id.length > 0 && entry.id.length <= 80 && validTimestamp(entry.time) &&
    typeof entry.symbol === "string" && entry.symbol.length <= 100 && typeof entry.setup === "string" && entry.setup.length <= 80 &&
    typeof entry.result === "string" && entry.result.length <= 20 &&
    typeof entry.note === "string" && entry.note.length <= 2000 &&
    (entry.emotion === undefined || (typeof entry.emotion === "string" && ["calm", "confident", "anxious", "fomo", "revenge", "bored", "other"].includes(entry.emotion))) &&
    (entry.tradeTime === undefined || (validTimestamp(entry.tradeTime) && entry.tradeTime.length <= 50)) &&
    (entry.quantity === undefined || validQuantity(entry.quantity)) &&
    (entry.stopRemoved === undefined || typeof entry.stopRemoved === "boolean") &&
    (entry.stopRemoved !== true || entry.actualStop === undefined || entry.actualStop === "") &&
    validOptionalAmount(entry.plannedStop, false) && validOptionalAmount(entry.actualStop, false) &&
    validOptionalAmount(entry.plannedTarget, false) && validOptionalAmount(entry.actualTarget, false);
}

function entryTimestamp(entry: JournalRecord): number | null {
  const value = entry.tradeTime ?? entry.time;
  if (typeof value !== "string") return null;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function istParts(timestamp: number): { date: string; hour: number; weekday: number } | null {
  const istDate = new Date(timestamp + IST_OFFSET_MS);
  if (!Number.isFinite(istDate.getTime())) return null;
  return {
    date: istDate.toISOString().slice(0, 10),
    hour: istDate.getUTCHours(),
    weekday: istDate.getUTCDay(),
  };
}

function boundedInteger(value: number | undefined, fallback: number, minimum: number, maximum: number): number {
  if (value === undefined || !Number.isFinite(value)) return fallback;
  return Math.max(minimum, Math.min(maximum, Math.trunc(value)));
}

function toPnl(value: number | null): number | null {
  return value === null ? null : Math.round(value * 100);
}

function addGroup<K, T extends { count: number; pnlCents: number }>(groups: Map<K, T>, key: K, pnlCents: number, create: () => T) {
  const group = groups.get(key) ?? create();
  group.count += 1;
  group.pnlCents += pnlCents;
  groups.set(key, group);
}

function finalizePnl(pnlCents: number): number {
  return pnlCents / 100;
}

function toTopLabelGroups(groups: Map<string, { label: string; count: number; pnlCents: number }>) {
  const rows = [...groups.values()]
    .sort((a, b) => b.count - a.count || a.label.localeCompare(b.label))
    .slice(0, MAX_INSIGHT_GROUPS)
    .map(({ label, count, pnlCents }) => ({ label, count, pnl: finalizePnl(pnlCents) }));
  return { rows, omitted: Math.max(0, groups.size - rows.length) };
}

/** Builds descriptive, self-reported observations; none of these values are risk controls. */
export function buildJournalInsights(
  entries: readonly JournalRecord[],
  options: JournalInsightOptions,
): JournalInsights {
  const nowMs = options.now instanceof Date ? options.now.getTime() : options.now;
  const period = options.period === "all" ? "all" : options.period === "30d" ? "30d" : "7d";
  const startMs = period === "all" ? Number.NEGATIVE_INFINITY : nowMs - (period === "30d" ? 30 : 7) * DAY_MS;
  const reentryWindowMs = boundedInteger(options.reentryWindowMinutes, 30, 1, MAX_REENTRY_WINDOW_MINUTES) * MINUTE_MS;
  const overtradingThreshold = boundedInteger(options.overtradingThreshold, 4, 2, MAX_OVERTRADING_THRESHOLD);
  const timestamped: Array<{ entry: JournalRecord; timestamp: number; pnlCents: number | null }> = [];
  let excludedTimestampEntries = 0;

  for (const entry of Array.isArray(entries) ? entries : []) {
    if (!entry || typeof entry !== "object") {
      excludedTimestampEntries += 1;
      continue;
    }
    const timestamp = entryTimestamp(entry);
    if (timestamp === null || !Number.isFinite(nowMs) || timestamp > nowMs) {
      excludedTimestampEntries += 1;
      continue;
    }
    timestamped.push({ entry, timestamp, pnlCents: toPnl(parseFiniteAmount(entry.result)) });
  }

  timestamped.sort((a, b) => a.timestamp - b.timestamp || String(a.entry.id ?? "").localeCompare(String(b.entry.id ?? "")));
  const inPeriod = timestamped.filter(({ timestamp }) => timestamp >= startMs);
  const pnlRows = inPeriod.filter((row) => row.pnlCents !== null) as Array<{ entry: JournalRecord; timestamp: number; pnlCents: number }>;
  const totalCents = pnlRows.reduce((sum, row) => sum + row.pnlCents, 0);

  const hourGroups = new Map<number, { count: number; pnlCents: number }>();
  const weekdayGroups = new Map<number, { count: number; pnlCents: number }>();
  const setupGroups = new Map<string, { label: string; count: number; pnlCents: number }>();
  const quantityGroups = new Map<string, { symbol: string; quantity: number; count: number; pnlCents: number }>();
  const dayCounts = new Map<string, number>();
  let missingQuantityPnlCount = 0;
  let stopsMoved = 0;
  let stopsRemoved = 0;

  for (const row of inPeriod) {
    const parts = istParts(row.timestamp);
    if (parts) dayCounts.set(parts.date, (dayCounts.get(parts.date) ?? 0) + 1);

    const plannedStop = parsePositiveAmount(row.entry.plannedStop);
    const actualStop = parsePositiveAmount(row.entry.actualStop);
    if (row.entry.stopRemoved === true) stopsRemoved += 1;
    else if (plannedStop !== null && actualStop !== null && Math.abs(plannedStop - actualStop) >= 0.005) stopsMoved += 1;

    if (row.pnlCents === null) continue;
    if (parts) {
      addGroup(hourGroups, parts.hour, row.pnlCents, () => ({ count: 0, pnlCents: 0 }));
      addGroup(weekdayGroups, parts.weekday, row.pnlCents, () => ({ count: 0, pnlCents: 0 }));
    }
    const label = row.entry.setup.trim() || "Unlabeled";
    const normalizedLabel = label.toLocaleLowerCase("en-IN");
    addGroup(setupGroups, normalizedLabel, row.pnlCents, () => ({ label, count: 0, pnlCents: 0 }));
    if (validQuantity(row.entry.quantity)) {
      const symbol = row.entry.symbol.trim() || "—";
      const key = `${symbol.toLocaleLowerCase("en-IN")}\u0000${row.entry.quantity}`;
      addGroup(quantityGroups, key, row.pnlCents, () => ({ symbol, quantity: row.entry.quantity, count: 0, pnlCents: 0 }));
    } else {
      missingQuantityPnlCount += 1;
    }
  }

  let reentriesAfterLoss = 0;
  let sizeIncreasesAfterLoss = 0;
  for (let index = 1; index < timestamped.length; index += 1) {
    const previous = timestamped[index - 1];
    const current = timestamped[index];
    if (current.timestamp < startMs || previous.pnlCents === null || previous.pnlCents >= 0) continue;
    const minutesBetween = (current.timestamp - previous.timestamp) / MINUTE_MS;
    if (minutesBetween >= 0 && minutesBetween <= reentryWindowMs / MINUTE_MS) reentriesAfterLoss += 1;
    if (previous.entry.symbol.trim().toLocaleLowerCase("en-IN") === current.entry.symbol.trim().toLocaleLowerCase("en-IN")
        && validQuantity(previous.entry.quantity) && validQuantity(current.entry.quantity)
        && current.entry.quantity > previous.entry.quantity) {
      sizeIncreasesAfterLoss += 1;
    }
  }

  const allOvertradingDays = [...dayCounts.entries()]
    .filter(([, count]) => count >= overtradingThreshold)
    .sort(([a], [b]) => b.localeCompare(a));
  const overtradingDays = allOvertradingDays.slice(0, 10).map(([date, entriesOnDay]) => ({ date, entries: entriesOnDay }));
  const setupResults = toTopLabelGroups(setupGroups);
  const sizeRows = [...quantityGroups.entries()]
    .sort((a, b) => b[1].count - a[1].count || a[1].symbol.localeCompare(b[1].symbol) || a[1].quantity - b[1].quantity)
    .slice(0, MAX_INSIGHT_GROUPS)
    .map(([, group]) => ({ symbol: group.symbol, quantity: group.quantity, count: group.count, pnl: finalizePnl(group.pnlCents) }));

  return {
    entryCount: inPeriod.length,
    manualPnlCount: pnlRows.length,
    manualPnlTotal: pnlRows.length > 0 ? finalizePnl(totalCents) : null,
    pnlByHour: [...hourGroups.entries()].sort((a, b) => Number(a[0]) - Number(b[0]))
      .map(([hour, group]) => ({ hour: Number(hour), count: group.count, pnl: finalizePnl(group.pnlCents) })),
    pnlByWeekday: [...weekdayGroups.entries()].sort((a, b) => Number(a[0]) - Number(b[0]))
      .map(([weekday, group]) => ({ weekday: Number(weekday), count: group.count, pnl: finalizePnl(group.pnlCents) })),
    pnlBySetup: setupResults.rows,
    pnlByQuantity: sizeRows,
    omittedSetupGroups: setupResults.omitted,
    omittedQuantityGroups: Math.max(0, quantityGroups.size - sizeRows.length),
    missingQuantityPnlCount,
    reentriesAfterLoss,
    sizeIncreasesAfterLoss,
    stopsMoved,
    stopsRemoved,
    overtradingDays,
    omittedOvertradingDays: Math.max(0, allOvertradingDays.length - overtradingDays.length),
    excludedTimestampEntries,
  };
}

export function parseIstDateTimeLocal(value: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) return null;
  const timestamp = Date.parse(`${value}:00+05:30`);
  if (!Number.isFinite(timestamp)) return null;
  const date = new Date(timestamp);
  if (new Date(timestamp + IST_OFFSET_MS).toISOString().slice(0, 16) !== value) return null;
  return date.toISOString();
}
