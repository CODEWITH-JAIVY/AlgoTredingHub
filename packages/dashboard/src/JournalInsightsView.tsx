import React, { useMemo, useState } from "react";
import {
  buildJournalInsights,
  type JournalInsightPeriod,
  type JournalPnlByHour,
  type JournalPnlByLabel,
  type JournalPnlByQuantity,
  type JournalPnlByWeekday,
  type JournalRecord,
} from "./journalInsights";

type Props = { entries: readonly JournalRecord[]; t: Record<string, string> };

const currency = new Intl.NumberFormat("en-IN", { maximumFractionDigits: 2 });

function formatPnl(value: number): string {
  const sign = value > 0 ? "+" : value < 0 ? "−" : "";
  return `${sign}₹${currency.format(Math.abs(value))}`;
}

function formatIstDate(date: string, locale: string): string {
  const value = new Date(`${date}T00:00:00+05:30`);
  if (!Number.isFinite(value.getTime())) return date;
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeZone: "Asia/Kolkata" }).format(value);
}

function Metric({ label, value, tone = "" }: { label: string; value: string; tone?: string }) {
  return <div className="insight-metric"><span>{label}</span><b className={tone}>{value}</b></div>;
}

function PnlTable({
  title, rows, t, omitted = 0, showPnl = true,
}: {
  title: string;
  rows: Array<{ label: string; count: number; pnl: number }>;
  t: Record<string, string>;
  omitted?: number;
  showPnl?: boolean;
}) {
  return <section className="panel insight-table-panel">
    <h3>{title}</h3>
    {rows.length === 0 ? <p className="insight-empty">{t.noReportedPnl}</p> : <div className="insight-table-wrap">
      <table className="insight-table"><thead><tr><th>{t.insightGroup}</th><th>{t.insightEntries}</th>{showPnl && <th>{t.insightManualPnl}</th>}</tr></thead>
        <tbody>{rows.map((row) => <tr key={row.label}><td>{row.label}</td><td>{row.count}</td>{showPnl && <td className={row.pnl < 0 ? "metric-negative" : row.pnl > 0 ? "metric-positive" : ""}>{formatPnl(row.pnl)}</td>}</tr>)}</tbody>
      </table>
    </div>}
    {omitted > 0 && <small className="insight-foot">{t.insightMoreGroups.replace("{count}", String(omitted))}</small>}
  </section>;
}

function hourRows(rows: JournalPnlByHour[]): Array<{ label: string; count: number; pnl: number }> {
  return rows.map((row) => ({ label: `${String(row.hour).padStart(2, "0")}:00`, count: row.count, pnl: row.pnl }));
}

function weekdayRows(rows: JournalPnlByWeekday[], t: Record<string, string>): Array<{ label: string; count: number; pnl: number }> {
  const names = ["weekdaySun", "weekdayMon", "weekdayTue", "weekdayWed", "weekdayThu", "weekdayFri", "weekdaySat"];
  return rows.map((row) => ({ label: t[names[row.weekday]] ?? String(row.weekday), count: row.count, pnl: row.pnl }));
}

function labelRows(rows: JournalPnlByLabel[]): Array<{ label: string; count: number; pnl: number }> {
  return rows.map(({ label, count, pnl }) => ({ label, count, pnl }));
}

function quantityRows(rows: JournalPnlByQuantity[]): Array<{ label: string; count: number; pnl: number }> {
  return rows.map(({ symbol, quantity, count, pnl }) => ({ label: `${symbol} · ${quantity}`, count, pnl }));
}

export function JournalInsightsView({ entries, t }: Props) {
  const [period, setPeriod] = useState<JournalInsightPeriod>("7d");
  const [reentryWindow, setReentryWindow] = useState(30);
  const [overtradingThreshold, setOvertradingThreshold] = useState(4);
  const [asOf, setAsOf] = useState(() => Date.now());
  React.useEffect(() => {
    const timer = window.setInterval(() => setAsOf(Date.now()), 60_000);
    return () => window.clearInterval(timer);
  }, []);
  const insight = useMemo(() => buildJournalInsights(entries, {
    period,
    now: asOf,
    reentryWindowMinutes: reentryWindow,
    overtradingThreshold,
  }), [entries, period, reentryWindow, overtradingThreshold, asOf]);

  const pnlValue = insight.manualPnlTotal === null ? t.noReportedPnlValue : formatPnl(insight.manualPnlTotal);
  const pnlTone = insight.manualPnlTotal === null ? "" : insight.manualPnlTotal < 0 ? "metric-negative" : insight.manualPnlTotal > 0 ? "metric-positive" : "";
  const weekdayPnl = weekdayRows(insight.pnlByWeekday, t);
  const overtradingRows = insight.overtradingDays.map(({ date, entries: count }) => ({
    label: formatIstDate(date, t.dateLocale), count, pnl: 0,
  }));

  return <section className="journal-insights">
    <div className="insight-heading"><div><div className="panel-kicker">{t.insightKicker}</div><h2>{t.insightTitle}</h2><p>{t.insightDescription}</p></div>
      <label className="insight-period">{t.insightPeriod}<select value={period} onChange={(event) => setPeriod(event.target.value as JournalInsightPeriod)}>
        <option value="7d">{t.insightLast7}</option><option value="30d">{t.insightLast30}</option><option value="all">{t.insightAll}</option>
      </select></label>
    </div>
    <div className="insight-options">
      <label>{t.insightReentryWindow}<select value={reentryWindow} onChange={(event) => setReentryWindow(Number(event.target.value))}>
        <option value={15}>15</option><option value={30}>30</option><option value={60}>60</option>
      </select></label>
      <label>{t.insightOvertradingThreshold}<span><input type="number" min={2} max={100} step={1} value={overtradingThreshold}
        onChange={(event) => setOvertradingThreshold(Number(event.target.value))}/>{t.insightEntriesPerDay}</span></label>
    </div>
    <div className="insight-metrics">
      <Metric label={t.insightEntries} value={String(insight.entryCount)}/>
      <Metric label={t.insightManualPnl} value={pnlValue} tone={pnlTone}/>
      <Metric label={t.insightReentries} value={String(insight.reentriesAfterLoss)}/>
      <Metric label={t.insightSizeIncreases} value={String(insight.sizeIncreasesAfterLoss)}/>
      <Metric label={t.insightStopsMoved} value={String(insight.stopsMoved)}/>
      <Metric label={t.insightStopsRemoved} value={String(insight.stopsRemoved)}/>
      <Metric label={t.insightOvertradingDays} value={String(insight.overtradingDays.length + insight.omittedOvertradingDays)}/>
    </div>
    <p className="insight-disclaimer">{t.insightDisclaimer}</p>
    <div className="insight-tables">
      <PnlTable title={t.insightByHour} rows={hourRows(insight.pnlByHour)} t={t}/>
      <PnlTable title={t.insightByWeekday} rows={weekdayPnl} t={t}/>
      <PnlTable title={t.insightBySetup} rows={labelRows(insight.pnlBySetup)} t={t} omitted={insight.omittedSetupGroups}/>
      <PnlTable title={t.insightByQuantity} rows={quantityRows(insight.pnlByQuantity)} t={t} omitted={insight.omittedQuantityGroups}/>
      <PnlTable title={t.insightOvertradingList} rows={overtradingRows} t={t} omitted={insight.omittedOvertradingDays} showPnl={false}/>
    </div>
    {insight.missingQuantityPnlCount > 0 && <p className="insight-foot">{t.insightMissingQuantity.replace("{count}", String(insight.missingQuantityPnlCount))}</p>}
    {insight.excludedTimestampEntries > 0 && <p className="insight-foot">{t.insightExcludedDates.replace("{count}", String(insight.excludedTimestampEntries))}</p>}
  </section>;
}
