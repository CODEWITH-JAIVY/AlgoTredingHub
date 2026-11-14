"use client";

import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ApiClient, ApiError, type AlertEvent, type AlertRule, type AlertRuleType, type AnalysisSummary, type Candle, type CandleSeries, type DemoStatus, type Instrument,
  type KillSwitch, type Order, type OrderRequest, type Quote, type RiskPolicy, type TokenResponse,
} from "@algotrade/contracts";
import { parseBacktestCsv, runBacktest, runWalkForwardBacktest, type BacktestBar, type BacktestResult, type WalkForwardResult } from "./backtest";
import { calculatePaperReplayAccounting, runPaperReplay, type PaperReplayAccounting, type PaperReplayResult, type ReplayOrder, type ReplayOrderStatus, type ReplayOrderType, type ReplaySide } from "./paperReplay";
import { formatIndicator } from "./analysisDisplay";
import { getScannerStatusMessage } from "./scannerStatus";
import { clearPendingOrder, isAmbiguousOrderResponseStatus, pendingOrderStorageKey, readPendingOrder, reconcilePendingOrder, savePendingOrder, type PendingOrderState } from "./pendingOrder";
import { RequestEpoch } from "./requestEpoch";
import { buildTradebookExportCsv, calculateFifoPnl, parseTradebookCsv, type TradeSegment, type TradebookReport } from "./tradebook";
import { formatDateTimeIst } from "./dateFormatting";
import { isJournalRecord, parseIstDateTimeLocal, type JournalRecord } from "./journalInsights";
import { JournalInsightsView } from "./JournalInsightsView";

type Props = { apiBase: string; storageKey: string; compact?: boolean };
type Session = Pick<TokenResponse, "accessToken" | "refreshToken" | "userId">;
type Tab = "overview" | "scanner" | "backtest" | "reports" | "journal" | "risk";
type Entry = JournalRecord;
type JournalDraft = {
  tradeTime: string; quantity: string; plannedStop: string; actualStop: string; stopRemoved: boolean;
  plannedTarget: string; actualTarget: string;
};
type Draft = { side: "BUY" | "SELL"; quantity: string; stop: string };
type BacktestSettings = { startingCapital: string; riskPercent: string; feeBpsPerSide: string; slippageTicks: string };
type PretradeChecks = { plan: boolean; stop: boolean; risk: boolean; simulatedData: boolean };

function readPendingOrderFromBrowser(key: string): PendingOrderState {
  try { return readPendingOrder(window.localStorage, key); }
  catch { return { kind: "blocked", reason: "unavailable" }; }
}

function savePendingOrderToBrowser(key: string, request: OrderRequest): boolean {
  try { return savePendingOrder(window.localStorage, key, request); }
  catch { return false; }
}

function clearPendingOrderFromBrowser(key: string): boolean {
  try { return clearPendingOrder(window.localStorage, key); }
  catch { return false; }
}

function isAmbiguousOrderFailure(cause: unknown): boolean {
  return !(cause instanceof ApiError) || isAmbiguousOrderResponseStatus(cause.status);
}

function riskErrorMessage(cause: unknown, t: Record<string, string>): string {
  if (cause instanceof ApiError) {
    const translationKeys: Record<string, string> = {
      OUTSIDE_TRADING_SESSION: "orderOutsideSession",
      OPENING_COOLDOWN: "orderOpeningCooldown",
      ORDER_ENTRY_CUTOFF: "orderCutoffPassed",
      MAX_DAILY_ORDER_ENTRIES: "dailyOrderLimitReached",
      INVALID_TRADING_WINDOW: "invalidTradingWindow",
      KILL_SWITCH_UNRESOLVED_ORDERS: "killSwitchResumeBlocked",
    };
    const key = translationKeys[cause.code];
    if (key) return t[key];
  }
  return cause instanceof Error ? cause.message : t.errorGeneric;
}

const accountFormatter = new Intl.NumberFormat("en-IN", { maximumFractionDigits: 2 });
const formatMoney = (value: number) => `₹${accountFormatter.format(value)}`;
function translateOrderStatus(status: string, t: Record<string, string>) {
  const key: Record<string, string> = {
    OPEN: "orderOpen", PARTIALLY_FILLED: "orderPartiallyFilled", FILLED: "orderFilled",
    CANCELLED: "orderCancelled", REJECTED: "orderRejected", EXPIRED: "orderExpired", UNKNOWN: "orderUnknown",
  };
  return t[key[status] ?? "orderUnknown"] ?? status;
}
const number = (value?: string | number | null) => {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
};
const initialPolicy: RiskPolicy = {
  maxQuantity: 100, maxOrderNotional: "100000.00", maxOpenOrders: 5,
  maxDailyLoss: "5000.00", maxTradeLoss: "1000.00", maxOrdersPerDay: 10,
  noNewOrdersAfter: "15:00", noOrdersFirstMinutes: 5, acceptedOrderEntriesToday: 0, pending: null, updatedAt: "",
};

function riskPolicyDraft(policy: RiskPolicy) {
  return {
    maxQuantity: String(policy.pending?.maxQuantity ?? policy.maxQuantity),
    maxOrderNotional: String(policy.pending?.maxOrderNotional ?? policy.maxOrderNotional),
    maxOpenOrders: String(policy.pending?.maxOpenOrders ?? policy.maxOpenOrders),
    maxDailyLoss: String(policy.pending?.maxDailyLoss ?? policy.maxDailyLoss),
    maxTradeLoss: String(policy.pending?.maxTradeLoss ?? policy.maxTradeLoss),
    maxOrdersPerDay: String(policy.pending?.maxOrdersPerDay ?? policy.maxOrdersPerDay),
    noNewOrdersAfter: String(policy.pending?.noNewOrdersAfter ?? policy.noNewOrdersAfter).slice(0, 5),
    noOrdersFirstMinutes: String(policy.pending?.noOrdersFirstMinutes ?? policy.noOrdersFirstMinutes),
  };
}

const en: Record<string, string> = {
  overview: "Overview", scanner: "Scanner", backtest: "Backtest", reports: "Reference P&L", journal: "Trade journal", risk: "Risk & discipline", watchlist: "Watchlist",
  search: "Search instruments", marketOpen: "MARKET DATA", dashboard: "Trading workspace", demo: "DEMO MODE",
  simulated: "SIMULATED", feedUnavailable: "Licensed market feed not connected", noSignal: "Analysis withheld",
  noSignalBody: "The synthetic sample is analyzed for data-quality checks. Bias, zones and trade scenarios stay withheld.",
  setup: "Setup health", fixture: "Historical development fixture · 03 Jan 2000", chart: "Price action",
  loading: "Connecting to local services…", retry: "Retry connection", signIn: "Sign in to your workspace",
  createAccount: "Create your private account", email: "Email address", password: "Password",
  signInButton: "Sign in", createButton: "Create account", switchSignup: "New here? Create an account",
  switchLogin: "Already have an account? Sign in", localOnly: "Single-user workspace · your account is private",
  notAdvisory: "Development sample only. Not live data, investment advice or a trading signal.",
  authEyebrow: "RULE-BASED · MOCK-FIRST", authTitleOne: "Trade with a plan.", authTitleTwo: "Learn with every trade.",
  authDescription: "A private workspace for disciplined practice, risk controls and an honest trading journal.",
  authPrivateAccount: "Private account", authRiskFirst: "Risk before order", authPracticeMode: "Safe practice mode",
  authFooter: "DESIGNED FOR PROCESS, NOT PREDICTIONS", privateWorkspace: "PRIVATE WORKSPACE",
  welcomeBack: "WELCOME BACK", getStarted: "GET STARTED", passwordHint: "At least 12 characters",
  workspace: "WORKSPACE", workspaceViews: "Workspace views", india: "INDIA", dashboardSummary: "One instrument at a time. Risk limits checked before every mock order.",
  languageLabel: "Language", dateLocale: "en-IN",
  paperMock: "PAPER / MOCK", sessionReference: "Exchange session reference", noLiveData: "NO LIVE DATA", mockOrdersTag: "MOCK ORDERS",
  syntheticDemo: "Synthetic demo only. No licensed provider is configured.", chartTimeframeReason: "Only finalized 1-minute candles are currently available",
  chartUp: "UP", chartDown: "DOWN", readOnly: "READ ONLY", bars: "BARS", utcToIst: "UTC → IST",
  ruleBasedContext: "RULE-BASED CONTEXT", withheld: "WITHHELD", indicatorSummary: "EMA · ATR · RSI · zones · scenarios",
  waitingLicensedData: "Waiting for finalized candles", analysisLoading: "Checking finalized sample candles…",
  analysisUnavailable: "Analysis service is unavailable. Retry when it is running.", analysisRetry: "Retry analysis",
  analysisBars: "FINALIZED BARS", analysisFreshness: "DATA FRESHNESS", analysisSignal: "SIGNAL STATUS",
  analysisTime: "LAST SAMPLE BAR", analysisWithheld: "Directional output withheld for synthetic data",
  analysisIndicatorTitle: "INDICATOR VALUES · 5M", analysisIndicatorWarmup: "— means finalized history is too short for that calculation.",
  bias: "BIAS", zone: "ZONE", confidence: "CONFIDENCE",
  riskFirst: "RISK FIRST", calculator: "CALCULATOR", units: "units", lot: "lot", mockOnly: "MOCK ONLY",
  suggestedQuantity: "Suggested quantity", estimatedRiskShort: "Est. risk", total: "TOTAL", paperTrading: "PAPER TRADING",
  mockFillsSimulated: "Mock API orders are status-only; they do not create fills or positions. Use Backtest → Paper Replay for modeled fills.", localOnlyShort: "LOCAL ONLY", dismiss: "Dismiss", removeAlert: "Remove alert",
  simulatedLocalOnly: "SIMULATED · LOCAL ONLY", close: "Close", marketMock: "MARKET · MOCK", marketReference: "at a simulated market reference of",
  account: "Account", orderTypeMode: "Order type · mode", orderOpen: "Open", orderPartiallyFilled: "Partially filled",
  orderFilled: "Filled", orderCancelled: "Cancelled", orderRejected: "Rejected", orderExpired: "Expired", orderUnknown: "Unknown",
  lotSize: "lot size", tickSize: "tick size", myWorkspace: "My workspace", mainNavigation: "Main navigation",
  available: "Available for mock testing", paperOrder: "Mock order ticket", buy: "Buy", sell: "Sell",
  quantity: "Quantity", protectiveStop: "Protective stop", approxRisk: "Estimated stop risk", notional: "Estimated notional",
  advancedView: "Advanced view", simpleView: "Simple view", advancedDetailsTitle: "Order details and active limits",
  referencePrice: "Simulated reference price", advancedDetailsNote: "This is a synthetic reference. The backend checks the latest limits again before submission.",
  submit: "Review mock order", cancel: "Cancel", confirm: "Confirm mock order", orderWarning: "This sends a simulated order to the local mock broker only.",
  orders: "Recent mock orders", noOrders: "No mock orders yet", status: "Status", side: "Side", instrument: "Instrument",
  recentActivity: "Recent activity", noJournal: "No journal entries yet", saveEntry: "Save journal entry", note: "What did you learn?",
  setupTag: "Setup / emotion", resultRupees: "Manual P&L (₹)", export: "Export my journal", delete: "Delete all entries",
  localJournal: "Stored in this browser only. It is not sent to the server.", alerts: "Price alerts", addAlert: "Create server rule",
  serverManagedAlerts: "SERVER MANAGED · MOCK DATA CANNOT TRIGGER ALERTS",
  alertRuleType: "Alert condition", priceCrossing: "Price crossing", zoneTouch: "Price enters zone", volumeSpike: "Volume spike",
  volumeMultiplier: "Volume multiple vs average", volumeAlert: "Current / average volume", volumeHistory: "Waiting · 20 consecutive regular candles needed",
  zeroVolumeBaseline: "Waiting · average volume is zero", alertOutsideSession: "Waiting · regular session candle required",
  alertPrice: "Trigger price", alertDirection: "Crossing direction", alertLower: "Zone lower price", alertUpper: "Zone upper price", above: "Above", below: "Below",
  alertPaused: "Paused · licensed source rights are not confirmed", alertWaiting: "Waiting · fresh approved one-minute data",
  alertArmed: "Armed · evaluated on finalized one-minute candles", alertTriggered: "Triggered",
  alertStale: "Paused · latest market data is stale", alertSynthetic: "Paused · synthetic or unapproved data ignored",
  alertIncomplete: "Waiting · incomplete candle ignored", alertFuture: "Paused · future-dated candle rejected",
  alertEvents: "Recent in-app alerts", noAlertEvents: "No alert events", noAlerts: "No alerts saved", feedHealth: "Feed health",
  riskLimits: "Account risk limits", maxOrder: "Max order notional", dailyLoss: "Daily loss limit", tradeLoss: "Per-trade loss limit",
  maxQty: "Maximum quantity", killSwitch: "Kill switch", activateKill: "Activate kill switch", deactivateKill: "Resume mock orders",
  killOn: "ACTIVE · mock submissions blocked", killOff: "INACTIVE · mock broker only", saveLimits: "Save limits",
  suggestedSize: "Position-size estimate", capital: "Illustrative capital", riskPercent: "Risk per trade %", entry: "Entry reference",
  size: "Suggested quantity", sizeRisk: "Risk at suggested quantity", sizeDisclaimer: "Calculator only. Confirm lot size, tick size and limits before a mock order.",
  connected: "Backend connected", disconnected: "Backend unavailable", refresh: "Refresh", logout: "Sign out",
  feedConnected: "Connected", noInstrumentsFound: "No instruments found",
  errorGeneric: "Something went wrong. Please try again.", orderPlaced: "Mock order submitted", cancelOrder: "Cancel order",
  orderOutsideSession: "New orders are available during the weekday demo session only (09:15–15:30 IST).",
  orderOpeningCooldown: "New orders are blocked during the opening cooldown.", orderCutoffPassed: "The configured cutoff for new orders has passed.",
  dailyOrderLimitReached: "The maximum accepted mock order entries for today has been reached.", invalidTradingWindow: "Choose a cutoff after the opening delay and no later than 15:30 IST.",
  orderStatusUnknown: "Order status is unknown. Check status or retry this exact request before creating another order.",
  checkStatus: "Check status", retrySameOrder: "Retry same request", pendingStorageUnavailable: "Local recovery storage is unavailable. No new order was sent; check browser storage and recent orders.",
  resolvePendingOrder: "Resolve the earlier order request before creating another order.", invalidPendingOrder: "The saved recovery record is unreadable. Check recent orders before unlocking new orders.",
  acknowledgeOrdersReviewed: "I checked recent orders",
  noChart: "No candles available for this demo range", selected: "Selected instrument", newAlert: "Server rule saved. It stays paused until licensed fresh data is authorized.",
  confirmKill: "Activate kill switch? Open mock orders will be sent for cancellation.", confirmResume: "Resume mock order submissions?", confirmRetryKill: "Retry cancellation for unresolved mock orders?",
  rightsAcknowledgement: "I have the right to use this historical data. The CSV stays in this browser tab and is not uploaded.",
  chooseCsv: "Choose historical CSV", runBacktest: "Run backtest", clearBacktest: "Clear imported data", exportBacktest: "Export results",
  noBacktest: "Import at least 100 valid one-minute bars to run this example strategy.",
  pretradeTitle: "Pre-trade discipline check", checkPlan: "My setup and invalidation are written down.",
  checkStop: "I checked the protective stop and its direction.", checkRisk: "I checked quantity, notional and rupee risk.",
  checkSimulated: "I understand this is synthetic data and a mock order.", checklistRequired: "Complete all four checks before review.",
  pretradeUnavailable: "Not evaluated here: live zone freshness, trend agreement, risk:reward, event risk, loss cooldown and daily-loss lock. No licensed feed or authoritative fill/P&L source is connected. The backend rechecks available mock limits and time gates before submission.",
  emotion: "State of mind (self-reported)", calm: "Calm", confident: "Confident", anxious: "Anxious", fomo: "FOMO",
  revenge: "Revenge trading", bored: "Bored", other: "Other / unsure",
  tradebookRights: "I have the right to use this executed-trade file. It stays in this browser tab and is not uploaded.",
  chooseTradebook: "Choose normalized tradebook CSV", reportReference: "Reference only · not tax advice", exportPnlCsv: "Export P&L CSV",
  scanEyebrow: "UNIVERSE SCAN · DATA QUALITY GATED", scannerStatus: "SCANNER STATUS",
  scanDescription: "Scan results stay unavailable until fresh, rights-cleared multi-instrument data and a shared analysis route exist.",
  scanNoProvider: "No licensed market-data provider is configured.", scanStatusUnavailable: "Market-data status is unavailable. Refresh to try again.", scannerDisclaimer: "Scanning requires an approved provider, fresh data, and the shared analysis service. No scan runs until those checks pass.",
  scannerUnavailable: "Unavailable · fail-closed", freshMultiTimeframe: "Fresh multi-timeframe feed", unavailable: "Unavailable",
  instrumentRights: "Instrument universe rights", notVerified: "Not verified", backendScanRoute: "Backend scan route", notConnected: "Not connected",
  mockExcluded: "Mock candles are deliberately excluded. No scan is running in the background.", plannedNotEvaluated: "PLANNED CONDITIONS · NOT EVALUATED",
  analysisFilters: "Analysis-consistent filters", trendAlignment: "Trend alignment", trendEvidence: "EMA 20 / 50 / 200 and ADX, with timeframe evidence",
  freshZone: "Fresh supply / demand zone", zoneEvidence: "Zone age, touches, invalidation and source timeframe",
  relativeVolumeQuality: "Relative volume and quality", volumeEvidence: "Freshness, gaps, completeness and source-right flags", notEvaluated: "Not evaluated",
  historicalLocalOnly: "HISTORICAL SIMULATION · LOCAL ONLY", backtestDescription: "Use your own licensed 1-minute OHLCV CSV to explore one transparent example strategy.",
  inputAssumptions: "INPUT & ASSUMPTIONS", selectInstrument: "Select an instrument", csvColumns: "CSV columns",
  tickSizeLabel: "Tick size", lotSizeLabel: "Lot size", barsLoaded: "Bars loaded", startingCapital: "Starting capital (₹)",
  estimatedFee: "Estimated fee (bps / side)", slippage: "Slippage (ticks / side)", walkForward: "3-window walk-forward",
  educationalSimulation: "This is an educational simulation, not a trading recommendation. Your CSV is not saved or uploaded.",
  backtestDataHandling: "The app never requests market history for this test. The selected instrument only supplies tick and lot size. Imported CSV data stays in memory and clears when you switch instruments, reload or close this tab, or remove it here.",
  csvLimits: "100–50,000 rows · 5 MB max · complete 1-minute regular-session bars · ISO timestamp with zone or IST local time",
  barsUnit: "bars", tradesUnit: "trades", walkForwardWindowStats: "Each window uses only earlier bars for indicator warm-up, starts with a fresh configured capital balance, and keeps strategy parameters fixed.",
  walkForwardCaveat: "These separate replays are not one continuous portfolio and do not remove survivorship bias, market-data errors, or future uncertainty.",
  paperReplayTitle: "MANUAL PAPER EXECUTION REPLAY · LOCAL ONLY", paperReplayDescription: "Stage hypothetical orders against the imported bars. Orders never reach the backend or a broker.",
  paperReplayDisclosure: "Historical OHLC cannot show queue position or the order of events inside a candle. Same-bar fills are prohibited; market and stop fills include adverse spread/slippage estimates. DAY orders expire at the next represented session, GTC orders may cross dates, and remaining quantity expires at file end. Fees are estimates; taxes, market impact, actual liquidity, holidays and broker rules are excluded. This is not portfolio P&L or a trading recommendation.",
  replaySide: "Side", replayType: "Order type", replayQuantity: "Quantity (whole lots)", replaySubmitBar: "Signal bar number", replayLimitPrice: "Limit price (₹)", replayStopPrice: "Stop price (₹)",
  replayMarket: "Market", replayLimit: "Limit", replayStopMarket: "Stop-market", replayMarketOpen: "Market at bar open", replayLimitTouch: "Limit touched", replayStopTrigger: "Stop triggered",
  replayCancelReason: "Cancel became effective before this bar opened.", replayWaitReason: "Maximum eligible-bar wait was reached.", replayDayReason: "DAY order expired at the next session boundary.", replayEndReason: "Replay ended before the remaining quantity could fill.", replayPriceReason: "Execution price was invalid or would violate the limit price.", replaySideReason: "Unsupported order side.", replayTypeReason: "Unsupported order type.", replayQuantityReason: "Quantity must be a positive whole number of lots within the simulation limit.", replaySubmissionReason: "Submission bar is outside the replay history.", replayTifReason: "Unsupported time in force.", replayCancelBarReason: "Cancel request bar must be within the history and no earlier than submission.", replayLimitReason: "Limit order needs a positive tick-aligned limit price.", replayStopReason: "Stop order needs a positive tick-aligned stop price.",
  replayNotionalReason: "Fill notional exceeds the ₹1,000,000,000 simulation accounting limit.", replayFeeReason: "Estimated fill fees exceed the ₹1,000,000 simulation accounting limit.", replayFillCap: "This replay exceeds the 50,000-fill accounting limit. Reduce the history or order count.",
  paperPnlTitle: "SIMULATED P&L · FIFO", paperPnlDisclosure: "Realized P&L is FIFO from simulated fills. Open positions are marked at the final imported close; open net P&L subtracts entry fees but excludes estimated exit costs. No taxes, statutory charges, margin, or broker reconciliation.",
  paperRealizedGross: "Realized gross P&L", paperRealizedNet: "Realized net P&L", paperRealizedCharges: "Realized fees", paperOpenNet: "Open net P&L*", paperCombinedNet: "Realized + open net*", paperOpenQuantity: "Open quantity", paperMark: "Final bar mark", paperOpenPositions: "OPEN POSITIONS", paperDirection: "Direction", paperAverageEntry: "Avg. entry", paperMarkPrice: "Mark price", paperUnrealizedGross: "Gross unrealized", paperEntryFees: "Entry fees allocated", paperUnrealizedNet: "Net unrealized*", paperRealizedMatches: "FIFO REALIZED MATCHES", paperOpenLong: "Long", paperOpenShort: "Short", paperNoOpenPosition: "No open position remains.", paperNoRealizedMatches: "No position was closed in this replay.",
  replayTimeInForce: "Time in force", replayDay: "DAY · expire at next session", replayGtc: "GTC · can cross sessions", replayCancelBar: "Cancel after bar (optional)",
  replayLatency: "Extra latency (bars)", replayParticipation: "Max volume participation (%)", replaySpread: "Spread estimate (ticks / side)", replayMaxWait: "Max eligible bars to wait", replayAddOrder: "Stage order", replayRun: "Run paper replay", replayClear: "Clear replay", replayExport: "Export replay JSON",
  replayNoBars: "Import local historical bars first. The CSV remains in this tab only.", replayNoOrders: "No orders staged. Choose a signal bar and add a hypothetical order.", replayOrdersTitle: "STAGED ORDERS", replayResultsTitle: "REPLAY RESULTS", replayFillsTitle: "SIMULATED FILLS", replayOrderId: "Order ID", replayEligibleBar: "Eligible from bar", replayStatus: "Status", replayFilledQty: "Filled", replayRemainingQty: "Remaining", replayAveragePrice: "Average fill", replayFees: "Est. fees", replayTime: "Time (IST)", replayFillPrice: "Fill price", replayReason: "Reason", replayNoFills: "No order filled in this replay. Orders may have expired, been cancelled, or lacked eligible volume/price touches.", replayShowingFills: "Most recent 100 fills", replayStatusPending: "Pending", replayStatusPartial: "Partial", replayStatusFilled: "Filled", replayStatusCancelled: "Cancelled", replayStatusExpired: "Expired", replayStatusRejected: "Rejected", replayRemove: "Remove staged order", replayInvalid: "Check the order fields and replay assumptions.", replayTooMany: "Stage no more than 50 orders per replay.", replayWorkCap: "This order/history combination is too large for the browser replay. Reduce the number of bars or orders.",
  strategyLongOnly: "EMA 20 / 50 CROSSOVER · 2× ATR STOP · LONG ONLY", simulationResults: "Simulation results", readyWhenYouAre: "Ready when you are",
  nextBarEntry: "Entry uses the next bar open. Position size uses risk budget, lot size and available capital.",
  netPnl: "Net P&L", returnLabel: "Return", endingCapital: "Ending capital", maxDrawdown: "Max drawdown", tradesWinRate: "Trades · win rate",
  estimatedFees: "Estimated fees", expectancyPerTrade: "Expectancy / trade", profitFactor: "Profit factor*", noLosingTrades: "∞ · no losing trades",
  maxExposure: "Max exposure", dailySharpe: "Daily Sharpe*", realizedEquityCurve: "Realized equity curve", recentSimulatedTrades: "Recent simulated trades",
  showingUpTo50: "Showing up to 50", entryLabel: "Entry", exitLabel: "Exit", quantityLabel: "Qty", exitReason: "Exit reason",
  noQualifyingEntry: "No qualifying entry occurred in this sample. A zero-trade result is valid.", windowLabel: "Window", simulatedEquityCurve: "Simulated realized equity curve",
  simulatedCandlestickChart: "Simulated historical candlestick chart", executedFillsLocal: "EXECUTED FILLS · LOCAL ONLY",
  reportDescription: "FIFO realized P&L from a normalized tradebook file you select.", printSavePdf: "Print / Save PDF",
  privateFileImport: "PRIVATE FILE IMPORT", tradebookSettings: "Tradebook settings", requiredNormalizedColumns: "Required normalized columns",
  reportImportDescription: "Use one account's executed fills. You provide each row's charges; this tool does not connect to a broker, reconcile account activity, or calculate tax due.",
  tradebookSegments: "Segments: CASH_INTRADAY, CASH_DELIVERY, FNO · Side: BUY or SELL · Unique fill ID · 50,000 rows and 5 MB max",
  reportDataHandling: "CSV is processed in browser memory only. It clears on account change, reload, tab close, or manual clear.",
  fifoMethod: "Match logic: FIFO by full segment and symbol key; timestamp, then trade ID for same-time rows.",
  realizedPnlSummary: "Realized P&L summary", segmentInstrument: "Segment · instrument",
  clearReport: "Clear report", fifoBreakdown: "FIFO MATCHING · SEGMENT BREAKDOWN", chooseTradebookMessage: "Select a tradebook CSV to calculate reference realized P&L. Open inventory is shown separately and is never marked to market.",
  realizedNetPnl: "Realized net P&L", realizedGrossPnl: "Realized gross P&L", chargesMatchedQuantity: "Charges in matched quantity",
  fillRowsMatches: "Fill rows · FIFO matches", openInventoryQuantity: "Open inventory quantity", openInventoryCharges: "Charges still in open inventory",
  bySegment: "By segment", segmentLabel: "Segment", matchedQuantity: "Matched qty", matchesLabel: "Matches", grossPnl: "Gross P&L", chargesLabel: "Charges",
  unmatchedInventory: "Unmatched inventory · no mark-to-market", positionsLabel: "positions", directionLabel: "Direction", openQuantity: "Open qty",
  averageEntry: "Avg entry", unallocatedEntryCharges: "Unallocated entry charges", recentFifoMatches: "Recent FIFO matches", instrumentLabel: "Instrument",
  entryToExit: "Entry → exit", noTradebookLoaded: "No tradebook loaded", processPrivate: "PROCESS · PRIVATE BY DEFAULT",
  journalDescription: "Record the decision and the lesson. Results are manual entries, not broker P&L.", postTradeReview: "POST-TRADE REVIEW",
  setupPlaceholder: "e.g. breakout · patient", optionalManualOnly: "Optional, manual only", journalNotePlaceholder: "Plan, execution, emotion, next improvement…",
  entriesLabel: "entries", manualPnl: "Manual P&L", journalPrivacy: "Journal is saved in this browser under this account ID. Export a JSON backup before clearing browser storage.",
  journalTradeTime: "Trade date and time (IST)", journalTradeTimeHint: "Optional. Leave blank to use the time you save this entry.",
  journalQuantity: "Position quantity (optional)", plannedStop: "Planned stop (₹, optional)", actualStop: "Actual stop (₹, optional)", stopRemoved: "Protective stop removed",
  plannedTarget: "Planned target (₹, optional)", actualTarget: "Actual target (₹, optional)", journalValuesInvalid: "Check the IST date/time, quantity, P&L, stop and target values.",
  insightKicker: "OBSERVATIONS · SELF-REPORTED", insightTitle: "Behavior review", insightDescription: "Review patterns in the trades you recorded. These observations do not change order controls.",
  insightPeriod: "Period", insightLast7: "Last 7 days", insightLast30: "Last 30 days", insightAll: "All entries",
  insightReentryWindow: "Re-entry window after a reported loss (minutes)", insightOvertradingThreshold: "Flag days with at least", insightEntriesPerDay: "journal entries",
  insightEntries: "Journal entries", insightManualPnl: "Reported P&L", insightReentries: "Entries within the selected window after a reported loss",
  insightSizeIncreases: "Same-symbol size increases after a reported loss", insightStopsMoved: "Reported stops moved", insightStopsRemoved: "Reported stops removed",
  insightOvertradingDays: "Days above your entry threshold", insightDisclaimer: "Based only on manual journal entries, not broker fills. This is not broker P&L, a diagnosis, investment advice, or an order block.",
  insightByHour: "Reported P&L by entry hour (IST)", insightByWeekday: "Reported P&L by weekday (IST)", insightBySetup: "Reported P&L by setup",
  insightByQuantity: "Reported P&L by symbol and exact quantity", insightOvertradingList: "Days meeting your entry threshold",
  insightGroup: "Group", insightMoreGroups: "{count} more groups are not shown.", noReportedPnl: "No manual P&L was entered for this period.", noReportedPnlValue: "Not reported",
  insightMissingQuantity: "{count} P&L entries had no recorded quantity, so they are omitted from the quantity breakdown.",
  insightExcludedDates: "{count} entries with invalid or future timestamps were excluded.",
  weekdaySun: "Sunday", weekdayMon: "Monday", weekdayTue: "Tuesday", weekdayWed: "Wednesday", weekdayThu: "Thursday", weekdayFri: "Friday", weekdaySat: "Saturday",
  guardrailsAccount: "GUARDRAILS · ACCOUNT LEVEL", riskDescription: "These controls are enforced by the backend before it accepts mock orders.",
  mockAccount: "MOCK ACCOUNT", backendPolicy: "BACKEND POLICY", persisted: "Persisted", maxOpenOrders: "Maximum open orders",
  maxOrdersPerDay: "Maximum accepted order entries per day", cutoffTime: "Stop accepting new orders at (IST)", openingCooldown: "No new orders for first (minutes)",
  dailyOrderEntryNote: "The daily count records accepted mock order submissions, including orders later cancelled. Until fills are modeled, this is a conservative order-entry cap rather than a completed-trade count. New orders are blocked outside weekdays 09:15–15:30 IST; the sample calendar does not include exchange holidays.",
  dailyOrderCount: "Accepted entries today",
  noEntryWindowWarning: "The active opening delay and cutoff overlap, so the backend blocks every new order until the active window is valid again.",
  policyUpdated: "Current saved policy updated", withYourAccount: "with your account", emergencyControl: "EMERGENCY CONTROL",
  pendingPolicy: "Relaxed values scheduled for", pendingPolicyBody: "Current limits remain enforced by the backend until then.", currentActiveLimits: "Active now:",
  killSwitchDescription: "Blocks new mock submissions and requests cancellation of open mock orders. It does not connect to a broker.",
  confirmedCancelled: "Confirmed cancelled", unresolvedOpen: "Unresolved open", killSwitchResumeBlocked: "Resolve or cancel all open orders before resuming mock submissions.", retryKillCancellation: "Retry unresolved cancellations", mockControlDisclaimer: "For the local mock environment only. This is not a real-money trading control.",
};
const hi: Record<string, string> = {
  ...en, overview: "अवलोकन", scanner: "स्कैनर", backtest: "बैकटेस्ट", reports: "P&L रिपोर्ट", journal: "ट्रेड जर्नल", risk: "जोखिम और अनुशासन", watchlist: "वॉचलिस्ट",
  search: "इंस्ट्रूमेंट खोजें", dashboard: "ट्रेडिंग वर्कस्पेस", demo: "डेमो मोड", simulated: "सिम्युलेटेड",
  feedUnavailable: "लाइसेंस वाला मार्केट फीड जुड़ा नहीं है", noSignal: "विश्लेषण रोका गया",
  noSignalBody: "डेटा गुणवत्ता जाँच के लिए सिंथेटिक सैंपल का विश्लेषण होता है। दिशा, ज़ोन और ट्रेड परिदृश्य रोके गए हैं।",
  fixture: "पुराना डेवलपमेंट सैंपल · 03 जनवरी 2000", chart: "मूल्य गतिविधि", loading: "लोकल सर्विस से जुड़ रहे हैं…",
  retry: "कनेक्शन फिर आज़माएँ", signIn: "अपने वर्कस्पेस में साइन इन करें", createAccount: "निजी खाता बनाएँ",
  email: "ईमेल पता", password: "पासवर्ड", signInButton: "साइन इन", createButton: "खाता बनाएँ",
  switchSignup: "नए हैं? खाता बनाएँ", switchLogin: "पहले से खाता है? साइन इन करें",
  localOnly: "एकल-उपयोगकर्ता वर्कस्पेस · आपका खाता निजी है", notAdvisory: "केवल डेवलपमेंट सैंपल। लाइव डेटा, निवेश सलाह या ट्रेडिंग सिग्नल नहीं।",
  authEyebrow: "नियम-आधारित · मॉक-फर्स्ट", authTitleOne: "योजना के साथ ट्रेड करें।", authTitleTwo: "हर ट्रेड से सीखें।",
  authDescription: "अनुशासित अभ्यास, जोखिम नियंत्रण और ईमानदार ट्रेड जर्नल के लिए निजी वर्कस्पेस।",
  authPrivateAccount: "निजी खाता", authRiskFirst: "ऑर्डर से पहले जोखिम", authPracticeMode: "सुरक्षित अभ्यास मोड",
  authFooter: "प्रक्रिया पर केंद्रित, भविष्यवाणी पर नहीं", privateWorkspace: "निजी वर्कस्पेस",
  welcomeBack: "वापस स्वागत है", getStarted: "शुरू करें", passwordHint: "कम से कम 12 अक्षर",
  workspace: "वर्कस्पेस", workspaceViews: "वर्कस्पेस दृश्य", india: "भारत", dashboardSummary: "एक समय में एक इंस्ट्रूमेंट। हर मॉक ऑर्डर से पहले जोखिम सीमाएँ जाँची जाती हैं।",
  languageLabel: "भाषा", dateLocale: "hi-IN",
  paperMock: "पेपर / मॉक", sessionReference: "एक्सचेंज सत्र संदर्भ", noLiveData: "लाइव डेटा नहीं", mockOrdersTag: "मॉक ऑर्डर",
  syntheticDemo: "केवल सिंथेटिक डेमो। कोई लाइसेंस वाला प्रदाता जुड़ा नहीं है।", chartTimeframeReason: "अभी केवल पूरी हुई 1-मिनट कैंडल उपलब्ध हैं",
  chartUp: "ऊपर", chartDown: "नीचे", readOnly: "केवल पढ़ने के लिए", bars: "बार", utcToIst: "UTC → IST",
  ruleBasedContext: "नियम-आधारित संदर्भ", withheld: "रोका गया", indicatorSummary: "EMA · ATR · RSI · ज़ोन · परिदृश्य",
  waitingLicensedData: "पूरी हुई कैंडल की प्रतीक्षा", analysisLoading: "पूरी हुई सैंपल कैंडल जाँची जा रही हैं…",
  analysisUnavailable: "विश्लेषण सेवा उपलब्ध नहीं है। सेवा चलने पर फिर कोशिश करें।", analysisRetry: "विश्लेषण फिर आज़माएँ",
  analysisBars: "पूरी हुई कैंडल", analysisFreshness: "डेटा की ताज़गी", analysisSignal: "सिग्नल स्थिति",
  analysisTime: "आखिरी सैंपल कैंडल", analysisWithheld: "सिंथेटिक डेटा के लिए दिशात्मक नतीजे रोके गए",
  analysisIndicatorTitle: "संकेतक मान · 5 मिनट", analysisIndicatorWarmup: "— का मतलब है कि इस गणना के लिए पर्याप्त पूरी कैंडल नहीं हैं।",
  bias: "दिशा", zone: "ज़ोन", confidence: "विश्वास स्तर",
  riskFirst: "पहले जोखिम", calculator: "कैलकुलेटर", units: "यूनिट", lot: "लॉट", mockOnly: "केवल मॉक",
  suggestedQuantity: "सुझाई गई मात्रा", estimatedRiskShort: "अनुमानित जोखिम", total: "कुल", paperTrading: "पेपर ट्रेडिंग",
  mockFillsSimulated: "मॉक API केवल ऑर्डर स्थिति दर्ज करता है; यह फ़िल या पोज़िशन नहीं बनाता। सिम्युलेटेड फ़िल के लिए Backtest → Paper Replay इस्तेमाल करें।", localOnlyShort: "केवल लोकल", dismiss: "बंद करें", removeAlert: "अलर्ट हटाएँ",
  simulatedLocalOnly: "सिम्युलेटेड · केवल लोकल", close: "बंद करें", marketMock: "मार्केट · मॉक", marketReference: "सिम्युलेटेड मार्केट संदर्भ मूल्य पर",
  account: "खाता", orderTypeMode: "ऑर्डर प्रकार · मोड", orderOpen: "खुला", orderPartiallyFilled: "आंशिक रूप से भरा", orderFilled: "पूरा",
  orderCancelled: "रद्द", orderRejected: "अस्वीकृत", orderExpired: "समाप्त", orderUnknown: "अज्ञात",
  lotSize: "लॉट साइज़", tickSize: "टिक साइज़", myWorkspace: "मेरा वर्कस्पेस", mainNavigation: "मुख्य नेविगेशन",
  available: "मॉक टेस्ट के लिए उपलब्ध", paperOrder: "मॉक ऑर्डर", buy: "खरीदें", sell: "बेचें", quantity: "मात्रा",
  protectiveStop: "प्रोटेक्टिव स्टॉप", approxRisk: "अनुमानित स्टॉप जोखिम", notional: "अनुमानित राशि", submit: "मॉक ऑर्डर जाँचें",
  advancedView: "उन्नत दृश्य", simpleView: "सरल दृश्य", advancedDetailsTitle: "ऑर्डर विवरण और सक्रिय सीमाएँ",
  referencePrice: "सिम्युलेटेड संदर्भ मूल्य", advancedDetailsNote: "यह सिंथेटिक संदर्भ है। सबमिशन से पहले बैकएंड नवीनतम सीमाएँ फिर जाँचता है।",
  cancel: "रद्द करें", confirm: "मॉक ऑर्डर की पुष्टि करें", orderWarning: "यह केवल लोकल मॉक ब्रोकर को सिम्युलेटेड ऑर्डर भेजेगा।",
  orders: "हाल के मॉक ऑर्डर", noOrders: "अभी कोई मॉक ऑर्डर नहीं", status: "स्थिति", side: "दिशा", instrument: "इंस्ट्रूमेंट",
  recentActivity: "हाल की गतिविधि", noJournal: "अभी जर्नल खाली है", saveEntry: "जर्नल सेव करें", note: "आपने क्या सीखा?",
  setupTag: "सेटअप / भावना", resultRupees: "मैनुअल P&L (₹)", export: "जर्नल एक्सपोर्ट करें", delete: "सभी एंट्री हटाएँ",
  localJournal: "केवल इस ब्राउज़र में सेव। सर्वर पर नहीं भेजा जाता।", alerts: "प्राइस अलर्ट", addAlert: "सर्वर नियम बनाएँ",
  serverManagedAlerts: "सर्वर पर प्रबंधित · मॉक डेटा अलर्ट ट्रिगर नहीं कर सकता",
  alertRuleType: "अलर्ट की शर्त", priceCrossing: "प्राइस क्रॉसिंग", zoneTouch: "प्राइस ज़ोन में आए", volumeSpike: "वॉल्यूम स्पाइक",
  volumeMultiplier: "औसत के मुकाबले वॉल्यूम", volumeAlert: "मौजूदा / औसत वॉल्यूम", volumeHistory: "इंतज़ार · लगातार 20 नियमित कैंडल चाहिए",
  zeroVolumeBaseline: "इंतज़ार · औसत वॉल्यूम शून्य है", alertOutsideSession: "इंतज़ार · नियमित सत्र की कैंडल चाहिए", alertPrice: "ट्रिगर प्राइस",
  alertDirection: "प्राइस की दिशा", alertLower: "ज़ोन का निचला प्राइस", alertUpper: "ज़ोन का ऊपरी प्राइस", above: "ऊपर", below: "नीचे",
  alertPaused: "रोका गया · लाइसेंस वाले स्रोत के अधिकार पुष्टि नहीं हुए", alertWaiting: "इंतज़ार · अनुमोदित ताज़ा 1-मिनट डेटा",
  alertArmed: "सक्रिय · केवल पूरी हुई 1-मिनट कैंडल पर जाँच", alertTriggered: "ट्रिगर हुआ",
  alertStale: "रोका गया · नया बाज़ार डेटा पुराना है", alertSynthetic: "रोका गया · सिंथेटिक या अनधिकृत डेटा नज़रअंदाज़",
  alertIncomplete: "इंतज़ार · अधूरी कैंडल नज़रअंदाज़", alertFuture: "रोका गया · भविष्य की तारीख वाली कैंडल अस्वीकृत",
  alertEvents: "हाल के इन-ऐप अलर्ट", noAlertEvents: "अभी कोई अलर्ट घटना नहीं", noAlerts: "अभी कोई अलर्ट सेव नहीं", feedHealth: "फीड स्थिति",
  riskLimits: "अकाउंट जोखिम सीमाएँ", maxOrder: "अधिकतम ऑर्डर राशि", dailyLoss: "दैनिक नुकसान सीमा", tradeLoss: "प्रति ट्रेड नुकसान सीमा",
  maxQty: "अधिकतम मात्रा", killSwitch: "किल स्विच", activateKill: "किल स्विच चालू करें", deactivateKill: "मॉक ऑर्डर फिर शुरू करें",
  killOn: "चालू · मॉक सबमिशन बंद", killOff: "बंद · केवल मॉक ब्रोकर", saveLimits: "सीमाएँ सेव करें",
  suggestedSize: "पोज़िशन साइज़ अनुमान", capital: "उदाहरण पूँजी", riskPercent: "प्रति ट्रेड जोखिम %", entry: "एंट्री संदर्भ",
  size: "सुझाई मात्रा", sizeRisk: "सुझाई मात्रा का जोखिम", sizeDisclaimer: "केवल कैलकुलेटर। मॉक ऑर्डर से पहले लॉट, टिक और सीमाएँ जाँचें।",
  connected: "बैकएंड जुड़ा है", disconnected: "बैकएंड उपलब्ध नहीं", refresh: "रीफ़्रेश", logout: "साइन आउट",
  feedConnected: "जुड़ा है", noInstrumentsFound: "कोई इंस्ट्रूमेंट नहीं मिला",
  errorGeneric: "कुछ ठीक नहीं हुआ। फिर कोशिश करें।", orderPlaced: "मॉक ऑर्डर भेजा गया", cancelOrder: "ऑर्डर रद्द करें",
  orderOutsideSession: "नए ऑर्डर केवल सप्ताह के दिनों में 09:15–15:30 IST के डेमो सत्र में उपलब्ध हैं।",
  orderOpeningCooldown: "शुरुआती कूलडाउन के दौरान नए ऑर्डर बंद हैं।", orderCutoffPassed: "नए ऑर्डर का तय कटऑफ समय निकल चुका है।",
  dailyOrderLimitReached: "आज के लिए स्वीकार किए गए मॉक ऑर्डर की अधिकतम सीमा पूरी हो गई है।", invalidTradingWindow: "कटऑफ को शुरुआती देरी के बाद और 15:30 IST से पहले चुनें।",
  orderStatusUnknown: "ऑर्डर की स्थिति पता नहीं है। नया ऑर्डर बनाने से पहले स्थिति जाँचें या इसी अनुरोध को फिर भेजें।",
  checkStatus: "स्थिति जाँचें", retrySameOrder: "इसी अनुरोध को फिर भेजें", pendingStorageUnavailable: "लोकल रिकवरी स्टोरेज उपलब्ध नहीं है। नया ऑर्डर नहीं भेजा गया; स्टोरेज और हाल के ऑर्डर जाँचें।",
  resolvePendingOrder: "नया ऑर्डर बनाने से पहले पिछले अनुरोध की स्थिति साफ़ करें।", invalidPendingOrder: "सेव किया रिकवरी रिकॉर्ड पढ़ा नहीं जा सकता। नया ऑर्डर खोलने से पहले हाल के ऑर्डर जाँचें।",
  acknowledgeOrdersReviewed: "मैंने हाल के ऑर्डर जाँच लिए",
  noChart: "इस डेमो अवधि में कैंडल उपलब्ध नहीं", selected: "चुना हुआ इंस्ट्रूमेंट", newAlert: "सर्वर नियम सेव हुआ। लाइसेंस वाला ताज़ा डेटा अधिकृत होने तक रुका रहेगा।",
  confirmKill: "किल स्विच चालू करें? खुले मॉक ऑर्डर रद्द करने के लिए भेजे जाएँगे।", confirmResume: "मॉक ऑर्डर फिर शुरू करें?", confirmRetryKill: "अनसुलझे मॉक ऑर्डर रद्द करने की फिर कोशिश करें?",
  rightsAcknowledgement: "मेरे पास इस ऐतिहासिक डेटा के उपयोग का अधिकार है। CSV इसी ब्राउज़र टैब में रहेगा और अपलोड नहीं होगा।",
  chooseCsv: "ऐतिहासिक CSV चुनें", runBacktest: "बैकटेस्ट चलाएँ", clearBacktest: "इम्पोर्ट किया डेटा हटाएँ", exportBacktest: "नतीजे एक्सपोर्ट करें",
  noBacktest: "इस उदाहरण रणनीति के लिए कम-से-कम 100 सही 1-मिनट बार इम्पोर्ट करें।",
  pretradeTitle: "ऑर्डर से पहले अनुशासन जाँच", checkPlan: "मेरा सेटअप और अमान्य होने की शर्त लिखी है।",
  checkStop: "मैंने प्रोटेक्टिव स्टॉप और उसकी दिशा जाँची है।", checkRisk: "मैंने मात्रा, कुल राशि और रुपये का जोखिम जाँचा है।",
  checkSimulated: "मैं समझता हूँ कि यह सिंथेटिक डेटा और मॉक ऑर्डर है।", checklistRequired: "रिव्यू से पहले चारों जाँच पूरी करें।",
  pretradeUnavailable: "यहाँ जाँच उपलब्ध नहीं: लाइव ज़ोन की ताज़गी, ट्रेंड का मेल, जोखिम:इनाम, आने वाली घटनाएँ, नुकसान के बाद कूलडाउन और दैनिक नुकसान लॉक। कोई लाइसेंस प्राप्त डेटा फ़ीड या भरोसेमंद फ़िल/पी एंड एल स्रोत जुड़ा नहीं है। सबमिशन से पहले बैकएंड उपलब्ध मॉक सीमाएँ और समय नियम फिर जाँचता है।",
  emotion: "मनःस्थिति (स्वयं बताई गई)", calm: "शांत", confident: "आत्मविश्वास", anxious: "चिंतित", fomo: "FOMO",
  revenge: "बदले की ट्रेडिंग", bored: "ऊबा हुआ", other: "अन्य / निश्चित नहीं",
  tradebookRights: "मेरे पास इस ट्रेड फ़ाइल के उपयोग का अधिकार है। यह इसी ब्राउज़र टैब में रहेगा, अपलोड नहीं होगा।",
  chooseTradebook: "नॉर्मलाइज़्ड ट्रेडबुक CSV चुनें", reportReference: "केवल संदर्भ · टैक्स सलाह नहीं", exportPnlCsv: "P&L CSV एक्सपोर्ट करें",
  scanEyebrow: "इंस्ट्रूमेंट स्कैन · डेटा गुणवत्ता जाँची जाती है", scannerStatus: "स्कैनर स्थिति",
  scanDescription: "ताज़ा और अधिकार-सत्यापित मल्टी-इंस्ट्रूमेंट डेटा तथा साझा विश्लेषण रूट उपलब्ध होने तक स्कैन नतीजे बंद रहेंगे।",
  scanNoProvider: "कोई लाइसेंस वाला मार्केट-डेटा प्रदाता कॉन्फ़िगर नहीं है।", scanStatusUnavailable: "मार्केट-डेटा स्थिति उपलब्ध नहीं है। फिर से कोशिश करने के लिए रिफ़्रेश करें।", scannerDisclaimer: "स्कैन के लिए स्वीकृत प्रदाता, ताज़ा डेटा और साझा विश्लेषण सेवा चाहिए। ये जाँचें पूरी होने तक कोई स्कैन नहीं चलेगा।",
  scannerUnavailable: "अनुपलब्ध · सुरक्षित रूप से बंद", freshMultiTimeframe: "ताज़ा मल्टी-टाइमफ्रेम फीड", unavailable: "अनुपलब्ध",
  instrumentRights: "इंस्ट्रूमेंट सूची के डेटा अधिकार", notVerified: "सत्यापित नहीं", backendScanRoute: "बैकएंड स्कैन रूट", notConnected: "जुड़ा नहीं",
  mockExcluded: "मॉक कैंडल जानबूझकर शामिल नहीं हैं। बैकग्राउंड में कोई स्कैन नहीं चल रहा।", plannedNotEvaluated: "प्रस्तावित शर्तें · जाँची नहीं गईं",
  analysisFilters: "विश्लेषण के अनुरूप फ़िल्टर", trendAlignment: "ट्रेंड का मेल", trendEvidence: "EMA 20 / 50 / 200 और ADX, टाइमफ्रेम प्रमाण के साथ",
  freshZone: "ताज़ा सप्लाई / डिमांड ज़ोन", zoneEvidence: "ज़ोन की उम्र, टच, अमान्य होने की शर्त और स्रोत टाइमफ्रेम",
  relativeVolumeQuality: "रिलेटिव वॉल्यूम और गुणवत्ता", volumeEvidence: "ताज़गी, गैप, पूर्णता और डेटा-अधिकार संकेत", notEvaluated: "जाँचा नहीं गया",
  historicalLocalOnly: "ऐतिहासिक सिमुलेशन · केवल लोकल", backtestDescription: "एक पारदर्शी उदाहरण रणनीति देखने के लिए अपना लाइसेंस वाला 1-मिनट OHLCV CSV इस्तेमाल करें।",
  inputAssumptions: "इनपुट और मान्यताएँ", selectInstrument: "इंस्ट्रूमेंट चुनें", csvColumns: "CSV कॉलम",
  tickSizeLabel: "टिक साइज़", lotSizeLabel: "लॉट साइज़", barsLoaded: "लोड किए बार", startingCapital: "शुरुआती पूँजी (₹)",
  estimatedFee: "अनुमानित शुल्क (bps / साइड)", slippage: "स्लिपेज (टिक / साइड)", walkForward: "3-विंडो वॉक-फ़ॉरवर्ड",
  educationalSimulation: "यह सीखने के लिए सिमुलेशन है, ट्रेडिंग सलाह नहीं। आपका CSV सेव या अपलोड नहीं होता।",
  backtestDataHandling: "इस टेस्ट के लिए ऐप मार्केट इतिहास नहीं माँगता। चुना इंस्ट्रूमेंट केवल टिक और लॉट साइज़ देता है। इम्पोर्ट किया CSV मेमोरी में रहता है और इंस्ट्रूमेंट बदलने, पेज रीफ़्रेश करने, टैब बंद करने या यहाँ हटाने पर साफ़ हो जाता है।",
  csvLimits: "100–50,000 पंक्तियाँ · अधिकतम 5 MB · पूरे नियमित सत्र के 1-मिनट बार · टाइमज़ोन वाला ISO समय या IST स्थानीय समय",
  barsUnit: "बार", tradesUnit: "ट्रेड", walkForwardWindowStats: "हर विंडो संकेतक शुरू करने के लिए केवल पुराने बार लेती है, नई कॉन्फ़िगर की गई पूँजी से शुरू होती है और रणनीति पैरामीटर स्थिर रखती है।",
  walkForwardCaveat: "ये अलग-अलग रीप्ले एक लगातार पोर्टफ़ोलियो नहीं हैं और सर्वाइवरशिप बायस, मार्केट-डेटा त्रुटियाँ या भविष्य की अनिश्चितता दूर नहीं करते।",
  paperReplayTitle: "मैन्युअल पेपर निष्पादन रीप्ले · केवल लोकल", paperReplayDescription: "इम्पोर्ट किए बार पर काल्पनिक ऑर्डर रखें। ऑर्डर बैकएंड या ब्रोकर तक नहीं जाते।",
  paperReplayDisclosure: "ऐतिहासिक OHLC क्यू-पोज़िशन या कैंडल के अंदर घटनाओं का क्रम नहीं दिखाता। उसी बार में फ़िल नहीं होगा; मार्केट और स्टॉप फ़िल में प्रतिकूल स्प्रेड/स्लिपेज अनुमान जुड़ते हैं। DAY ऑर्डर अगले दिखाए गए सत्र पर और GTC तारीख़ बदलने तक रह सकता है; बाकी मात्रा फ़ाइल के अंत में समाप्त होती है। शुल्क अनुमानित हैं; टैक्स, मार्केट इम्पैक्ट, असली लिक्विडिटी, छुट्टियाँ और ब्रोकर नियम शामिल नहीं हैं। यह पोर्टफ़ोलियो P&L या ट्रेडिंग सलाह नहीं है।",
  replaySide: "साइड", replayType: "ऑर्डर प्रकार", replayQuantity: "मात्रा (पूरे लॉट)", replaySubmitBar: "सिग्नल बार नंबर", replayLimitPrice: "लिमिट कीमत (₹)", replayStopPrice: "स्टॉप कीमत (₹)",
  replayMarket: "मार्केट", replayLimit: "लिमिट", replayStopMarket: "स्टॉप-मार्केट", replayMarketOpen: "बार ओपन पर मार्केट", replayLimitTouch: "लिमिट छुई", replayStopTrigger: "स्टॉप ट्रिगर हुआ",
  replayCancelReason: "रद्दीकरण इस बार के ओपन से पहले लागू हुआ।", replayWaitReason: "अधिकतम योग्य बार प्रतीक्षा पूरी हुई।", replayDayReason: "DAY ऑर्डर अगले सत्र की सीमा पर समाप्त हुआ।", replayEndReason: "बाकी मात्रा फ़िल होने से पहले रीप्ले समाप्त हुआ।", replayPriceReason: "निष्पादन कीमत अमान्य थी या लिमिट का उल्लंघन करती।", replaySideReason: "ऑर्डर साइड समर्थित नहीं है।", replayTypeReason: "ऑर्डर प्रकार समर्थित नहीं है।", replayQuantityReason: "मात्रा पूरे लॉट में और सिमुलेशन सीमा के भीतर होनी चाहिए।", replaySubmissionReason: "सबमिशन बार रीप्ले इतिहास के बाहर है।", replayTifReason: "ऑर्डर अवधि समर्थित नहीं है।", replayCancelBarReason: "रद्दीकरण बार इतिहास में और सबमिशन के बाद होना चाहिए।", replayLimitReason: "लिमिट ऑर्डर के लिए टिक-अलाइन सकारात्मक कीमत चाहिए।", replayStopReason: "स्टॉप ऑर्डर के लिए टिक-अलाइन सकारात्मक कीमत चाहिए।",
  replayNotionalReason: "फ़िल नोटional ₹1,000,000,000 की सिमुलेशन लेखा सीमा से अधिक है।", replayFeeReason: "अनुमानित फ़िल शुल्क ₹1,000,000 की लेखा सीमा से अधिक है।", replayFillCap: "इस रीप्ले में 50,000 फ़िल सीमा पार होगी। इतिहास या ऑर्डर कम करें।",
  paperPnlTitle: "सिम्युलेटेड P&L · FIFO", paperPnlDisclosure: "रियलाइज़्ड P&L सिम्युलेटेड फ़िल पर FIFO से है। खुले पोज़िशन को आख़िरी इम्पोर्टेड क्लोज पर मार्क किया जाता है; ओपन नेट P&L में एंट्री शुल्क घटते हैं, अनुमानित एग्ज़िट लागत नहीं। टैक्स, वैधानिक शुल्क, मार्जिन या ब्रोकर मिलान शामिल नहीं हैं।",
  paperRealizedGross: "रियलाइज़्ड ग्रॉस P&L", paperRealizedNet: "रियलाइज़्ड नेट P&L", paperRealizedCharges: "रियलाइज़्ड शुल्क", paperOpenNet: "ओपन नेट P&L*", paperCombinedNet: "रियलाइज़्ड + ओपन नेट*", paperOpenQuantity: "खुली मात्रा", paperMark: "आख़िरी बार का मार्क", paperOpenPositions: "खुले पोज़िशन", paperDirection: "दिशा", paperAverageEntry: "औसत एंट्री", paperMarkPrice: "मार्क कीमत", paperUnrealizedGross: "ग्रॉस अनरियलाइज़्ड", paperEntryFees: "आवंटित एंट्री शुल्क", paperUnrealizedNet: "नेट अनरियलाइज़्ड*", paperRealizedMatches: "FIFO रियलाइज़्ड मैच", paperOpenLong: "लॉन्ग", paperOpenShort: "शॉर्ट", paperNoOpenPosition: "कोई खुला पोज़िशन नहीं है।", paperNoRealizedMatches: "इस रीप्ले में कोई पोज़िशन बंद नहीं हुआ।",
  replayTimeInForce: "ऑर्डर अवधि", replayDay: "DAY · अगले सत्र पर समाप्त", replayGtc: "GTC · सत्र के पार रह सकता है", replayCancelBar: "किस बार के बाद रद्द करें (वैकल्पिक)",
  replayLatency: "अतिरिक्त विलंब (बार)", replayParticipation: "अधिकतम वॉल्यूम भागीदारी (%)", replaySpread: "स्प्रेड अनुमान (टिक / साइड)", replayMaxWait: "अधिकतम योग्य प्रतीक्षा बार", replayAddOrder: "ऑर्डर जोड़ें", replayRun: "पेपर रीप्ले चलाएँ", replayClear: "रीप्ले साफ़ करें", replayExport: "रीप्ले JSON एक्सपोर्ट करें",
  replayNoBars: "पहले लोकल ऐतिहासिक बार इम्पोर्ट करें। CSV केवल इसी टैब में रहता है।", replayNoOrders: "कोई ऑर्डर नहीं रखा। सिग्नल बार चुनें और काल्पनिक ऑर्डर जोड़ें।", replayOrdersTitle: "रखे गए ऑर्डर", replayResultsTitle: "रीप्ले नतीजे", replayFillsTitle: "सिम्युलेटेड फ़िल", replayOrderId: "ऑर्डर ID", replayEligibleBar: "इस बार से योग्य", replayStatus: "स्थिति", replayFilledQty: "फ़िल मात्रा", replayRemainingQty: "बाकी", replayAveragePrice: "औसत फ़िल", replayFees: "अनु. शुल्क", replayTime: "समय (IST)", replayFillPrice: "फ़िल कीमत", replayReason: "कारण", replayNoFills: "इस रीप्ले में कोई ऑर्डर फ़िल नहीं हुआ। ऑर्डर समाप्त/रद्द हुए या योग्य वॉल्यूम/कीमत नहीं मिली।", replayShowingFills: "हाल के अधिकतम 100 फ़िल", replayStatusPending: "लंबित", replayStatusPartial: "आंशिक", replayStatusFilled: "फ़िल हुआ", replayStatusCancelled: "रद्द", replayStatusExpired: "समाप्त", replayStatusRejected: "अस्वीकृत", replayRemove: "रखा ऑर्डर हटाएँ", replayInvalid: "ऑर्डर फ़ील्ड और रीप्ले मान्यताएँ जाँचें।", replayTooMany: "एक रीप्ले में अधिकतम 50 ऑर्डर रखें।", replayWorkCap: "ऑर्डर/इतिहास संयोजन ब्राउज़र रीप्ले के लिए बहुत बड़ा है। बार या ऑर्डर कम करें।",
  strategyLongOnly: "EMA 20 / 50 क्रॉसओवर · 2× ATR स्टॉप · केवल लॉन्ग", simulationResults: "सिमुलेशन नतीजे", readyWhenYouAre: "तैयार होने पर शुरू करें",
  nextBarEntry: "एंट्री अगली बार के ओपन पर होती है। पोज़िशन साइज़ जोखिम बजट, लॉट साइज़ और उपलब्ध पूँजी पर आधारित है।",
  netPnl: "नेट P&L", returnLabel: "रिटर्न", endingCapital: "अंतिम पूँजी", maxDrawdown: "अधिकतम गिरावट", tradesWinRate: "ट्रेड · जीत दर",
  estimatedFees: "अनुमानित शुल्क", expectancyPerTrade: "प्रति ट्रेड अपेक्षित परिणाम", profitFactor: "प्रॉफ़िट फ़ैक्टर*", noLosingTrades: "∞ · कोई घाटे वाला ट्रेड नहीं",
  maxExposure: "अधिकतम एक्सपोज़र", dailySharpe: "दैनिक शार्प*", realizedEquityCurve: "रियलाइज़्ड इक्विटी कर्व", recentSimulatedTrades: "हाल के सिम्युलेटेड ट्रेड",
  showingUpTo50: "अधिकतम 50 दिखाए जा रहे हैं", entryLabel: "एंट्री", exitLabel: "एग्ज़िट", quantityLabel: "मात्रा", exitReason: "एग्ज़िट कारण",
  noQualifyingEntry: "इस सैंपल में कोई योग्य एंट्री नहीं मिली। शून्य ट्रेड भी सही नतीजा है।", windowLabel: "विंडो", simulatedEquityCurve: "सिम्युलेटेड रियलाइज़्ड इक्विटी कर्व",
  simulatedCandlestickChart: "ऐतिहासिक कैंडल का सिम्युलेटेड चार्ट", executedFillsLocal: "निष्पादित फ़िल · केवल लोकल",
  reportDescription: "आपकी चुनी नॉर्मलाइज़्ड ट्रेडबुक फ़ाइल से FIFO रियलाइज़्ड P&L।", printSavePdf: "प्रिंट / PDF सेव करें",
  privateFileImport: "निजी फ़ाइल इम्पोर्ट", tradebookSettings: "ट्रेडबुक सेटिंग", requiredNormalizedColumns: "ज़रूरी नॉर्मलाइज़्ड कॉलम",
  reportImportDescription: "एक अकाउंट के निष्पादित फ़िल इस्तेमाल करें। हर पंक्ति का शुल्क आप देते हैं; यह टूल ब्रोकर से नहीं जुड़ता, अकाउंट गतिविधि का मिलान नहीं करता और टैक्स नहीं निकालता।",
  tradebookSegments: "सेगमेंट: CASH_INTRADAY, CASH_DELIVERY, FNO · साइड: BUY या SELL · हर फ़िल की अलग ID · अधिकतम 50,000 पंक्तियाँ और 5 MB",
  reportDataHandling: "CSV केवल ब्राउज़र मेमोरी में प्रोसेस होता है। अकाउंट बदलने, रीफ़्रेश, टैब बंद करने या हाथ से हटाने पर साफ़ हो जाता है।",
  fifoMethod: "मिलान नियम: पूरे सेगमेंट और सिंबल के लिए FIFO; समान समय पर पहले टाइमस्टैम्प, फिर ट्रेड ID।",
  realizedPnlSummary: "रियलाइज़्ड P&L सारांश", segmentInstrument: "सेगमेंट · इंस्ट्रूमेंट",
  clearReport: "रिपोर्ट हटाएँ", fifoBreakdown: "FIFO मिलान · सेगमेंट विवरण", chooseTradebookMessage: "रेफरेंस रियलाइज़्ड P&L के लिए ट्रेडबुक CSV चुनें। खुले ट्रेड अलग दिखेंगे और उनका मार्क-टू-मार्केट मूल्य नहीं निकलेगा।",
  realizedNetPnl: "रियलाइज़्ड नेट P&L", realizedGrossPnl: "रियलाइज़्ड ग्रॉस P&L", chargesMatchedQuantity: "मिले हुए हिस्से का शुल्क",
  fillRowsMatches: "फ़िल पंक्तियाँ · FIFO मिलान", openInventoryQuantity: "खुली मात्रा", openInventoryCharges: "खुली मात्रा पर बाकी शुल्क",
  bySegment: "सेगमेंट के अनुसार", segmentLabel: "सेगमेंट", matchedQuantity: "मिली मात्रा", matchesLabel: "मिलान", grossPnl: "ग्रॉस P&L", chargesLabel: "शुल्क",
  unmatchedInventory: "बिना मिलान की मात्रा · मार्क-टू-मार्केट नहीं", positionsLabel: "पोज़िशन", directionLabel: "दिशा", openQuantity: "खुली मात्रा",
  averageEntry: "औसत एंट्री", unallocatedEntryCharges: "आवंटित न हुआ एंट्री शुल्क", recentFifoMatches: "हाल के FIFO मिलान", instrumentLabel: "इंस्ट्रूमेंट",
  entryToExit: "एंट्री → एग्ज़िट", noTradebookLoaded: "ट्रेडबुक लोड नहीं है", processPrivate: "प्रक्रिया · डिफ़ॉल्ट रूप से निजी",
  journalDescription: "निर्णय और उससे मिली सीख लिखें। नतीजे हाथ से दर्ज होते हैं, ब्रोकर P&L नहीं।", postTradeReview: "ट्रेड के बाद समीक्षा",
  setupPlaceholder: "जैसे: ब्रेकआउट · धैर्य", optionalManualOnly: "वैकल्पिक, केवल हाथ से", journalNotePlaceholder: "योजना, निष्पादन, भावना, अगला सुधार…",
  entriesLabel: "एंट्री", manualPnl: "हाथ से दर्ज P&L", journalPrivacy: "जर्नल इस ब्राउज़र में इस अकाउंट ID के तहत सेव है। ब्राउज़र स्टोरेज साफ़ करने से पहले JSON बैकअप एक्सपोर्ट करें।",
  journalTradeTime: "ट्रेड की तारीख और समय (IST)", journalTradeTimeHint: "वैकल्पिक। खाली छोड़ें तो एंट्री सेव करने का समय लिया जाएगा।",
  journalQuantity: "पोज़िशन मात्रा (वैकल्पिक)", plannedStop: "तय किया गया स्टॉप (₹, वैकल्पिक)", actualStop: "असल स्टॉप (₹, वैकल्पिक)", stopRemoved: "प्रोटेक्टिव स्टॉप हटाया",
  plannedTarget: "तय किया गया लक्ष्य (₹, वैकल्पिक)", actualTarget: "असल लक्ष्य (₹, वैकल्पिक)", journalValuesInvalid: "IST तारीख/समय, मात्रा, P&L, स्टॉप और लक्ष्य की जाँच करें।",
  insightKicker: "अवलोकन · स्वयं दर्ज", insightTitle: "व्यवहार समीक्षा", insightDescription: "आपने जिन ट्रेडों को दर्ज किया है, उनमें पैटर्न देखें। ये अवलोकन ऑर्डर नियंत्रण नहीं बदलते।",
  insightPeriod: "अवधि", insightLast7: "पिछले 7 दिन", insightLast30: "पिछले 30 दिन", insightAll: "सभी एंट्री",
  insightReentryWindow: "दर्ज नुकसान के बाद री-एंट्री समय (मिनट)", insightOvertradingThreshold: "इतनी या अधिक एंट्री वाले दिन दिखाएँ", insightEntriesPerDay: "जर्नल एंट्री",
  insightEntries: "जर्नल एंट्री", insightManualPnl: "दर्ज P&L", insightReentries: "दर्ज नुकसान के बाद चुने समय में एंट्री",
  insightSizeIncreases: "दर्ज नुकसान के बाद उसी सिंबल में मात्रा बढ़ी", insightStopsMoved: "दर्ज स्टॉप बदले गए", insightStopsRemoved: "दर्ज स्टॉप हटाए गए",
  insightOvertradingDays: "आपकी सीमा से अधिक एंट्री वाले दिन", insightDisclaimer: "यह केवल हाथ से दर्ज जर्नल पर आधारित है, ब्रोकर फ़िल पर नहीं। यह ब्रोकर P&L, निदान, निवेश सलाह या ऑर्डर रोक नहीं है।",
  insightByHour: "एंट्री के घंटे के अनुसार दर्ज P&L (IST)", insightByWeekday: "सप्ताह के दिन के अनुसार दर्ज P&L (IST)", insightBySetup: "सेटअप के अनुसार दर्ज P&L",
  insightByQuantity: "सिंबल और सटीक मात्रा के अनुसार दर्ज P&L", insightOvertradingList: "आपकी एंट्री सीमा पूरी करने वाले दिन",
  insightGroup: "समूह", insightMoreGroups: "{count} और समूह नहीं दिखाए गए।", noReportedPnl: "इस अवधि में मैनुअल P&L दर्ज नहीं है।", noReportedPnlValue: "दर्ज नहीं",
  insightMissingQuantity: "{count} P&L एंट्री में मात्रा दर्ज नहीं थी, इसलिए वे मात्रा के विवरण में शामिल नहीं हैं।",
  insightExcludedDates: "गलत या भविष्य के समय वाली {count} एंट्री शामिल नहीं की गईं।",
  weekdaySun: "रविवार", weekdayMon: "सोमवार", weekdayTue: "मंगलवार", weekdayWed: "बुधवार", weekdayThu: "गुरुवार", weekdayFri: "शुक्रवार", weekdaySat: "शनिवार",
  guardrailsAccount: "सुरक्षा सीमाएँ · अकाउंट स्तर", riskDescription: "मॉक ऑर्डर स्वीकार करने से पहले बैकएंड इन नियंत्रणों को लागू करता है।",
  mockAccount: "मॉक अकाउंट", backendPolicy: "बैकएंड नीति", persisted: "सेव है", maxOpenOrders: "अधिकतम खुले ऑर्डर",
  maxOrdersPerDay: "प्रति दिन अधिकतम स्वीकार किए गए ऑर्डर", cutoffTime: "इस समय के बाद नए ऑर्डर बंद (IST)", openingCooldown: "शुरुआती कितने मिनट नए ऑर्डर बंद रहें", 
  dailyOrderEntryNote: "दैनिक गिनती स्वीकार किए गए मॉक ऑर्डर सबमिशन की है, बाद में रद्द हुए ऑर्डर भी इसमें आते हैं। फ़िल मॉडल होने तक यह पूरे हुए ट्रेड की गिनती के बजाय सावधानीपूर्ण ऑर्डर सीमा है। सप्ताह के दिनों में 09:15–15:30 IST के बाहर नए ऑर्डर बंद रहते हैं; नमूना कैलेंडर में एक्सचेंज की छुट्टियाँ शामिल नहीं हैं।",
  dailyOrderCount: "आज स्वीकार एंट्री",
  noEntryWindowWarning: "शुरुआती देरी और लागू कटऑफ का समय आपस में टकरा रहा है, इसलिए बैकएंड तब तक सभी नए ऑर्डर रोकेगा जब तक लागू विंडो सही न हो।",
  policyUpdated: "सेव की गई नीति अपडेट हुई", withYourAccount: "आपके अकाउंट के साथ", emergencyControl: "आपातकालीन नियंत्रण",
  pendingPolicy: "कम सख्त सीमाएँ इस तारीख से लागू होंगी:", pendingPolicyBody: "तब तक बैकएंड मौजूदा सीमाएँ लागू रखेगा।", currentActiveLimits: "अभी लागू:",
  killSwitchDescription: "नए मॉक ऑर्डर रोकता है और खुले मॉक ऑर्डर रद्द करने का अनुरोध करता है। यह किसी ब्रोकर से नहीं जुड़ता।",
  confirmedCancelled: "पुष्टि से रद्द", unresolvedOpen: "अनसुलझे खुले ऑर्डर", killSwitchResumeBlocked: "मॉक ऑर्डर फिर शुरू करने से पहले सभी खुले ऑर्डर रद्द या हल करें।", retryKillCancellation: "अनसुलझे ऑर्डर रद्द करने की फिर कोशिश", mockControlDisclaimer: "केवल लोकल मॉक वातावरण के लिए। यह असली पैसे का ट्रेडिंग नियंत्रण नहीं है।",
};

function Icon({ name, size = 18 }: { name: string; size?: number }) {
  const paths: Record<string, React.ReactNode> = {
    grid: <><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></>,
    chart: <><path d="M3 3v18h18"/><path d="m7 14 4-4 4 3 6-7"/><path d="M17 6h4v4"/></>,
    book: <><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v17H6.5A2.5 2.5 0 0 0 4 22z"/><path d="M4 5.5v14A2.5 2.5 0 0 1 6.5 17H20M8 7h8M8 11h8"/></>,
    shield: <><path d="M12 22s8-4 8-11V5l-8-3-8 3v6c0 7 8 11 8 11"/><path d="m9 12 2 2 4-4"/></>,
    search: <><circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/></>,
    bell: <><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9"/><path d="M10 21h4"/></>,
    refresh: <><path d="M20 7v5h-5M4 17v-5h5"/><path d="M5.5 9a7 7 0 0 1 11.6-2L20 12M4 12l2.9 5a7 7 0 0 0 11.6-2"/></>,
    arrow: <><path d="M7 17 17 7M7 7h10v10"/></>,
    close: <><path d="m18 6-12 12M6 6l12 12"/></>,
    plus: <><path d="M12 5v14M5 12h14"/></>,
    download: <><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="m7 10 5 5 5-5M12 15V3"/></>,
  };
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name] ?? paths.grid}</svg>;
}

function safeLoad<T>(key: string, fallback: T): T {
  try { const value = localStorage.getItem(key); return value ? JSON.parse(value) as T : fallback; }
  catch { return fallback; }
}

const isJournalEntry = isJournalRecord;

function isPriceAlert(value: unknown): value is AlertRule {
  if (!value || typeof value !== "object") return false;
  const alert = value as Partial<AlertRule>;
  const hasPositivePrice = (typeof alert.price === "string" || typeof alert.price === "number") && number(alert.price) > 0;
  const hasValidUpperPrice = (typeof alert.upperPrice === "string" || typeof alert.upperPrice === "number")
    && number(alert.upperPrice) > number(alert.price);
  const validCondition = alert.ruleType === "ZONE_TOUCH"
    ? alert.direction == null && hasValidUpperPrice
    : alert.ruleType === "VOLUME_SPIKE"
      ? alert.direction == null && alert.price == null && alert.upperPrice == null
        && (typeof alert.volumeMultiplier === "string" || typeof alert.volumeMultiplier === "number")
        && number(alert.volumeMultiplier) >= 1.1 && number(alert.volumeMultiplier) <= 10
        && alert.lookbackCandles === 20
      : alert.ruleType === "PRICE_CROSSING" && (alert.direction === "ABOVE" || alert.direction === "BELOW")
        && (alert.upperPrice == null);
  const priceCondition = alert.ruleType === "VOLUME_SPIKE" || hasPositivePrice;
  return typeof alert.id === "string" && alert.id.length <= 80 && typeof alert.instrumentId === "string" &&
    validCondition && priceCondition &&
    ["PAUSED", "WAITING_FOR_DATA", "ARMED", "TRIGGERED"].includes(String(alert.state)) && typeof alert.createdAt === "string";
}

function isAlertEvent(value: unknown): value is AlertEvent {
  if (!value || typeof value !== "object") return false;
  const event = value as Partial<AlertEvent>;
  const priceCrossing = event.ruleType === "PRICE_CROSSING" && (event.direction === "ABOVE" || event.direction === "BELOW") && event.upperPrice == null;
  const zoneTouch = event.ruleType === "ZONE_TOUCH" && event.direction == null
    && (typeof event.upperPrice === "string" || typeof event.upperPrice === "number")
    && number(event.upperPrice) > number(event.thresholdPrice);
  const volumeSpike = event.ruleType === "VOLUME_SPIKE" && event.direction == null
    && event.thresholdPrice == null && event.upperPrice == null
    && (typeof event.volumeMultiplier === "string" || typeof event.volumeMultiplier === "number")
    && number(event.volumeMultiplier) >= 1.1 && number(event.volumeMultiplier) <= 10
    && event.lookbackCandles === 20 && typeof event.triggerVolume === "number" && event.triggerVolume >= 0
    && (typeof event.referenceAverageVolume === "string" || typeof event.referenceAverageVolume === "number")
    && number(event.referenceAverageVolume) > 0;
  return typeof event.id === "string" && typeof event.instrumentId === "string" &&
    (priceCrossing || zoneTouch || volumeSpike) &&
    (volumeSpike || ((typeof event.thresholdPrice === "string" || typeof event.thresholdPrice === "number") && number(event.thresholdPrice) > 0)) &&
    (typeof event.triggerPrice === "string" || typeof event.triggerPrice === "number") && number(event.triggerPrice) > 0 &&
    typeof event.createdAt === "string" && typeof event.candleTime === "string";
}

function alertStateLabel(alert: AlertRule, labels: typeof en): string {
  if (alert.reasonCode === "INSUFFICIENT_VOLUME_HISTORY" || alert.reasonCode === "DISCONTINUOUS_VOLUME_HISTORY") return labels.volumeHistory;
  if (alert.reasonCode === "ZERO_VOLUME_BASELINE") return labels.zeroVolumeBaseline;
  if (alert.reasonCode === "OUTSIDE_SUPPORTED_SESSION_OR_INVALID_VOLUME") return labels.alertOutsideSession;
  if (alert.state === "WAITING_FOR_DATA") return labels.alertWaiting;
  if (alert.state === "ARMED") return labels.alertArmed;
  if (alert.state === "TRIGGERED") return labels.alertTriggered;
  if (alert.reasonCode === "STALE_DATA") return labels.alertStale;
  if (alert.reasonCode === "UNAPPROVED_OR_SYNTHETIC_SOURCE") return labels.alertSynthetic;
  if (alert.reasonCode === "INCOMPLETE_CANDLE") return labels.alertIncomplete;
  if (alert.reasonCode === "FUTURE_CANDLE") return labels.alertFuture;
  return labels.alertPaused;
}

export function TradingDashboard({ apiBase, storageKey, compact = false }: Props) {
  const [session, setSession] = useState<Session | null>(null);
  const [sessionReady, setSessionReady] = useState(false);
  const [language, setLanguage] = useState<"en" | "hi">("en");
  const [tab, setTab] = useState<Tab>("overview");
  const [pretradeChecks, setPretradeChecks] = useState<PretradeChecks>({ plan: false, stop: false, risk: false, simulatedData: false });
  const [authMode, setAuthMode] = useState<"login" | "signup">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [authError, setAuthError] = useState("");
  const [authBusy, setAuthBusy] = useState(false);
  const [loading, setLoading] = useState(false);
  const [connected, setConnected] = useState(false);
  const [error, setError] = useState("");
  const [instruments, setInstruments] = useState<Instrument[]>([]);
  const [quotes, setQuotes] = useState<Quote[]>([]);
  const [dataStatus, setDataStatus] = useState<DemoStatus | null>(null);
  const [series, setSeries] = useState<CandleSeries | null>(null);
  const [analysis, setAnalysis] = useState<AnalysisSummary | null>(null);
  const [analysisLoading, setAnalysisLoading] = useState(false);
  const [analysisFailed, setAnalysisFailed] = useState(false);
  const [analysisRequestNonce, setAnalysisRequestNonce] = useState(0);
  const [selectedId, setSelectedId] = useState("");
  const [query, setQuery] = useState("");
  const [orders, setOrders] = useState<Order[]>([]);
  const [policy, setPolicy] = useState<RiskPolicy>(initialPolicy);
  const [killSwitch, setKillSwitch] = useState<KillSwitch>({ active: false, confirmedCancelled: 0, unresolvedOrders: 0 });
  const [capital, setCapital] = useState("100000");
  const [riskPercent, setRiskPercent] = useState("1");
  const [entryPrice, setEntryPrice] = useState("");
  const [stopPrice, setStopPrice] = useState("");
  const [draft, setDraft] = useState<Draft | null>(null);
  const [confirmOrder, setConfirmOrder] = useState(false);
  const [advancedOrderDetails, setAdvancedOrderDetails] = useState(false);
  const [pendingOrderKey, setPendingOrderKey] = useState("");
  const [pendingRecovery, setPendingRecovery] = useState<PendingOrderState>({ kind: "none" });
  const [orderBusy, setOrderBusy] = useState(false);
  const [journal, setJournal] = useState<Entry[]>([]);
  const [journalReady, setJournalReady] = useState(false);
  const [journalNote, setJournalNote] = useState("");
  const [journalSetup, setJournalSetup] = useState("");
  const [journalEmotion, setJournalEmotion] = useState("calm");
  const [journalResult, setJournalResult] = useState("");
  const [journalDraft, setJournalDraft] = useState<JournalDraft>({ tradeTime: "", quantity: "", plannedStop: "", actualStop: "", stopRemoved: false, plannedTarget: "", actualTarget: "" });
  const [backtestAcknowledged, setBacktestAcknowledged] = useState(false);
  const [backtestFileName, setBacktestFileName] = useState("");
  const [backtestBars, setBacktestBars] = useState<BacktestBar[] | null>(null);
  const [backtestResult, setBacktestResult] = useState<BacktestResult | null>(null);
  const [walkForwardResult, setWalkForwardResult] = useState<WalkForwardResult | null>(null);
  const [backtestError, setBacktestError] = useState("");
  const [backtestSettings, setBacktestSettings] = useState<BacktestSettings>({ startingCapital: "100000", riskPercent: "1", feeBpsPerSide: "2", slippageTicks: "1" });
  const [tradebookAcknowledged, setTradebookAcknowledged] = useState(false);
  const [tradebookFileName, setTradebookFileName] = useState("");
  const [tradebookReport, setTradebookReport] = useState<TradebookReport | null>(null);
  const [tradebookError, setTradebookError] = useState("");
  const [alerts, setAlerts] = useState<AlertRule[]>([]);
  const [alertEvents, setAlertEvents] = useState<AlertEvent[]>([]);
  const [alertReady, setAlertReady] = useState(false);
  const [alertBusy, setAlertBusy] = useState(false);
  const [alertRuleType, setAlertRuleType] = useState<AlertRuleType>("PRICE_CROSSING");
  const [alertDirection, setAlertDirection] = useState<"ABOVE" | "BELOW">("ABOVE");
  const [alertPrice, setAlertPrice] = useState("");
  const [alertUpperPrice, setAlertUpperPrice] = useState("");
  const [alertVolumeMultiplier, setAlertVolumeMultiplier] = useState("2.00");
  const [limitsDraft, setLimitsDraft] = useState({ maxQuantity: "100", maxOrderNotional: "100000", maxDailyLoss: "5000", maxTradeLoss: "1000", maxOpenOrders: "5", maxOrdersPerDay: "10", noNewOrdersAfter: "15:00", noOrdersFirstMinutes: "5" });
  const [policyBusy, setPolicyBusy] = useState(false);
  const [killSwitchBusy, setKillSwitchBusy] = useState(false);
  const [toast, setToast] = useState("");
  const t = language === "hi" ? hi : en;
  const sessionRef = useRef(session);
  sessionRef.current = session;
  const orderDialogRef = useRef<HTMLElement | null>(null);
  const orderTriggerRef = useRef<HTMLElement | null>(null);
  const confirmHeadingRef = useRef<HTMLHeadingElement | null>(null);
  const loadEpochRef = useRef<RequestEpoch | null>(null);
  if (loadEpochRef.current === null) loadEpochRef.current = new RequestEpoch();
  const orderBusyRef = useRef(orderBusy);
  const killSwitchBusyRef = useRef(false);
  const orderOpen = Boolean(draft);
  orderBusyRef.current = orderBusy;

  useEffect(() => {
    try {
      const saved = sessionStorage.getItem(storageKey);
      if (saved) {
        const parsed = JSON.parse(saved) as Session;
        if (typeof parsed.accessToken === "string" && typeof parsed.refreshToken === "string" && typeof parsed.userId === "string") setSession(parsed);
      }
    } catch { try { sessionStorage.removeItem(storageKey); } catch { /* Storage can be disabled by the browser. */ } }
    setSessionReady(true);
  }, [storageKey]);

  useEffect(() => {
    const root = document.documentElement;
    const previousLanguage = root.lang;
    root.lang = language;
    return () => { root.lang = previousLanguage; };
  }, [language]);

  useEffect(() => {
    if (!sessionReady) return;
    try {
      if (session) sessionStorage.setItem(storageKey, JSON.stringify(session));
      else sessionStorage.removeItem(storageKey);
    } catch { /* Keep this tab usable if browser storage is blocked. */ }
  }, [session, sessionReady, storageKey]);

  useEffect(() => {
    if (!orderOpen) return;
    const dialog = orderDialogRef.current;
    if (!dialog) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        if (!orderBusyRef.current) { setDraft(null); setConfirmOrder(false); }
        return;
      }
      if (event.key !== "Tab") return;
      const focusable = [...dialog.querySelectorAll<HTMLElement>(
        'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])',
      )].filter((element) => element.getAttribute("aria-hidden") !== "true");
      if (!focusable.length) { event.preventDefault(); return; }
      const first = focusable[0], last = focusable[focusable.length - 1];
      if (event.shiftKey && (document.activeElement === first || !dialog.contains(document.activeElement))) {
        event.preventDefault(); last.focus();
      } else if (!event.shiftKey && (document.activeElement === last || !dialog.contains(document.activeElement))) {
        event.preventDefault(); first.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      if (orderTriggerRef.current?.isConnected) orderTriggerRef.current.focus();
      orderTriggerRef.current = null;
    };
  }, [orderOpen]);

  useEffect(() => {
    if (!orderOpen) return;
    if (confirmOrder) confirmHeadingRef.current?.focus();
    else orderDialogRef.current?.querySelector<HTMLElement>("[data-dialog-initial-focus]")?.focus();
  }, [confirmOrder, orderOpen]);

  const api = useMemo(() => new ApiClient(apiBase, () => sessionRef.current, (tokens) => {
    setSession(tokens ? { accessToken: tokens.accessToken, refreshToken: tokens.refreshToken, userId: tokens.userId } : null);
  }), [apiBase]);
  const selected = instruments.find((item) => item.id === selectedId) ?? instruments[0] ?? null;
  const selectedQuote = quotes.find((quote) => quote.instrumentId === selected?.id) ?? null;
  const price = number(selectedQuote?.price);
  const tick = number(selected?.tickSize) || 0.05;
  const lotSize = selected?.lotSize ?? 1;
  const maxQuantity = Math.max(0, Number(limitsDraft.maxQuantity) || policy.maxQuantity);
  const recommended = useMemo(() => {
    const entry = number(entryPrice) || price;
    const stop = number(stopPrice) || Math.max(tick, entry * 0.99);
    const distance = Math.abs(entry - stop);
    const riskCash = Math.min(number(capital) * number(riskPercent) / 100, number(policy.maxTradeLoss));
    if (!(distance > 0) || !(riskCash > 0) || !(entry > 0)) return 0;
    const maxByRisk = Math.floor(riskCash / distance / lotSize) * lotSize;
    const maxByNotional = Math.floor(number(policy.maxOrderNotional) / entry / lotSize) * lotSize;
    return Math.max(0, Math.min(maxByRisk, maxByNotional, maxQuantity));
  }, [entryPrice, stopPrice, price, tick, capital, riskPercent, lotSize, policy.maxOrderNotional, policy.maxTradeLoss, maxQuantity]);
  const calculatedRisk = recommended * Math.abs((number(entryPrice) || price) - (number(stopPrice) || Math.max(tick, price * 0.99)));

  const load = useCallback(async () => {
    if (!session) return;
    const loadEpoch = loadEpochRef.current!.begin();
    setLoading(true); setError("");
    const results = await Promise.allSettled([api.instruments(), api.quotes(), api.dataStatus(), api.orders(), api.riskPolicy(), api.killSwitch(), api.alerts(), api.alertEvents()]);
    if (!loadEpochRef.current!.isCurrent(loadEpoch)) return;
    const [instrumentResult, quoteResult, statusResult, orderResult, policyResult, killResult, alertResult, alertEventResult] = results;
    const failures: string[] = [];
    if (instrumentResult.status === "fulfilled") {
      const items = instrumentResult.value; setInstruments(items);
      setSelectedId((current) => current && items.some((item) => item.id === current) ? current : items[0]?.id ?? "");
    } else failures.push(instrumentResult.reason instanceof Error ? instrumentResult.reason.message : t.errorGeneric);
    if (quoteResult.status === "fulfilled") setQuotes(quoteResult.value); else failures.push("quotes");
    if (statusResult.status === "fulfilled") setDataStatus(statusResult.value); else failures.push("feed status");
    const recoveryKey = pendingOrderStorageKey(storageKey, session.userId);
    const recovered = readPendingOrderFromBrowser(recoveryKey);
    if (orderResult.status === "fulfilled") {
      setOrders(orderResult.value);
      if (recovered.kind === "pending") {
        const reconciled = reconcilePendingOrder(orderResult.value, recovered.request);
        if (reconciled.kind === "resolved") {
            const cleared = clearPendingOrderFromBrowser(recoveryKey);
            setPendingRecovery(cleared ? { kind: "none" } : recovered);
        } else if (reconciled.kind === "conflict") setPendingRecovery({ kind: "blocked", reason: "invalid" });
        else setPendingRecovery(recovered);
      } else setPendingRecovery(recovered);
    } else {
      setPendingRecovery(recovered);
      failures.push("orders");
    }
    if (policyResult.status === "fulfilled") {
      setPolicy(policyResult.value);
      setLimitsDraft(riskPolicyDraft(policyResult.value));
    } else failures.push("risk policy");
    if (killResult.status === "fulfilled") setKillSwitch(killResult.value); else failures.push("kill switch");
    if (alertResult.status === "fulfilled") setAlerts(alertResult.value.filter(isPriceAlert).slice(0, 100)); else failures.push("alerts");
    if (alertEventResult.status === "fulfilled") setAlertEvents(alertEventResult.value.filter(isAlertEvent).slice(0, 100)); else failures.push("alert events");
    setAlertReady(true);
    setConnected(results.every((result) => result.status === "fulfilled"));
    if (failures.length) setError(failures[0]);
    setLoading(false);
  }, [api, session, storageKey, t.errorGeneric]);

  useEffect(() => {
    void load();
    return () => loadEpochRef.current?.invalidate();
  }, [load]);
  useEffect(() => {
    setSeries(null);
    if (!selected || !dataStatus || !session) return;
    let active = true;
    api.candles(selected.id, dataStatus.demoRangeStart, dataStatus.demoRangeEnd)
      .then((result) => { if (active) setSeries(result); })
      .catch(() => { if (active) setSeries(null); });
    return () => { active = false; };
  }, [api, dataStatus, selected?.id, session]);
  useEffect(() => {
    setAnalysis(null);
    setAnalysisFailed(false);
    if (!session || !selected || !series || series.instrumentId !== selected.id) {
      setAnalysisLoading(false);
      return;
    }
    let active = true;
    setAnalysisLoading(true);
    api.analysis(selected.id, "5m", series.from, series.to, series.source)
      .then((result) => { if (active) setAnalysis(result); })
      .catch(() => { if (active) setAnalysisFailed(true); })
      .finally(() => { if (active) setAnalysisLoading(false); });
    return () => { active = false; };
  }, [api, selected?.id, series, session?.userId, analysisRequestNonce]);
  useEffect(() => {
    setBacktestBars(null); setBacktestResult(null); setWalkForwardResult(null); setBacktestFileName(""); setBacktestError("");
  }, [selectedId]);
  useEffect(() => {
    setBacktestBars(null); setBacktestResult(null); setWalkForwardResult(null); setBacktestFileName(""); setBacktestError("");
    setTradebookFileName(""); setTradebookReport(null); setTradebookError("");
    setBacktestAcknowledged(false); setTradebookAcknowledged(false);
  }, [session?.userId]);
  useEffect(() => {
    setJournalReady(false);
    if (!session) { setJournal([]); return; }
    const savedJournal = safeLoad<unknown>(`algotrade.journal.${session.userId}`, []);
    setJournal(Array.isArray(savedJournal) ? savedJournal.filter(isJournalEntry).slice(0, 2000) : []);
    setJournalReady(true);
  }, [session]);
  useEffect(() => {
    setAlertReady(false);
    if (!session) { setAlerts([]); setAlertEvents([]); }
  }, [session?.userId]);
  useEffect(() => {
    if (session && journalReady) {
      try { localStorage.setItem(`algotrade.journal.${session.userId}`, JSON.stringify(journal.slice(0, 2000))); }
      catch { setError("Browser storage is full or unavailable. Export or remove journal entries to continue."); }
    }
  }, [journal, journalReady, session]);
  useEffect(() => {
    if (!toast) return;
    const timeout = window.setTimeout(() => setToast(""), 3600);
    return () => window.clearTimeout(timeout);
  }, [toast]);

  async function authenticate(event: React.FormEvent) {
    event.preventDefault(); setAuthBusy(true); setAuthError("");
    if (authMode === "signup" && new TextEncoder().encode(password).byteLength > 72) {
      setAuthError("Password must be 72 UTF-8 bytes or fewer."); setAuthBusy(false); return;
    }
    try {
      const tokens = authMode === "login" ? await api.login(email, password) : await api.signup(email, password);
      setSession({ accessToken: tokens.accessToken, refreshToken: tokens.refreshToken, userId: tokens.userId });
    } catch (cause) { setAuthError(cause instanceof Error ? cause.message : t.errorGeneric); }
    finally { setAuthBusy(false); }
  }

  async function logout() {
    const refresh = session?.refreshToken;
    const revoke = refresh ? api.logout(refresh) : Promise.resolve();
    sessionRef.current = null;
    setSession(null); setSeries(null); setAnalysis(null); setAnalysisFailed(false); setInstruments([]); setOrders([]); setDraft(null); setConfirmOrder(false); setPendingOrderKey(""); setPendingRecovery({ kind: "none" }); setConnected(false); setError("");
    try { sessionStorage.removeItem(storageKey); } catch { /* Local sign-out remains effective if storage is blocked. */ }
    try { await revoke; } catch { /* Local sign-out still succeeds if server revocation is unavailable. */ }
  }

  function showToast(message: string) { setToast(message); }
  function openTicket(side: Draft["side"]) {
    if (!selected) return;
    if (pendingRecovery.kind !== "none") { setError(t.resolvePendingOrder); return; }
    if (!connected) { setError(t.disconnected); return; }
    orderTriggerRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const reference = price || 0;
    const enteredStop = number(stopPrice);
    const hasValidEnteredStop = side === "BUY" ? enteredStop > 0 && enteredStop < reference : enteredStop > reference;
    const rawStop = hasValidEnteredStop ? enteredStop : side === "BUY" ? reference - Math.max(tick, reference * 0.01) : reference + Math.max(tick, reference * 0.01);
    const roundedStop = Math.max(tick, Math.round(rawStop / tick) * tick);
    setDraft({ side, quantity: String(recommended || 0), stop: roundedStop.toFixed(decimalPlaces(selected.tickSize)) });
    setAdvancedOrderDetails(false);
    setPretradeChecks({ plan: false, stop: false, risk: false, simulatedData: false });
    setPendingOrderKey(crypto.randomUUID()); setConfirmOrder(false);
  }

  function recordResolvedOrder(order: Order, request: OrderRequest) {
    if (!session) return;
    if (reconcilePendingOrder([order], request).kind !== "resolved") {
      setPendingRecovery({ kind: "blocked", reason: "invalid" });
      setError("");
      return;
    }
    const key = pendingOrderStorageKey(storageKey, session.userId);
    const cleared = clearPendingOrderFromBrowser(key);
    setOrders((current) => [order, ...current.filter((item) => item.id !== order.id)]);
    setPendingRecovery(cleared ? { kind: "none" } : { kind: "pending", request });
    setDraft(null); setConfirmOrder(false); setPendingOrderKey("");
    if (order.status === "REJECTED") { setError(t.orderRejected); showToast(t.orderRejected); }
    else { setError(""); showToast(t.orderPlaced); }
    void api.riskPolicy().then((next) => { setPolicy(next); setLimitsDraft(riskPolicyDraft(next)); }).catch(() => {});
  }

  async function checkPendingOrder() {
    if (!session || pendingRecovery.kind !== "pending" || orderBusy) return;
    const request = pendingRecovery.request;
    setOrderBusy(true); setError("");
    try {
      const current = await api.orders();
      setOrders(current);
      const reconciled = reconcilePendingOrder(current, request);
      if (reconciled.kind === "resolved") recordResolvedOrder(reconciled.order, request);
      else if (reconciled.kind === "conflict") { setPendingRecovery({ kind: "blocked", reason: "invalid" }); setError(""); }
    } catch (cause) { setError(riskErrorMessage(cause, t)); }
    finally { setOrderBusy(false); }
  }

  async function retryPendingOrder() {
    if (!session || pendingRecovery.kind !== "pending" || orderBusy) return;
    const recovery = pendingRecovery;
    const key = pendingOrderStorageKey(storageKey, session.userId);
    setOrderBusy(true); setError("");
    try {
      const order = await api.placeOrder(recovery.request);
      recordResolvedOrder(order, recovery.request);
    } catch (cause) {
      if (isAmbiguousOrderFailure(cause)) {
        try {
          const current = await api.orders();
          setOrders(current);
          const reconciled = reconcilePendingOrder(current, recovery.request);
          if (reconciled.kind === "resolved") recordResolvedOrder(reconciled.order, recovery.request);
          else if (reconciled.kind === "conflict") { setPendingRecovery({ kind: "blocked", reason: "invalid" }); setError(""); }
        } catch { /* Keep the exact request persisted until status can be reconciled. */ }
      } else {
        const cleared = clearPendingOrderFromBrowser(key);
        setPendingRecovery(cleared ? { kind: "none" } : { kind: "blocked", reason: "unavailable" });
        setError(cleared ? riskErrorMessage(cause, t) : t.pendingStorageUnavailable);
      }
    } finally { setOrderBusy(false); }
  }

  function acknowledgeInvalidRecovery() {
    if (!session || pendingRecovery.kind !== "blocked" || pendingRecovery.reason !== "invalid") return;
    if (!window.confirm(`${t.acknowledgeOrdersReviewed}?`)) return;
    const key = pendingOrderStorageKey(storageKey, session.userId);
    if (clearPendingOrderFromBrowser(key)) { setPendingRecovery({ kind: "none" }); setError(""); }
    else setError(t.pendingStorageUnavailable);
  }

  async function submitOrder() {
    if (!connected) { setError(t.disconnected); return; }
    if (!draft || !selected || !session || !pendingOrderKey || orderBusy || pendingRecovery.kind !== "none" || killSwitch.active || !Object.values(pretradeChecks).every(Boolean)) {
      if (pendingRecovery.kind !== "none") setError(t.resolvePendingOrder);
      return;
    }
    const quantity = Number(draft.quantity), stop = Number(draft.stop);
    if (!Number.isSafeInteger(quantity) || quantity <= 0 || quantity > policy.maxQuantity || quantity % lotSize !== 0) {
      setError(`Quantity ${t.errorGeneric}`); setConfirmOrder(false); return;
    }
    if (!isTickAligned(stop, tick) || !(stop > 0) || (draft.side === "BUY" ? stop >= price : stop <= price)) {
      setError(`${t.protectiveStop}: price direction or tick-size check failed.`); setConfirmOrder(false); return;
    }
    if (quantity * price > number(policy.maxOrderNotional)) {
      setError(`${t.notional}: configured limit exceeded.`); setConfirmOrder(false); return;
    }
    const stopRisk = quantity * Math.abs(price - stop);
    if (stopRisk > number(policy.maxTradeLoss) || stopRisk > number(capital) * number(riskPercent) / 100) {
      setError(`${t.approxRisk}: configured per-trade budget exceeded at the current mock reference.`); setConfirmOrder(false); return;
    }
    const request: OrderRequest = { clientOrderId: pendingOrderKey, instrumentId: selected.id, side: draft.side,
      orderType: "MARKET", quantity, protectiveStopPrice: stop };
    const recoveryKey = pendingOrderStorageKey(storageKey, session.userId);
    if (!savePendingOrderToBrowser(recoveryKey, request)) { setError(t.pendingStorageUnavailable); return; }
    setOrderBusy(true); setError("");
    try {
      const order = await api.placeOrder(request);
      recordResolvedOrder(order, request);
    } catch (cause) {
      if (isAmbiguousOrderFailure(cause)) {
        setPendingRecovery({ kind: "pending", request });
        setError("");
        try {
          const current = await api.orders();
          setOrders(current);
          const reconciled = reconcilePendingOrder(current, request);
          if (reconciled.kind === "resolved") recordResolvedOrder(reconciled.order, request);
          else if (reconciled.kind === "conflict") { setPendingRecovery({ kind: "blocked", reason: "invalid" }); setError(""); }
        } catch { /* Keep the exact request persisted until status can be reconciled. */ }
      } else {
        const cleared = clearPendingOrderFromBrowser(recoveryKey);
        setPendingRecovery(cleared ? { kind: "none" } : { kind: "blocked", reason: "unavailable" });
        setError(cleared ? riskErrorMessage(cause, t) : t.pendingStorageUnavailable);
      }
    }
    finally { setOrderBusy(false); }
  }

  async function cancelOrder(order: Order) {
    setError("");
    const symbol = instruments.find((item) => item.id === order.instrumentId)?.symbol.replace("DEMO-", "") ?? "Mock order";
    try {
      const updated = await api.cancelOrder(order.id);
      setOrders((current) => current.map((item) => item.id === updated.id ? updated : item));
      showToast(`${symbol} · ${updated.status}`);
    } catch (cause) { setError(riskErrorMessage(cause, t)); }
  }

  async function updateKillSwitch(active: boolean, reason: string) {
    if (killSwitchBusyRef.current) return;
    killSwitchBusyRef.current = true;
    setKillSwitchBusy(true);
    try {
      const updated = await api.setKillSwitch(active, reason);
      await load();
      setKillSwitch((current) => ({ ...current, confirmedCancelled: updated.confirmedCancelled }));
    } catch (cause) { setError(riskErrorMessage(cause, t)); }
    finally { killSwitchBusyRef.current = false; setKillSwitchBusy(false); }
  }

  async function toggleKillSwitch() {
    const active = !killSwitch.active;
    if (!window.confirm(active ? t.confirmKill : t.confirmResume)) return;
    await updateKillSwitch(active, active ? "Activated from AlgoTrade Hub UI" : "Resumed from AlgoTrade Hub UI");
  }

  async function retryKillSwitchCancellation() {
    if (!killSwitch.active || killSwitch.unresolvedOrders <= 0 || killSwitchBusyRef.current) return;
    if (!window.confirm(t.confirmRetryKill)) return;
    await updateKillSwitch(true, "Retried unresolved mock cancellations from AlgoTrade Hub UI");
  }

  async function saveLimits(event: React.FormEvent) {
    event.preventDefault(); setPolicyBusy(true); setError("");
    try {
      const next = await api.updateRiskPolicy({ maxQuantity: Number(limitsDraft.maxQuantity), maxOrderNotional: limitsDraft.maxOrderNotional,
        maxOpenOrders: Number(limitsDraft.maxOpenOrders), maxDailyLoss: limitsDraft.maxDailyLoss, maxTradeLoss: limitsDraft.maxTradeLoss,
        maxOrdersPerDay: Number(limitsDraft.maxOrdersPerDay), noNewOrdersAfter: limitsDraft.noNewOrdersAfter,
        noOrdersFirstMinutes: Number(limitsDraft.noOrdersFirstMinutes) });
      setPolicy(next); setLimitsDraft(riskPolicyDraft(next)); showToast(t.saveLimits);
    } catch (cause) { setError(riskErrorMessage(cause, t)); }
    finally { setPolicyBusy(false); }
  }

  function saveJournal(event: React.FormEvent) {
    event.preventDefault();
    if (!session || !journalNote.trim()) return;
    if (journal.length >= 2000) { setError("Journal reached the 2,000-entry browser limit. Export or remove entries first."); return; }
    const parseOptionalAmount = (value: string, allowNegative: boolean) => {
      if (!value.trim()) return undefined;
      const parsed = Number(value);
      if (!Number.isFinite(parsed) || Math.abs(parsed) > 1_000_000_000 || (!allowNegative && parsed <= 0)) return null;
      return parsed;
    };
    const quantity = journalDraft.quantity.trim() ? Number(journalDraft.quantity) : undefined;
    const result = parseOptionalAmount(journalResult, true);
    const plannedStop = parseOptionalAmount(journalDraft.plannedStop, false);
    const actualStop = parseOptionalAmount(journalDraft.actualStop, false);
    const plannedTarget = parseOptionalAmount(journalDraft.plannedTarget, false);
    const actualTarget = parseOptionalAmount(journalDraft.actualTarget, false);
    const tradeTime = journalDraft.tradeTime.trim() ? parseIstDateTimeLocal(journalDraft.tradeTime) : new Date().toISOString();
    if (!tradeTime || result === null || plannedStop === null || actualStop === null || plannedTarget === null || actualTarget === null ||
        (journalDraft.stopRemoved && actualStop !== undefined) ||
        (quantity !== undefined && (!Number.isSafeInteger(quantity) || quantity <= 0 || quantity > 1_000_000_000))) {
      setError(t.journalValuesInvalid); return;
    }
    const entry: Entry = { id: crypto.randomUUID(), time: new Date().toISOString(), tradeTime, symbol: selected?.symbol ?? "—",
      setup: journalSetup.trim() || "Unlabeled", result: result === undefined ? "" : String(result), note: journalNote.trim(), emotion: journalEmotion };
    if (quantity !== undefined) entry.quantity = quantity;
    if (plannedStop !== undefined) entry.plannedStop = String(plannedStop);
    if (actualStop !== undefined) entry.actualStop = String(actualStop);
    if (journalDraft.stopRemoved) entry.stopRemoved = true;
    if (plannedTarget !== undefined) entry.plannedTarget = String(plannedTarget);
    if (actualTarget !== undefined) entry.actualTarget = String(actualTarget);
    setJournal((current) => [entry, ...current]);
    setJournalNote(""); setJournalSetup(""); setJournalResult(""); setJournalEmotion("calm"); showToast(t.saveEntry);
    setJournalDraft({ tradeTime: "", quantity: "", plannedStop: "", actualStop: "", stopRemoved: false, plannedTarget: "", actualTarget: "" });
  }

  async function importBacktestCsv(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.currentTarget.files?.[0]; event.currentTarget.value = "";
    if (!file) return;
    setBacktestError(""); setBacktestResult(null); setWalkForwardResult(null);
    if (!backtestAcknowledged) { setBacktestError("Confirm data-use rights before choosing a file."); return; }
    if (file.size > 5 * 1024 * 1024) { setBacktestBars(null); setBacktestResult(null); setWalkForwardResult(null); setBacktestFileName(""); setBacktestError("CSV file exceeds the 5 MB limit."); return; }
    if (!selected) { setBacktestError("Select an instrument first."); return; }
    try {
      const csv = await file.text();
      const bars = parseBacktestCsv(csv, number(selected.tickSize) || 0.05);
      setBacktestBars(bars); setBacktestFileName(file.name.slice(0, 120));
    } catch (cause) {
      setBacktestBars(null); setBacktestResult(null); setWalkForwardResult(null); setBacktestFileName("");
      setBacktestError(cause instanceof Error ? cause.message : "CSV could not be read.");
    }
  }

  function runImportedBacktest() {
    if (!backtestBars || !selected) return;
    setBacktestError("");
    try {
      setBacktestResult(runBacktest(backtestBars, {
        startingCapital: Number(backtestSettings.startingCapital), riskPercent: Number(backtestSettings.riskPercent),
        lotSize: Math.max(1, selected.lotSize), tickSize: number(selected.tickSize) || 0.05,
        feeBpsPerSide: Number(backtestSettings.feeBpsPerSide), slippageTicks: Number(backtestSettings.slippageTicks),
      }));
      setWalkForwardResult(null);
    } catch (cause) { setBacktestResult(null); setWalkForwardResult(null); setBacktestError(cause instanceof Error ? cause.message : "Backtest could not run."); }
  }

  function runWalkForward() {
    if (!backtestBars || !selected) return;
    setBacktestError(""); setBacktestResult(null);
    try {
      setWalkForwardResult(runWalkForwardBacktest(backtestBars, {
        startingCapital: Number(backtestSettings.startingCapital), riskPercent: Number(backtestSettings.riskPercent),
        lotSize: Math.max(1, selected.lotSize), tickSize: number(selected.tickSize) || 0.05,
        feeBpsPerSide: Number(backtestSettings.feeBpsPerSide), slippageTicks: Number(backtestSettings.slippageTicks),
      }));
    } catch (cause) { setWalkForwardResult(null); setBacktestError(cause instanceof Error ? cause.message : "Walk-forward replay could not run."); }
  }

  function exportBacktest() {
    if (!backtestResult && !walkForwardResult) return;
    const blob = new Blob([JSON.stringify({ exportedAt: new Date().toISOString(), instrument: selected?.symbol, sourceFile: backtestFileName, result: backtestResult, walkForward: walkForwardResult }, null, 2)], { type: "application/json" });
    const href = URL.createObjectURL(blob); const link = document.createElement("a"); link.href = href; link.download = "algotrade-backtest.json";
    link.click(); window.setTimeout(() => URL.revokeObjectURL(href), 1000);
  }

  function clearBacktest() { setBacktestBars(null); setBacktestResult(null); setWalkForwardResult(null); setBacktestFileName(""); setBacktestError(""); }

  async function importTradebookCsv(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.currentTarget.files?.[0]; event.currentTarget.value = "";
    if (!file) return;
    setTradebookError(""); setTradebookReport(null); setTradebookFileName("");
    if (!tradebookAcknowledged) { setTradebookError("Confirm data-use rights before choosing a file."); return; }
    if (file.size > 5 * 1024 * 1024) { setTradebookError("Tradebook CSV exceeds the 5 MB limit."); return; }
    try {
      const fills = parseTradebookCsv(await file.text());
      setTradebookReport(calculateFifoPnl(fills)); setTradebookFileName(file.name.slice(0, 120));
    } catch (cause) { setTradebookReport(null); setTradebookError(cause instanceof Error ? cause.message : "Tradebook could not be read."); }
  }

  function exportTradebookReport() {
    if (!tradebookReport) return;
    const csv = buildTradebookExportCsv(tradebookReport);
    const blob = new Blob(["\uFEFF", csv], { type: "text/csv;charset=utf-8" });
    const href = URL.createObjectURL(blob); const link = document.createElement("a"); link.href = href; link.download = "algotrade-reference-pnl.csv";
    link.click(); window.setTimeout(() => URL.revokeObjectURL(href), 1000);
  }

  function clearTradebook() { setTradebookReport(null); setTradebookFileName(""); setTradebookError(""); }

  function exportJournal() {
    if (!session) return;
    const blob = new Blob([JSON.stringify({ exportedAt: new Date().toISOString(), userId: session.userId, entries: journal }, null, 2)], { type: "application/json" });
    const href = URL.createObjectURL(blob); const link = document.createElement("a"); link.href = href; link.download = "algotrade-journal.json";
    link.click(); URL.revokeObjectURL(href);
  }

  async function addAlert(event: React.FormEvent) {
    event.preventDefault();
    const lowerPrice = number(alertPrice);
    const upperPrice = number(alertUpperPrice);
    const volumeMultiplier = number(alertVolumeMultiplier);
    if (!selected || (alertRuleType !== "VOLUME_SPIKE" && !(lowerPrice > 0)) || alertBusy
        || (alertRuleType === "ZONE_TOUCH" && !(upperPrice > lowerPrice))
        || (alertRuleType === "VOLUME_SPIKE" && (volumeMultiplier < 1.1 || volumeMultiplier > 10))) return;
    setAlertBusy(true);
    try {
      const rule = alertRuleType === "VOLUME_SPIKE"
        ? await api.createVolumeSpikeAlert(selected.id, alertVolumeMultiplier)
        : alertRuleType === "ZONE_TOUCH"
          ? await api.createZoneTouchAlert(selected.id, alertPrice, alertUpperPrice)
          : await api.createAlert(selected.id, alertDirection, alertPrice);
      if (!isPriceAlert(rule)) throw new Error(t.errorGeneric);
      setAlerts((current) => [rule, ...current].slice(0, 100));
      setAlertPrice(""); setAlertUpperPrice(""); setAlertVolumeMultiplier("2.00"); showToast(t.newAlert);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t.errorGeneric);
    } finally { setAlertBusy(false); }
  }

  async function removeAlert(id: string) {
    if (alertBusy) return;
    setAlertBusy(true);
    try {
      await api.deleteAlert(id);
      setAlerts((current) => current.filter((value) => value.id !== id));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t.errorGeneric);
    } finally { setAlertBusy(false); }
  }

  async function markAlertRead(event: AlertEvent) {
    if (event.readAt || alertBusy) return;
    setAlertBusy(true);
    try {
      const updated = await api.markAlertEventRead(event.id);
      setAlertEvents((current) => current.map((value) => value.id === updated.id ? updated : value));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t.errorGeneric);
    } finally { setAlertBusy(false); }
  }

  if (!sessionReady) return <div className="auth-loading">{t.loading}</div>;
  if (!session) return <main className={`auth-screen ${compact ? "is-compact" : ""}`}>
    <div className="auth-art"><div className="brand-mark"><span className="brand-glyph">A</span><span>AlgoTrade<span className="brand-light"> Hub</span></span></div>
      <div className="auth-copy"><span className="eyebrow">{t.authEyebrow}</span><h1>{t.authTitleOne}<br/><em>{t.authTitleTwo}</em></h1>
        <p>{t.authDescription}</p>
        <div className="auth-mini-stats"><div><strong>01</strong><span>{t.authPrivateAccount}</span></div><div><strong>₹</strong><span>{t.authRiskFirst}</span></div><div><strong>MOCK</strong><span>{t.authPracticeMode}</span></div></div></div>
      <div className="auth-orbit orbit-one"/><div className="auth-orbit orbit-two"/><div className="auth-bottom">{t.authFooter}</div>
    </div>
    <section className="auth-panel"><div className="auth-top"><span className="secure-badge"><Icon name="shield" size={15}/> {t.privateWorkspace}</span><LanguageToggle language={language} setLanguage={setLanguage} label={t.languageLabel}/></div>
      <form className="auth-form" onSubmit={(event) => void authenticate(event)}><div className="form-kicker">{authMode === "login" ? t.welcomeBack : t.getStarted}</div>
        <h2>{authMode === "login" ? t.signIn : t.createAccount}</h2><p>{t.localOnly}</p>
        <label>{t.email}<input autoComplete="email" type="email" value={email} onChange={(event) => setEmail(event.target.value)} required maxLength={320} placeholder="you@example.com"/></label>
        <label>{t.password}<input autoComplete={authMode === "login" ? "current-password" : "new-password"} type="password" value={password} onChange={(event) => setPassword(event.target.value)} required minLength={12} maxLength={72} placeholder={t.passwordHint}/></label>
        {authError && <div className="inline-error" role="alert">{authError}</div>}
        <button className="button button-primary auth-submit" disabled={authBusy}>{authBusy ? t.loading : authMode === "login" ? t.signInButton : t.createButton}<Icon name="arrow"/></button>
        <button className="auth-switch" type="button" onClick={() => { setAuthMode(authMode === "login" ? "signup" : "login"); setAuthError(""); }}>{authMode === "login" ? t.switchSignup : t.switchLogin}</button>
      </form><p className="auth-legal">{t.notAdvisory}</p>
    </section>
  </main>;

  const filtered = instruments.filter((item) => `${item.exchange} ${item.symbol} ${item.segment}`.toLowerCase().includes(query.toLowerCase()));
  return <div className={`app-shell ${compact ? "is-compact" : ""}`}>
    <aside className="sidebar">
      <div className="brand"><span className="brand-glyph">A</span><span>AlgoTrade<span className="brand-light"> Hub</span></span></div>
      <div className="workspace-pill"><span className="pulse-dot"/><span>{t.demo}</span><span className="workspace-chevron">⌄</span></div>
      <span className="side-caption">{t.workspace}</span>
      <nav className="main-nav" aria-label={t.mainNavigation}>
        <button className={tab === "overview" ? "active" : ""} onClick={() => setTab("overview")}><Icon name="grid"/>{t.overview}</button>
        <button className={tab === "scanner" ? "active" : ""} onClick={() => setTab("scanner")}><Icon name="search"/>{t.scanner}</button>
        <button className={tab === "backtest" ? "active" : ""} onClick={() => setTab("backtest")}><Icon name="chart"/>{t.backtest}</button>
        <button className={tab === "reports" ? "active" : ""} onClick={() => setTab("reports")}><Icon name="book"/>{t.reports}</button>
        <button className={tab === "journal" ? "active" : ""} onClick={() => setTab("journal")}><Icon name="book"/>{t.journal}<span className="nav-count">{journal.length || ""}</span></button>
        <button className={tab === "risk" ? "active" : ""} onClick={() => setTab("risk")}><Icon name="shield"/>{t.risk}</button>
      </nav>
      <div className="watchlist-section"><div className="section-line"><span>{t.watchlist}</span><span className="market-indicator"><i/>MOCK</span></div>
        <div className="search-box"><Icon name="search" size={16}/><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder={t.search}/></div>
        <div className="watchlist-items">{filtered.map((item) => {
          const quote = quotes.find((value) => value.instrumentId === item.id);
          return <button className={`watch-item ${selected?.id === item.id ? "selected" : ""}`} key={item.id} onClick={() => setSelectedId(item.id)}>
            <span className={`exchange-tile ${item.exchange === "BSE" ? "bse" : ""}`}>{item.exchange}</span><span className="watch-symbol"><b>{item.symbol.replace("DEMO-", "")}</b><small>{item.segment} · {item.instrumentType}</small></span>
            <span className="watch-price">{formatMoney(number(quote?.price))}<small>SIM</small></span>
          </button>;
        })}{filtered.length === 0 && <div className="empty-mini">{t.noInstrumentsFound}</div>}</div>
      </div>
      <div className="sidebar-bottom"><div className="feed-mini"><div className="feed-mini-icon"><Icon name="bell" size={16}/></div><div><b>{t.feedHealth}</b><span>{dataStatus?.licensedSourceConfigured ? t.feedConnected : t.feedUnavailable}</span></div></div>
        <div className="profile-row"><span className="avatar">{session.userId.slice(0, 2).toUpperCase()}</span><span className="profile-name"><b>{t.myWorkspace}</b><small>{t.authPrivateAccount}</small></span><button className="icon-button logout-button" aria-label={t.logout} title={t.logout} onClick={() => void logout()}>↗</button></div></div>
    </aside>

    <main className="main-area">
      <header className="topbar"><div className="breadcrumbs"><span>{t.workspace}</span><b>/</b><strong>{t[tab]}</strong></div>
          <div className="topbar-actions"><div className={`connection-chip ${connected ? "connected" : ""}`}><i/>{connected ? t.connected : t.disconnected}</div><LanguageToggle language={language} setLanguage={setLanguage} label={t.languageLabel}/>
          <button className="icon-button refresh-button" disabled={loading} onClick={() => void load()} title={t.refresh} aria-label={t.refresh}><Icon name="refresh"/></button><button className="logout-top" onClick={() => void logout()}>{t.logout}</button></div></header>
      {compact && <nav className="compact-tab-nav" aria-label={t.workspaceViews}>{([["overview", "grid"], ["scanner", "search"], ["backtest", "chart"], ["reports", "book"], ["journal", "book"], ["risk", "shield"]] as const).map(([key, icon]) => <button type="button" key={key} className={tab === key ? "active" : ""} aria-pressed={tab === key} onClick={() => setTab(key)}><Icon name={icon} size={14}/><span>{t[key]}</span></button>)}</nav>}

      {pendingRecovery.kind === "pending" && <div className="global-error" role="alert">
        <span>{t.orderStatusUnknown} <code>{pendingRecovery.request.clientOrderId.slice(0, 8)}</code></span>
        <div className="recovery-actions"><button disabled={orderBusy} onClick={() => void checkPendingOrder()}>{t.checkStatus}</button>
          <button disabled={orderBusy} onClick={() => void retryPendingOrder()}>{t.retrySameOrder}</button></div>
      </div>}
      {pendingRecovery.kind === "blocked" && <div className="global-error" role="alert">
        <span>{pendingRecovery.reason === "invalid" ? t.invalidPendingOrder : t.pendingStorageUnavailable}</span>
        {pendingRecovery.reason === "invalid" && <div className="recovery-actions"><button onClick={acknowledgeInvalidRecovery}>{t.acknowledgeOrdersReviewed}</button></div>}
      </div>}
      {error && <div className="global-error" role="alert"><span>{error}</span><button onClick={() => setError("")} aria-label={t.dismiss}>×</button></div>}
      {tab === "overview" && <>
        <section className="page-heading"><div><div className="eyebrow">{new Intl.DateTimeFormat(language === "hi" ? "hi-IN" : "en-IN", { weekday: "long", day: "2-digit", month: "long", year: "numeric", timeZone: "Asia/Kolkata" }).format(new Date()).toUpperCase()} <span className="heading-dot">·</span> {t.india}</div><h1>{t.dashboard}<span className="paper-chip"><i/> {t.paperMock}</span></h1><p>{t.dashboardSummary}</p></div>
          <div className="heading-meta"><span className="clock-icon">◷</span><div><b>09:15 – 15:30 IST</b><small>{t.sessionReference}</small></div></div></section>
        <section className="status-strip"><div className="status-icon"><Icon name="shield" size={18}/></div><div><b>{t.feedUnavailable}</b><span>{dataStatus?.licensedSourceConfigured ? t.feedConnected : t.syntheticDemo}</span></div><div className="status-tags"><span className="tag tag-amber">{t.noLiveData}</span><span className="tag tag-slate">{t.mockOrdersTag}</span></div></section>
        <div className="dashboard-grid">
          <section className="panel chart-panel"><div className="panel-heading"><div><div className="panel-kicker">{t.selected}</div><h2>{selected?.symbol.replace("DEMO-", "") ?? "—"}<span className="exchange-label">{selected?.exchange} · {selected?.segment}</span></h2></div>
            <div className="chart-price"><strong>{formatMoney(price)}</strong><span className="sim-label"><i/>{t.simulated}</span></div></div>
            <div className="chart-toolbar"><div className="chart-tabs"><button className="chart-tab active">1m</button><button className="chart-tab" disabled title={t.chartTimeframeReason}>5m</button><button className="chart-tab" disabled title={t.chartTimeframeReason}>15m</button><button className="chart-tab" disabled title={t.chartTimeframeReason}>1h</button></div>
              <div className="chart-legend"><span><i className="legend-up"/>{t.chartUp}</span><span><i className="legend-down"/>{t.chartDown}</span><span className="read-only-label">{t.readOnly}</span></div></div>
            <div className="chart-container">{series?.candles?.length ? <CandleChart candles={series.candles} label={t.simulatedCandlestickChart} locale={t.dateLocale}/> : <div className="chart-empty"><div className="empty-chart-icon">⌁</div><b>{t.noChart}</b><span>{t.fixture}</span></div>}</div>
            <div className="chart-footer"><span className="fixture-dot"/>{t.fixture}<span className="chart-footer-right">{series?.candles?.length ?? 0} {t.bars} <b>·</b> {t.utcToIst}</span></div>
          </section>

          <section className="panel analysis-panel"><div className="panel-title-row"><div><div className="panel-kicker">{t.ruleBasedContext}</div><h2>{t.noSignal}</h2></div><div className="analysis-status"><span/><b>{analysis?.source === "SYNTHETIC_DEMO" ? t.simulated : analysis?.signalStatus === "AVAILABLE" ? analysis.signalStatus : t.withheld}</b></div></div>
            <div className="analysis-empty"><div className="lock-orb"><Icon name="shield" size={22}/></div>
              {analysisLoading ? <><b>{t.analysisLoading}</b><p>{t.indicatorSummary}</p><span>{t.waitingLicensedData}</span></> : analysisFailed ? <><b>{t.analysisUnavailable}</b><p>{t.indicatorSummary}</p><button className="text-button" onClick={() => setAnalysisRequestNonce((value) => value + 1)}>{t.analysisRetry}</button></> : analysis ? <><b>{(language === "hi" ? analysis.summaryHindi : analysis.summaryEnglish).join(" ") || t.noSignalBody}</b><p>{t.indicatorSummary}</p><span>{analysis.source === "SYNTHETIC_DEMO" ? t.analysisWithheld : analysis.freshness}</span><small>{t.analysisTime}: {new Intl.DateTimeFormat(language === "hi" ? "hi-IN" : "en-IN", { hour: "2-digit", minute: "2-digit", timeZone: "Asia/Kolkata" }).format(new Date(analysis.dataTimestamp))} IST · {analysis.algorithmVersion}</small></> : <><b>{t.noSignalBody}</b><p>{t.indicatorSummary}</p><span>{t.waitingLicensedData}</span></>}</div>
            <div className="analysis-metrics"><div><span>{t.analysisBars}</span><b>{analysis?.candleCount ?? "—"}</b></div><div><span>{t.analysisFreshness}</span><b>{analysis?.freshness ?? "—"}</b></div><div><span>{t.analysisSignal}</span><b>{analysis?.signalStatus ?? "—"}</b></div></div>
            <div className="analysis-indicator-title" id="analysis-indicator-heading">{t.analysisIndicatorTitle}</div>
            <dl className="analysis-indicator-grid" aria-labelledby="analysis-indicator-heading">
              {[
                ["EMA 20", analysis?.indicators.ema20], ["EMA 50", analysis?.indicators.ema50],
                ["EMA 200", analysis?.indicators.ema200], ["RSI 14", analysis?.indicators.rsi14],
                ["MACD", analysis?.indicators.macd], ["MACD SIGNAL", analysis?.indicators.macdSignal],
                ["MACD HIST", analysis?.indicators.macdHistogram], ["ADX 14", analysis?.indicators.adx14],
                ["ATR 14", analysis?.indicators.atr14], ["VOL AVG 20", analysis?.indicators.volumeAverage20],
                ["VOL RATIO", analysis?.indicators.latestVolumeRatio],
              ].map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{formatIndicator(value as string | number | null, label === "MACD" || label === "MACD SIGNAL" || label === "MACD HIST" ? 4 : 2)}{label === "VOL RATIO" && value != null ? "×" : ""}</dd></div>)}
            </dl>
            <p className="analysis-indicator-note">{t.analysisIndicatorWarmup}</p>
            <div className="analysis-foot"><span className="lock-small">◈</span> {t.notAdvisory}</div>
          </section>

          <section className="panel sizing-panel"><div className="panel-heading compact-heading"><div><div className="panel-kicker">{t.riskFirst}</div><h2>{t.suggestedSize}</h2></div><span className="calculator-chip">{t.calculator}</span></div>
            <div className="sizing-fields"><label>{t.capital}<span className="input-prefix">₹<input inputMode="decimal" value={capital} onChange={(event) => setCapital(event.target.value)} aria-label={t.capital}/></span></label>
              <label>{t.riskPercent}<span className="input-suffix"><input inputMode="decimal" value={riskPercent} onChange={(event) => setRiskPercent(event.target.value)} aria-label={t.riskPercent}/><i>%</i></span></label>
              <label>{t.entry}<span className="input-prefix">₹<input inputMode="decimal" value={entryPrice} onChange={(event) => setEntryPrice(event.target.value)} placeholder={price ? price.toFixed(2) : "0.00"}/></span></label>
              <label>{t.protectiveStop}<span className="input-prefix">₹<input inputMode="decimal" value={stopPrice} onChange={(event) => setStopPrice(event.target.value)} placeholder={price ? (price * 0.99).toFixed(2) : "0.00"}/></span></label></div>
            <div className="size-result"><div><span>{t.size}</span><b>{recommended || 0}<small> {selected?.segment === "FNO" ? `· ${lotSize} ${t.lot}` : t.units}</small></b></div><div><span>{t.sizeRisk}</span><b>{formatMoney(calculatedRisk)}</b></div><button className="round-info" title={t.sizeDisclaimer}>i</button></div>
            <div className="calculator-foot"><span>{t.sizeDisclaimer}</span></div>
          </section>

          <section className="panel order-panel"><div className="panel-heading compact-heading"><div><div className="panel-kicker">{t.available}</div><h2>{t.paperOrder}</h2></div><span className="mock-only-pill"><i/>{t.mockOnly}</span></div>
            {selected ? <div className="ticket-preview"><div className="ticket-instrument"><span className="exchange-tile">{selected.exchange}</span><div><b>{selected.symbol.replace("DEMO-", "")}</b><small>{selected.segment} · {t.lotSize} {selected.lotSize} · {t.tickSize} ₹{selected.tickSize}</small></div><strong>{formatMoney(price)}</strong></div>
              <div className="ticket-riskline"><span>{t.suggestedQuantity} <b>{recommended || 0}</b></span><span>{t.estimatedRiskShort} <b>{formatMoney(calculatedRisk)}</b></span></div>
              <div className="side-buttons"><button className="button buy-button" disabled={!recommended || killSwitch.active || !connected || pendingRecovery.kind !== "none"} onClick={() => openTicket("BUY")}><span>↗</span>{t.buy}</button><button className="button sell-button" disabled={!recommended || killSwitch.active || !connected || pendingRecovery.kind !== "none"} onClick={() => openTicket("SELL")}><span>↘</span>{t.sell}</button></div>
              <p className="ticket-note"><Icon name="shield" size={14}/>{t.orderWarning}</p></div> : <div className="empty-mini">{t.loading}</div>}
            {killSwitch.active && <div className="kill-inline">{t.killOn}</div>}
          </section>

          <section className="panel orders-panel"><div className="panel-heading compact-heading"><div><div className="panel-kicker">{t.paperTrading}</div><h2>{t.orders}</h2></div><span className="count-pill">{orders.length} {t.total}</span></div>
            <p className="orders-scope-note">{t.mockFillsSimulated}</p>
            {orders.length === 0 ? <div className="orders-empty"><div className="empty-order-icon">⇄</div><b>{t.noOrders}</b></div> : <div className="table-wrap"><table><thead><tr><th>{t.instrument}</th><th>{t.side}</th><th>{t.quantity}</th><th>{t.status}</th><th/></tr></thead><tbody>{orders.slice(0, 6).map((order) => <tr key={order.id}><td><b>{instruments.find((x) => x.id === order.instrumentId)?.symbol.replace("DEMO-", "") ?? "DEMO"}</b><small>{order.mode} · {formatDateTimeIst(order.createdAt, t.dateLocale)} IST</small></td><td><span className={`side-label ${order.side.toLowerCase()}`}>{t[order.side.toLowerCase()]}</span></td><td>{order.quantity}</td><td><span className={`order-status ${order.status.toLowerCase()}`}>{translateOrderStatus(order.status, t)}</span></td><td>{order.status === "OPEN" && <button className="text-button" onClick={() => void cancelOrder(order)}>{t.cancelOrder}</button>}</td></tr>)}</tbody></table></div>}
          </section>

          <section className="panel alerts-panel"><div className="panel-heading compact-heading"><div><div className="panel-kicker">{t.serverManagedAlerts}</div><h2>{t.alerts}</h2></div><div className="alert-bell"><Icon name="bell"/></div></div>
            <form className="alert-form" onSubmit={(event) => void addAlert(event)}><div className="alert-selects"><label>{t.alertRuleType}<select value={alertRuleType} onChange={(event) => setAlertRuleType(event.target.value as AlertRuleType)}><option value="PRICE_CROSSING">{t.priceCrossing}</option><option value="ZONE_TOUCH">{t.zoneTouch}</option><option value="VOLUME_SPIKE">{t.volumeSpike}</option></select></label>{alertRuleType === "PRICE_CROSSING" ? <><label>{t.alertDirection}<select value={alertDirection} onChange={(event) => setAlertDirection(event.target.value as "ABOVE" | "BELOW")}><option value="ABOVE">{t.above}</option><option value="BELOW">{t.below}</option></select></label><label>{t.alertPrice}<span className="input-prefix">₹<input inputMode="decimal" value={alertPrice} onChange={(event) => setAlertPrice(event.target.value)} required placeholder="0.00"/></span></label></> : alertRuleType === "ZONE_TOUCH" ? <><label>{t.alertLower}<span className="input-prefix">₹<input inputMode="decimal" value={alertPrice} onChange={(event) => setAlertPrice(event.target.value)} required placeholder="0.00"/></span></label><label>{t.alertUpper}<span className="input-prefix">₹<input inputMode="decimal" value={alertUpperPrice} onChange={(event) => setAlertUpperPrice(event.target.value)} required placeholder="0.00"/></span></label></> : <label>{t.volumeMultiplier}<span className="input-prefix"><input type="number" min="1.1" max="10" step="0.01" inputMode="decimal" value={alertVolumeMultiplier} onChange={(event) => setAlertVolumeMultiplier(event.target.value)} required/></span></label>}</div><button className="button button-outline" disabled={!selected || alertBusy || !alertReady || (alertRuleType === "ZONE_TOUCH" && !(number(alertUpperPrice) > number(alertPrice))) || (alertRuleType === "VOLUME_SPIKE" && (number(alertVolumeMultiplier) < 1.1 || number(alertVolumeMultiplier) > 10))} aria-busy={alertBusy}>{t.addAlert}<Icon name="plus" size={16}/></button></form>
            <div className="alert-list">{!alertReady ? <div className="alerts-empty">{t.loading}</div> : alerts.length ? alerts.slice(0, 3).map((alert) => <div className="alert-row" key={alert.id}><span className="alert-condition"><b>{instruments.find((item) => item.id === alert.instrumentId)?.symbol.replace("DEMO-", "") ?? "Instrument"}</b><small>{alert.ruleType === "VOLUME_SPIKE" ? `${t.volumeSpike} · ${number(alert.volumeMultiplier).toFixed(2)}× / ${alert.lookbackCandles ?? 20}` : alert.ruleType === "ZONE_TOUCH" ? `${t.zoneTouch} · ${formatMoney(number(alert.price))}–${formatMoney(number(alert.upperPrice))}` : `${alert.direction === "ABOVE" ? "↑" : "↓"} ${formatMoney(number(alert.price))}`}</small></span><span className="paused-status">{alertStateLabel(alert, t)}</span><button className="remove-alert" disabled={alertBusy} onClick={() => void removeAlert(alert.id)} aria-label={t.removeAlert}>×</button></div>) : <div className="alerts-empty">{t.noAlerts}<small>{t.alertPaused}</small></div>}</div>
            <div className="alert-event-list"><b>{t.alertEvents}</b>{alertEvents.length ? alertEvents.slice(0, 3).map((event) => <div className={`alert-event-row ${event.readAt ? "is-read" : ""}`} key={event.id}><span><strong>{instruments.find((item) => item.id === event.instrumentId)?.symbol.replace("DEMO-", "") ?? "Instrument"}</strong><small>{event.ruleType === "VOLUME_SPIKE" ? `${t.volumeAlert}: ${event.triggerVolume} / ${number(event.referenceAverageVolume).toFixed(2)} (${number(event.volumeMultiplier).toFixed(2)}×)` : event.ruleType === "ZONE_TOUCH" ? `${t.zoneTouch} · ${formatMoney(number(event.thresholdPrice))}–${formatMoney(number(event.upperPrice))}` : `${event.direction === "ABOVE" ? "↑" : "↓"} ${formatMoney(number(event.thresholdPrice))}`} · ${formatDateTimeIst(event.createdAt, t.dateLocale)} IST</small></span>{!event.readAt && <button className="text-button" disabled={alertBusy} onClick={() => void markAlertRead(event)}>{t.dismiss}</button>}</div>) : <small>{t.noAlertEvents}</small>}</div>
          </section>
        </div>
      </>}

      {tab === "backtest" && <BacktestView t={t} selected={selected} fileName={backtestFileName} bars={backtestBars} result={backtestResult} walkForward={walkForwardResult}
        acknowledged={backtestAcknowledged} setAcknowledged={(value) => { setBacktestAcknowledged(value); if (!value) clearBacktest(); }} settings={backtestSettings}
        setSettings={(key, value) => { setBacktestSettings((current) => ({ ...current, [key]: value })); setBacktestResult(null); setWalkForwardResult(null); }}
        error={backtestError} onImport={importBacktestCsv} onRun={runImportedBacktest} onWalkForward={runWalkForward} onClear={clearBacktest} onExport={exportBacktest} />}
      {tab === "scanner" && <ScannerView t={t} dataStatus={dataStatus} />}
      {tab === "reports" && <ReportsView t={t} fileName={tradebookFileName} report={tradebookReport} acknowledged={tradebookAcknowledged}
        setAcknowledged={(value) => { setTradebookAcknowledged(value); if (!value) clearTradebook(); }} error={tradebookError} onImport={importTradebookCsv} onClear={clearTradebook} onExport={exportTradebookReport} onPrint={() => window.print()} />}
      {tab === "journal" && <JournalView t={t} journal={journal} note={journalNote} setNote={setJournalNote} setup={journalSetup} setSetup={setJournalSetup}
        emotion={journalEmotion} setEmotion={setJournalEmotion} result={journalResult} setResult={setJournalResult} draft={journalDraft} setDraft={setJournalDraft}
        onSave={saveJournal} onExport={exportJournal} onDelete={() => { if (window.confirm(t.delete)) setJournal([]); }} />}
      {tab === "risk" && <RiskView t={t} policy={policy} draft={limitsDraft} setDraft={setLimitsDraft} onSave={saveLimits} busy={policyBusy} killSwitch={killSwitch} toggleKill={() => void toggleKillSwitch()} retryKill={() => void retryKillSwitchCancellation()} killBusy={killSwitchBusy} />}
    </main>

    {draft && <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget && !orderBusy) setDraft(null); }}><section ref={orderDialogRef} className="order-modal" role="dialog" aria-modal="true" aria-labelledby="order-title" aria-describedby="order-disclaimer">
      <div className="modal-header"><div><div className="panel-kicker">{t.simulatedLocalOnly}</div><h2 id="order-title">{t.paperOrder}</h2></div><div className="modal-header-actions"><button type="button" className="order-view-toggle" aria-expanded={advancedOrderDetails} aria-controls="order-advanced-details" onClick={() => setAdvancedOrderDetails((current) => !current)}>{advancedOrderDetails ? t.simpleView : t.advancedView}</button><button type="button" className="icon-button" disabled={orderBusy} onClick={() => { if (!orderBusyRef.current) { setDraft(null); setConfirmOrder(false); } }} aria-label={t.close}><Icon name="close"/></button></div></div>
      <section id="order-advanced-details" className="order-advanced-details" aria-labelledby="order-advanced-heading" hidden={!advancedOrderDetails}>
        <h3 id="order-advanced-heading">{t.advancedDetailsTitle}</h3>
        <dl className="order-advanced-grid">
          <div><dt>{t.account}</dt><dd>{t.myWorkspace} · {session.userId.slice(0, 8)}</dd></div>
          <div><dt>{t.instrument}</dt><dd>{selected?.symbol.replace("DEMO-", "") ?? "—"}</dd></div>
          <div><dt>{t.side}</dt><dd>{t[draft.side.toLowerCase()]}</dd></div>
          <div><dt>{t.orderTypeMode}</dt><dd>{t.marketMock}</dd></div>
          <div><dt>{t.referencePrice}</dt><dd>{formatMoney(price)}</dd></div>
          <div><dt>{t.quantity}</dt><dd>{draft.quantity}</dd></div>
          <div><dt>{t.protectiveStop}</dt><dd>{formatMoney(number(draft.stop))}</dd></div>
          <div><dt>{t.notional}</dt><dd>{formatMoney(number(draft.quantity) * price)}</dd></div>
          <div><dt>{t.approxRisk}</dt><dd>{formatMoney(number(draft.quantity) * Math.abs(price - number(draft.stop)))}</dd></div>
          <div><dt>{t.lotSize} / {t.tickSize}</dt><dd>{lotSize} / ₹{tick}</dd></div>
          <div><dt>{t.maxQty}</dt><dd>{policy.maxQuantity}</dd></div>
          <div><dt>{t.maxOrder}</dt><dd>{formatMoney(number(policy.maxOrderNotional))}</dd></div>
          <div><dt>{t.tradeLoss}</dt><dd>{formatMoney(number(policy.maxTradeLoss))}</dd></div>
        </dl>
        <p>{t.advancedDetailsNote}</p>
      </section>
      {!confirmOrder ? <><div className="order-summary-top"><span className={`side-label ${draft.side.toLowerCase()}`}>{t[draft.side.toLowerCase()]}</span><b>{selected?.symbol.replace("DEMO-", "")}</b><span className="mock-only-pill">{t.marketMock}</span></div>
        <div className="modal-fields"><label>{t.quantity}<input data-dialog-initial-focus type="number" min={lotSize} step={lotSize} value={draft.quantity} onChange={(event) => setDraft({ ...draft, quantity: event.target.value })}/></label><label>{t.protectiveStop}<input inputMode="decimal" value={draft.stop} step={selected?.tickSize} onChange={(event) => setDraft({ ...draft, stop: event.target.value })}/></label></div>
        <div className="confirmation-box"><div><span>{t.notional}</span><b>{formatMoney(number(draft.quantity) * price)}</b></div><div><span>{t.approxRisk}</span><b>{formatMoney(number(draft.quantity) * Math.abs(price - number(draft.stop)))}</b></div><p><Icon name="shield" size={15}/>{t.orderWarning}</p></div>
        <div className="pretrade-checklist"><b>{t.pretradeTitle}</b>
          {([["plan", t.checkPlan], ["stop", t.checkStop], ["risk", t.checkRisk], ["simulatedData", t.checkSimulated]] as const).map(([key, label]) =>
            <label key={key}><input type="checkbox" checked={pretradeChecks[key]} onChange={(event) => setPretradeChecks((current) => ({ ...current, [key]: event.target.checked }))}/><span>{label}</span></label>)}
          {!Object.values(pretradeChecks).every(Boolean) && <small>{t.checklistRequired}</small>}
        </div>
        <div className="modal-actions"><button className="button button-outline" onClick={() => setDraft(null)}>{t.cancel}</button><button className="button button-primary" disabled={!Number(draft.quantity) || killSwitch.active || !Object.values(pretradeChecks).every(Boolean)} onClick={() => setConfirmOrder(true)}>{t.submit}<Icon name="arrow" size={16}/></button></div></>
        : <><div className="final-confirm"><span className="confirm-mark"><Icon name="shield" size={23}/></span><h3 ref={confirmHeadingRef} tabIndex={-1}>{t.confirm}</h3><p>{t[draft.side.toLowerCase()]} {draft.quantity} {selected?.symbol.replace("DEMO-", "")} {t.marketReference} {formatMoney(price)}.</p><div><span>{t.account}</span><b>{t.myWorkspace} · {session.userId.slice(0, 8)}</b></div><div><span>{t.orderTypeMode}</span><b>{t.marketMock}</b></div><div><span>{t.notional}</span><b>{formatMoney(number(draft.quantity) * price)}</b></div><div><span>{t.protectiveStop}</span><b>{formatMoney(number(draft.stop))}</b></div><div><span>{t.approxRisk}</span><b>{formatMoney(number(draft.quantity) * Math.abs(price - number(draft.stop)))}</b></div><strong>{t.orderWarning}</strong></div><div className="modal-actions"><button className="button button-outline" disabled={orderBusy} onClick={() => setConfirmOrder(false)}>{t.cancel}</button><button className="button button-primary" disabled={orderBusy || pendingRecovery.kind !== "none" || !Object.values(pretradeChecks).every(Boolean)} onClick={() => void submitOrder()}>{orderBusy ? t.loading : t.confirm}<Icon name="arrow" size={16}/></button></div></>}
      <p id="order-disclaimer" className="modal-disclaimer">{t.notAdvisory}</p>
      <p className="pretrade-unavailable" role="note">{t.pretradeUnavailable}</p>
    </section></div>}
    {toast && <div className="toast-message" role="status"><span>✓</span>{toast}</div>}
  </div>;
}

function LanguageToggle({ language, setLanguage, label }: { language: "en" | "hi"; setLanguage: (language: "en" | "hi") => void; label: string }) {
  return <div className="language-toggle" role="group" aria-label={label}><button type="button" lang="en" aria-label="English" aria-pressed={language === "en"} className={language === "en" ? "active" : ""} onClick={() => setLanguage("en")}>EN</button><span/><button type="button" lang="hi" aria-label="Hindi" aria-pressed={language === "hi"} className={language === "hi" ? "active" : ""} onClick={() => setLanguage("hi")}>हि</button></div>;
}

function ScannerView({ t, dataStatus }: { t: Record<string, string>; dataStatus: DemoStatus | null }) {
  return <section className="page-section"><div className="page-heading"><div><div className="eyebrow">{t.scanEyebrow}</div><h1>{t.scanner}</h1><p>{t.scanDescription}</p></div><span className="mock-only-pill">{t.noLiveData}</span></div>
    <div className="scanner-layout"><section className="panel scanner-status"><div className="scanner-lock"><Icon name="shield" size={25}/></div><span className="scanner-kicker">{t.scannerStatus}</span><h2>{t.scannerUnavailable}</h2>
      <p>{getScannerStatusMessage(dataStatus, t.scanNoProvider, t.scanStatusUnavailable)}</p>
      <div className="scanner-gates"><div><span>{t.freshMultiTimeframe}</span><b>{t.unavailable}</b></div><div><span>{t.instrumentRights}</span><b>{t.notVerified}</b></div><div><span>{t.backendScanRoute}</span><b>{t.notConnected}</b></div></div>
      <small>{t.mockExcluded}</small></section>
      <section className="panel scanner-rules"><div className="panel-kicker">{t.plannedNotEvaluated}</div><h2>{t.analysisFilters}</h2>
        <article><span className="scanner-rule-icon">↗</span><div><b>{t.trendAlignment}</b><small>{t.trendEvidence}</small></div><em>{t.notEvaluated}</em></article>
        <article><span className="scanner-rule-icon">⌁</span><div><b>{t.freshZone}</b><small>{t.zoneEvidence}</small></div><em>{t.notEvaluated}</em></article>
        <article><span className="scanner-rule-icon">▥</span><div><b>{t.relativeVolumeQuality}</b><small>{t.volumeEvidence}</small></div><em>{t.notEvaluated}</em></article>
        <div className="scanner-disclaimer">{t.scannerDisclaimer}</div>
      </section></div>
  </section>;
}

function BacktestView({ t, selected, fileName, bars, result, walkForward, acknowledged, setAcknowledged, settings, setSettings, error, onImport, onRun, onWalkForward, onClear, onExport }: {
  t: Record<string, string>; selected: Instrument | null; fileName: string; bars: BacktestBar[] | null; result: BacktestResult | null; walkForward: WalkForwardResult | null;
  acknowledged: boolean; setAcknowledged: (value: boolean) => void; settings: BacktestSettings;
  setSettings: (key: keyof BacktestSettings, value: string) => void; error: string;
  onImport: (event: React.ChangeEvent<HTMLInputElement>) => void; onRun: () => void; onWalkForward: () => void; onClear: () => void; onExport: () => void;
}) {
  const update = (key: keyof BacktestSettings, value: string) => setSettings(key, value);
  return <section className="page-section"><div className="page-heading"><div><div className="eyebrow">{t.historicalLocalOnly}</div><h1>{t.backtest}</h1><p>{t.backtestDescription}</p></div>
    {(result || walkForward) && <button className="button button-outline" onClick={onExport}><Icon name="download" size={16}/>{t.exportBacktest}</button>}</div>
    <div className="backtest-layout"><section className="panel backtest-import"><div className="panel-kicker">{t.inputAssumptions}</div><h2>{selected?.symbol ?? t.selectInstrument}</h2>
      <p className="backtest-description">{t.backtestDataHandling}</p>
      <label className="rights-check"><input type="checkbox" checked={acknowledged} onChange={(event) => setAcknowledged(event.target.checked)}/><span>{t.rightsAcknowledgement}</span></label>
      <label className="file-picker"><span>{t.chooseCsv}</span><input type="file" accept=".csv,text/csv" disabled={!acknowledged || !selected} onChange={onImport}/></label>
      <div className="csv-constraints"><b>{t.csvColumns}</b><code>timestamp, open, high, low, close, volume</code><span>{t.csvLimits}</span></div>
      <div className="backtest-assumption-row"><span>{t.tickSizeLabel} <b>₹{selected?.tickSize ?? "—"}</b></span><span>{t.lotSizeLabel} <b>{selected?.lotSize ?? "—"}</b></span><span>{t.barsLoaded} <b>{bars?.length.toLocaleString() ?? 0}</b></span></div>
      {fileName && <div className="loaded-file"><span>✓ {fileName}</span><button className="text-button danger-text" onClick={onClear}>{t.clearBacktest}</button></div>}
      <div className="backtest-settings"><label>{t.startingCapital}<input type="number" min="1" max="1000000000" step="1000" value={settings.startingCapital} onChange={(event) => update("startingCapital", event.target.value)}/></label>
        <label>{t.riskPercent}<input type="number" min="0.01" max="5" step="0.01" value={settings.riskPercent} onChange={(event) => update("riskPercent", event.target.value)}/></label>
        <label>{t.estimatedFee}<input type="number" min="0" max="100" step="0.1" value={settings.feeBpsPerSide} onChange={(event) => update("feeBpsPerSide", event.target.value)}/></label>
        <label>{t.slippage}<input type="number" min="0" max="50" step="1" value={settings.slippageTicks} onChange={(event) => update("slippageTicks", event.target.value)}/></label></div>
      <div className="backtest-actions"><button className="button button-primary" disabled={!bars || !acknowledged} onClick={onRun}>{t.runBacktest}<Icon name="arrow" size={16}/></button>
        <button className="button button-outline" disabled={!bars || !acknowledged} onClick={onWalkForward}>{t.walkForward}</button>{bars && <button className="button button-outline" onClick={onClear}>{t.clearBacktest}</button>}</div>
      {error && <div className="backtest-error" role="alert">{error}</div>}
      <div className="journal-local-note">{t.educationalSimulation}</div>
    </section>
    <section className="panel backtest-results"><div className="panel-kicker">{t.strategyLongOnly}</div><h2>{result || walkForward ? t.simulationResults : t.readyWhenYouAre}</h2>
      {!result && !walkForward ? <div className="backtest-empty"><div className="empty-chart-icon">⌁</div><b>{t.noBacktest}</b><span>{t.nextBarEntry}</span></div> : result ? <>
        <div className="backtest-metrics"><div><span>{t.netPnl}</span><b className={result.netPnl >= 0 ? "metric-positive" : "metric-negative"}>{formatMoney(result.netPnl)}</b></div><div><span>{t.returnLabel}</span><b className={result.returnPercent >= 0 ? "metric-positive" : "metric-negative"}>{result.returnPercent.toFixed(2)}%</b></div><div><span>{t.endingCapital}</span><b>{formatMoney(result.endingCapital)}</b></div><div><span>{t.maxDrawdown}</span><b className="metric-negative">{formatMoney(result.maxDrawdownRupees)} · {result.maxDrawdownPercent.toFixed(2)}%</b></div><div><span>{t.tradesWinRate}</span><b>{result.trades.length} · {result.winRatePercent.toFixed(1)}%</b></div><div><span>{t.estimatedFees}</span><b>{formatMoney(result.totalFees)}</b></div><div><span>{t.expectancyPerTrade}</span><b>{formatMoney(result.expectancy)}</b></div><div><span>{t.profitFactor}</span><b>{result.profitFactor === null ? (result.trades.length && result.positiveTradeNetPnl > 0 ? t.noLosingTrades : "—") : result.profitFactor.toFixed(2)}</b></div><div><span>{t.maxExposure}</span><b>{formatMoney(result.maxExposureRupees)}</b></div><div><span>{t.dailySharpe}</span><b>{result.dailySharpeRatio === null ? "—" : result.dailySharpeRatio.toFixed(2)}</b></div></div>
        <div className="equity-chart"><div className="equity-chart-head"><b>{t.realizedEquityCurve}</b><span>{result.barsUsed.toLocaleString()} {t.barsUnit} · {result.strategyVersion}</span></div><EquityChart result={result} label={t.simulatedEquityCurve} locale={t.dateLocale}/></div>
        {result.trades.length > 0 ? <div className="backtest-trades"><div className="equity-chart-head"><b>{t.recentSimulatedTrades}</b><span>{t.showingUpTo50}</span></div><div className="table-wrap"><table><thead><tr><th>{t.entryLabel}</th><th>{t.exitLabel}</th><th>{t.quantityLabel}</th><th>{t.exitReason}</th><th>{t.netPnl}</th></tr></thead><tbody>{result.trades.slice(-50).reverse().map((trade, index) => <tr key={`${trade.entryTime}-${index}`}><td>{chartTimeIst(trade.entryTime, t.dateLocale)} IST</td><td>{chartTimeIst(trade.exitTime, t.dateLocale)} IST</td><td>{trade.quantity}</td><td>{trade.exitReason.replaceAll("_", " ")}</td><td className={trade.netPnl >= 0 ? "metric-positive" : "metric-negative"}>{formatMoney(trade.netPnl)}</td></tr>)}</tbody></table></div></div> : <div className="no-trades">{t.noQualifyingEntry}</div>}
        <ul className="backtest-warnings">{result.warnings.map((warning) => <li key={warning}>{warning}</li>)}</ul>
      </> : walkForward ? <><div className="backtest-description">{walkForward.method}</div><div className="walk-forward-grid">{walkForward.windows.map((window) => <article key={window.window}>
        <div><b>{t.windowLabel} {window.window}</b><span>{chartTimeIst(window.start, t.dateLocale)}–{chartTimeIst(window.end, t.dateLocale)} IST</span></div><strong className={window.result.netPnl >= 0 ? "metric-positive" : "metric-negative"}>{formatMoney(window.result.netPnl)}</strong>
        <small>{window.result.trades.length} {t.tradesUnit} · {window.result.returnPercent.toFixed(2)}% · DD {window.result.maxDrawdownPercent.toFixed(2)}%</small>
      </article>)}</div><ul className="backtest-warnings"><li>{t.walkForwardWindowStats}</li><li>{t.walkForwardCaveat}</li></ul></> : null}
    </section></div>
    <PaperReplayPanel t={t} selected={selected} bars={bars} acknowledged={acknowledged} sourceFile={fileName}
      feeBpsPerSide={settings.feeBpsPerSide} slippageTicks={settings.slippageTicks}/>
  </section>;
}

function PaperReplayPanel({ t, selected, bars, acknowledged, sourceFile, feeBpsPerSide, slippageTicks }: {
  t: Record<string, string>; selected: Instrument | null; bars: BacktestBar[] | null; acknowledged: boolean; sourceFile: string;
  feeBpsPerSide: string; slippageTicks: string;
}) {
  const [side, setSide] = useState<ReplaySide>("BUY");
  const [type, setType] = useState<ReplayOrderType>("MARKET");
  const [quantity, setQuantity] = useState("1");
  const [submissionBar, setSubmissionBar] = useState("1");
  const [limitPrice, setLimitPrice] = useState("");
  const [stopPrice, setStopPrice] = useState("");
  const [timeInForce, setTimeInForce] = useState<"DAY" | "GTC">("DAY");
  const [cancelBar, setCancelBar] = useState("");
  const [latencyBars, setLatencyBars] = useState("0");
  const [participationPercent, setParticipationPercent] = useState("10");
  const [spreadTicks, setSpreadTicks] = useState("1");
  const [maxWaitBars, setMaxWaitBars] = useState("5");
  const [orders, setOrders] = useState<ReplayOrder[]>([]);
  const [result, setResult] = useState<PaperReplayResult | null>(null);
  const [accounting, setAccounting] = useState<PaperReplayAccounting | null>(null);
  const [error, setError] = useState("");
  const lotSize = Math.max(1, selected?.lotSize ?? 1);
  const tickSize = number(selected?.tickSize) || 0.05;

  useEffect(() => {
    setOrders([]); setResult(null); setAccounting(null); setError(""); setQuantity(String(lotSize));
  }, [bars, lotSize, selected?.symbol]);
  useEffect(() => { setResult(null); setAccounting(null); }, [feeBpsPerSide, slippageTicks, latencyBars, participationPercent, spreadTicks, maxWaitBars]);

  function stageOrder(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError(""); setResult(null); setAccounting(null);
    if (!bars || !selected || !acknowledged) { setError(t.replayNoBars); return; }
    if (orders.length >= 50) { setError(t.replayTooMany); return; }
    const requestedQuantity = Number(quantity), signalBar = Number(submissionBar), cancelAt = cancelBar ? Number(cancelBar) : undefined;
    if (!Number.isSafeInteger(requestedQuantity) || requestedQuantity < lotSize || requestedQuantity % lotSize !== 0 ||
      !Number.isSafeInteger(signalBar) || signalBar < 1 || signalBar > bars.length ||
      (cancelAt !== undefined && (!Number.isSafeInteger(cancelAt) || cancelAt < signalBar || cancelAt > bars.length))) {
      setError(t.replayInvalid); return;
    }
    const order: ReplayOrder = {
      id: crypto.randomUUID(), side, type, quantity: requestedQuantity, submittedAtIndex: signalBar - 1, timeInForce,
      ...(type === "LIMIT" ? { limitPrice: Number(limitPrice) } : {}),
      ...(type === "STOP_MARKET" ? { stopPrice: Number(stopPrice) } : {}),
      ...(cancelAt !== undefined ? { cancelRequestedAtIndex: cancelAt - 1 } : {}),
    };
    setOrders((current) => [...current, order]);
  }

  function runReplay() {
    if (!bars || !selected || !acknowledged || orders.length === 0) return;
    setError("");
    try {
      const replay = runPaperReplay(bars, orders, {
        tickSize, lotSize, feeBpsPerSide: Number(feeBpsPerSide), spreadTicksPerSide: Number(spreadTicks),
        slippageTicks: Number(slippageTicks), participationPercent: Number(participationPercent),
        latencyBars: Number(latencyBars), maxWaitBars: Number(maxWaitBars),
      });
      setResult(replay);
      setAccounting(calculatePaperReplayAccounting(replay, bars, selectedReplaySegment(selected), selected.symbol));
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : "";
      setResult(null); setAccounting(null); setError(message.includes("safe computation limit") ? t.replayWorkCap : message.includes("50,000-fill accounting limit") ? t.replayFillCap : message || t.replayInvalid);
    }
  }

  function clearReplay() { setOrders([]); setResult(null); setAccounting(null); setError(""); }
  function exportReplay() {
    if (!result) return;
    const blob = new Blob([JSON.stringify({ exportedAt: new Date().toISOString(), instrument: selected?.symbol, sourceFile, orders, result, accounting }, null, 2)], { type: "application/json" });
    const href = URL.createObjectURL(blob), link = document.createElement("a");
    link.href = href; link.download = "algotrade-paper-replay.json"; link.click(); window.setTimeout(() => URL.revokeObjectURL(href), 1000);
  }

  const statusLabel = (status: ReplayOrderStatus) => t[`replayStatus${status === "PARTIALLY_FILLED" ? "Partial" : status[0] + status.slice(1).toLowerCase()}`];
  return <section className="panel paper-replay-panel" aria-labelledby="paper-replay-heading">
    <div className="paper-replay-heading"><div><div className="panel-kicker">{t.paperReplayTitle}</div><h2 id="paper-replay-heading">{t.replayResultsTitle}</h2><p>{t.paperReplayDescription}</p></div>
      {result && <button className="button button-outline" onClick={exportReplay}>{t.replayExport}</button>}</div>
    <div className="paper-replay-disclosure" role="note">{t.paperReplayDisclosure}</div>
    {!bars || !acknowledged ? <div className="backtest-empty paper-replay-empty"><b>{t.replayNoBars}</b></div> : <>
      <form className="paper-replay-form" onSubmit={stageOrder}>
        <label>{t.replaySide}<select value={side} onChange={(event) => setSide(event.target.value as ReplaySide)}><option value="BUY">{t.buy}</option><option value="SELL">{t.sell}</option></select></label>
        <label>{t.replayType}<select value={type} onChange={(event) => setType(event.target.value as ReplayOrderType)}><option value="MARKET">{t.replayMarket}</option><option value="LIMIT">{t.replayLimit}</option><option value="STOP_MARKET">{t.replayStopMarket}</option></select></label>
        <label>{t.replayQuantity}<input type="number" min={lotSize} step={lotSize} value={quantity} onChange={(event) => setQuantity(event.target.value)} required/></label>
        <label>{t.replaySubmitBar}<input type="number" min="1" max={bars.length} value={submissionBar} onChange={(event) => setSubmissionBar(event.target.value)} required/><small>{bars.length.toLocaleString()} {t.barsUnit}</small></label>
        {type === "LIMIT" && <label>{t.replayLimitPrice}<input type="number" min={tickSize} step={tickSize} value={limitPrice} onChange={(event) => setLimitPrice(event.target.value)} required/></label>}
        {type === "STOP_MARKET" && <label>{t.replayStopPrice}<input type="number" min={tickSize} step={tickSize} value={stopPrice} onChange={(event) => setStopPrice(event.target.value)} required/></label>}
        <label>{t.replayTimeInForce}<select value={timeInForce} onChange={(event) => setTimeInForce(event.target.value as "DAY" | "GTC")}><option value="DAY">{t.replayDay}</option><option value="GTC">{t.replayGtc}</option></select></label>
        <label>{t.replayCancelBar}<input type="number" min={submissionBar} max={bars.length} value={cancelBar} onChange={(event) => setCancelBar(event.target.value)}/></label>
        <label>{t.replayLatency}<input type="number" min="0" max="100" step="1" value={latencyBars} onChange={(event) => setLatencyBars(event.target.value)} required/></label>
        <label>{t.replayParticipation}<input type="number" min="0.01" max="100" step="0.1" value={participationPercent} onChange={(event) => setParticipationPercent(event.target.value)} required/></label>
        <label>{t.replaySpread}<input type="number" min="0" max="100" step="1" value={spreadTicks} onChange={(event) => setSpreadTicks(event.target.value)} required/></label>
        <label>{t.replayMaxWait}<input type="number" min="1" max="10000" step="1" value={maxWaitBars} onChange={(event) => setMaxWaitBars(event.target.value)} required/></label>
        <div className="paper-replay-actions"><button className="button button-outline" type="submit">{t.replayAddOrder}</button><button className="button button-primary" type="button" disabled={orders.length === 0} onClick={runReplay}>{t.replayRun}</button>{orders.length > 0 && <button className="button button-outline" type="button" onClick={clearReplay}>{t.replayClear}</button>}</div>
      </form>
      {error && <div className="backtest-error" role="alert">{error}</div>}
      <div className="paper-replay-data-note">{t.educationalSimulation} · {selected?.symbol} · {bars.length.toLocaleString()} {t.barsUnit} · tick ₹{tickSize} · lot {lotSize}</div>
      <div className="paper-replay-table-block"><div className="equity-chart-head"><b>{t.replayOrdersTitle}</b><span>{orders.length}/50</span></div>
        {orders.length === 0 ? <div className="no-trades">{t.replayNoOrders}</div> : <div className="table-wrap"><table><thead><tr><th>{t.replayOrderId}</th><th>{t.replaySide}</th><th>{t.replayType}</th><th>{t.replayQuantity}</th><th>{t.replaySubmitBar}</th><th>{t.replayTimeInForce}</th><th>{t.replayRemove}</th></tr></thead><tbody>{orders.map((order) => <tr key={order.id}><td><code>{order.id.slice(0, 8)}</code></td><td>{order.side}</td><td>{order.type.replaceAll("_", " ")}</td><td>{order.quantity}</td><td>{order.submittedAtIndex + 1}</td><td>{order.timeInForce ?? "DAY"}</td><td><button className="text-button danger-text" aria-label={t.replayRemove} onClick={() => { setOrders((current) => current.filter((item) => item.id !== order.id)); setResult(null); setAccounting(null); }}>{t.replayRemove}</button></td></tr>)}</tbody></table></div>}
      </div>
      {result && <div className="paper-replay-table-block paper-replay-result" aria-live="polite"><div className="equity-chart-head"><b>{t.replayResultsTitle}</b><span>{result.orders.length} · {result.fills.length} {t.replayFillsTitle.toLowerCase()} · {t.replayFees} {formatMoney(result.totalFees)}</span></div>
        <div className="table-wrap"><table><thead><tr><th>{t.replayOrderId}</th><th>{t.replayEligibleBar}</th><th>{t.replayStatus}</th><th>{t.replayFilledQty}</th><th>{t.replayRemainingQty}</th><th>{t.replayAveragePrice}</th><th>{t.replayFees}</th><th>{t.replayReason}</th></tr></thead><tbody>{result.orders.map((order) => <tr key={order.id}><td><code>{order.id.slice(0, 8)}</code></td><td>{order.eligibleAtIndex + 1}</td><td>{statusLabel(order.status)}</td><td>{order.filledQuantity}</td><td>{order.remainingQuantity}</td><td>{order.averageFillPrice === null ? "—" : formatMoney(order.averageFillPrice)}</td><td>{formatMoney(order.totalFees)}</td><td title={order.reason ?? ""}>{localReplayReason(order.reason, t)}</td></tr>)}</tbody></table></div>
        <div className="paper-replay-table-block"><div className="equity-chart-head"><b>{t.replayFillsTitle}</b><span>{t.replayShowingFills}</span></div>{result.fills.length === 0 ? <div className="no-trades">{t.replayNoFills}</div> : <div className="table-wrap"><table><thead><tr><th>{t.replayTime}</th><th>{t.replayOrderId}</th><th>{t.replaySide}</th><th>{t.replayFilledQty}</th><th>{t.replayFillPrice}</th><th>{t.replayFees}</th><th>{t.replayReason}</th></tr></thead><tbody>{result.fills.slice(-100).reverse().map((fill) => <tr key={`${fill.orderId}-${fill.sequence}`}><td>{formatDateTimeIst(fill.time, t.dateLocale)}</td><td><code>{fill.orderId.slice(0, 8)}</code></td><td>{fill.side}</td><td>{fill.quantity}</td><td>{formatMoney(fill.price)}</td><td>{formatMoney(fill.fee)}</td><td>{t[`replay${fill.reason === "MARKET_OPEN" ? "MarketOpen" : fill.reason === "LIMIT_TOUCH" ? "LimitTouch" : "StopTrigger"}`]}</td></tr>)}</tbody></table></div>}</div>
        {accounting && <section className="paper-accounting" aria-label={t.paperPnlTitle}>
          <div className="equity-chart-head"><b>{t.paperPnlTitle}</b><span>{accounting.segment} · {t.paperMark} {formatDateTimeIst(accounting.markedAt, t.dateLocale)}</span></div>
          <div className="backtest-metrics"><div><span>{t.paperRealizedGross}</span><b>{formatMoney(accounting.realizedGrossPnl)}</b></div><div><span>{t.paperRealizedNet}</span><b className={accounting.realizedNetPnl >= 0 ? "metric-positive" : "metric-negative"}>{formatMoney(accounting.realizedNetPnl)}</b></div><div><span>{t.paperRealizedCharges}</span><b>{formatMoney(accounting.realizedCharges)}</b></div><div><span>{t.paperOpenNet}</span><b className={accounting.netUnrealizedPnl >= 0 ? "metric-positive" : "metric-negative"}>{formatMoney(accounting.netUnrealizedPnl)}</b></div><div><span>{t.paperCombinedNet}</span><b className={accounting.combinedNetPnl >= 0 ? "metric-positive" : "metric-negative"}>{formatMoney(accounting.combinedNetPnl)}</b></div><div><span>{t.paperOpenQuantity}</span><b>{accounting.openPositions.reduce((sum, position) => sum + position.quantity, 0)}</b></div><div><span>{t.replayFees}</span><b>{formatMoney(accounting.totalEstimatedFees)}</b></div></div>
          <div className="paper-pnl-disclosure">{t.paperPnlDisclosure}</div>
          <div className="paper-replay-table-block"><div className="equity-chart-head"><b>{t.paperOpenPositions}</b><span>{accounting.openPositions.length}</span></div>{accounting.openPositions.length === 0 ? <div className="no-trades">{t.paperNoOpenPosition}</div> : <div className="table-wrap"><table><thead><tr><th>{t.paperDirection}</th><th>{t.paperOpenQuantity}</th><th>{t.paperAverageEntry}</th><th>{t.paperMarkPrice}</th><th>{t.paperUnrealizedGross}</th><th>{t.paperEntryFees}</th><th>{t.paperUnrealizedNet}</th></tr></thead><tbody>{accounting.openPositions.map((position, index) => <tr key={`${position.direction}-${index}`}><td>{position.direction === "LONG" ? t.paperOpenLong : t.paperOpenShort}</td><td>{position.quantity}</td><td>{formatMoney(position.averageEntryPrice)}</td><td>{formatMoney(position.markPrice)}</td><td>{formatMoney(position.grossUnrealizedPnl)}</td><td>{formatMoney(position.unallocatedEntryFees)}</td><td className={position.netUnrealizedPnl >= 0 ? "metric-positive" : "metric-negative"}>{formatMoney(position.netUnrealizedPnl)}</td></tr>)}</tbody></table></div>}</div>
          <div className="paper-replay-table-block"><div className="equity-chart-head"><b>{t.paperRealizedMatches}</b><span>{accounting.realizedMatches.length}</span></div>{accounting.realizedMatches.length === 0 ? <div className="no-trades">{t.paperNoRealizedMatches}</div> : <div className="table-wrap"><table><thead><tr><th>{t.paperDirection}</th><th>{t.paperOpenQuantity}</th><th>{t.entryLabel}</th><th>{t.exitLabel}</th><th>{t.paperRealizedCharges}</th><th>{t.netPnl}</th></tr></thead><tbody>{accounting.realizedMatches.slice(-100).reverse().map((match, index) => <tr key={`${match.entryTradeId}-${match.exitTradeId}-${index}`}><td>{match.direction === "LONG" ? t.paperOpenLong : t.paperOpenShort}</td><td>{match.quantity}</td><td>{formatDateTimeIst(match.entryTime, t.dateLocale)} · {formatMoney(match.entryPrice)}</td><td>{formatDateTimeIst(match.exitTime, t.dateLocale)} · {formatMoney(match.exitPrice)}</td><td>{formatMoney(match.charges)}</td><td className={match.netPnl >= 0 ? "metric-positive" : "metric-negative"}>{formatMoney(match.netPnl)}</td></tr>)}</tbody></table></div>}</div>
        </section>}
      </div>}
    </>}
  </section>;
}

function localReplayReason(reason: string | undefined, t: Record<string, string>) {
  if (!reason) return "—";
  const localized: Record<string, string> = {
    "Cancel request became effective before this bar opened.": t.replayCancelReason,
    "Maximum eligible-bar wait was reached.": t.replayWaitReason,
    "DAY order expired at the next session boundary.": t.replayDayReason,
    "Historical replay ended before the remaining quantity could fill.": t.replayEndReason,
    "Execution price was invalid or would violate the limit price.": t.replayPriceReason,
    "Unsupported order side.": t.replaySideReason,
    "Unsupported order type.": t.replayTypeReason,
    "Quantity must be a positive whole number of lots within the simulation limit.": t.replayQuantityReason,
    "Submission bar is outside the replay history.": t.replaySubmissionReason,
    "Unsupported time in force.": t.replayTifReason,
    "Cancel request bar must be within the history and no earlier than submission.": t.replayCancelBarReason,
    "Limit order needs a positive tick-aligned limit price.": t.replayLimitReason,
    "Stop order needs a positive tick-aligned stop price.": t.replayStopReason,
    "Fill notional exceeds the ₹1,000,000,000 simulation accounting limit.": t.replayNotionalReason,
    "Estimated fill fees exceed the ₹1,000,000 simulation accounting limit.": t.replayFeeReason,
  };
  return localized[reason] ?? reason;
}

function selectedReplaySegment(instrument: Instrument): TradeSegment {
  const type = `${instrument.segment} ${instrument.instrumentType}`.toUpperCase();
  return /(FNO|NFO|FUT|OPT)/.test(type) ? "FNO" : "CASH_INTRADAY";
}

function EquityChart({ result, label, locale }: { result: BacktestResult; label: string; locale: string }) {
  const points = result.equityCurve;
  const values = points.map((point) => point.equity);
  const low = Math.min(...values), high = Math.max(...values), spread = high - low || 1;
  const coords = points.map((point, index) => `${(index / Math.max(1, points.length - 1)) * 780 + 10},${210 - ((point.equity - low) / spread) * 180}`).join(" ");
  return <svg className="equity-svg" viewBox="0 0 800 230" preserveAspectRatio="none" role="img" aria-label={label}>
    {[0, 1, 2, 3].map((line) => <line key={line} x1="8" x2="792" y1={20 + line * 60} y2={20 + line * 60} className="equity-gridline"/>)}
    <polyline points={coords} fill="none" stroke={result.netPnl >= 0 ? "#2b9b75" : "#cb606c"} strokeWidth="2.5" strokeLinejoin="round" strokeLinecap="round"/>
    <text x="10" y="226" className="chart-axis-label">{chartTimeIst(points[0]?.time ?? "", locale)} IST</text><text x="790" y="226" textAnchor="end" className="chart-axis-label">{chartTimeIst(points.at(-1)?.time ?? "", locale)} IST</text>
  </svg>;
}

function ReportsView({ t, fileName, report, acknowledged, setAcknowledged, error, onImport, onClear, onExport, onPrint }: {
  t: Record<string, string>; fileName: string; report: TradebookReport | null; acknowledged: boolean;
  setAcknowledged: (value: boolean) => void; error: string; onImport: (event: React.ChangeEvent<HTMLInputElement>) => void;
  onClear: () => void; onExport: () => void; onPrint: () => void;
}) {
  return <section className="page-section"><div className="page-heading"><div><div className="eyebrow">{t.executedFillsLocal}</div><h1>{t.reports}</h1><p>{t.reportDescription}</p></div>
    {report && <div className="report-export-actions"><button className="button button-outline" onClick={onExport}><Icon name="download" size={16}/>{t.exportPnlCsv}</button><button className="button button-outline" onClick={onPrint}>{t.printSavePdf}</button></div>}</div>
    <div className="reports-layout"><section className="panel reports-import"><div className="panel-kicker">{t.privateFileImport}</div><h2>{t.tradebookSettings}</h2>
      <p className="backtest-description">{t.reportImportDescription}</p>
      <label className="rights-check"><input type="checkbox" checked={acknowledged} onChange={(event) => setAcknowledged(event.target.checked)}/><span>{t.tradebookRights}</span></label>
      <label className="file-picker"><span>{t.chooseTradebook}</span><input type="file" accept=".csv,text/csv" disabled={!acknowledged} onChange={onImport}/></label>
      <div className="csv-constraints"><b>{t.requiredNormalizedColumns}</b><code>trade_id, timestamp, segment, symbol, side, quantity, price, charges</code><span>{t.tradebookSegments}</span></div>
      <div className="report-data-note"><Icon name="shield" size={15}/><span>{t.reportDataHandling}</span></div>
      {fileName && <div className="loaded-file"><span>✓ {fileName}</span><button className="text-button danger-text" onClick={onClear}>{t.clearReport}</button></div>}
      {error && <div className="backtest-error" role="alert">{error}</div>}
      <div className="journal-local-note">{t.reportReference}. {t.fifoMethod}</div>
    </section>
    <section className="panel report-results"><div className="panel-kicker">{t.fifoBreakdown}</div><h2>{report ? t.realizedPnlSummary : t.noTradebookLoaded}</h2>
      {!report ? <div className="backtest-empty"><div className="empty-chart-icon">₹</div><b>{t.chooseTradebookMessage}</b></div> : <>
        <div className="backtest-metrics"><div><span>{t.realizedNetPnl}</span><b className={report.realizedNetPnl >= 0 ? "metric-positive" : "metric-negative"}>{formatMoney(report.realizedNetPnl)}</b></div><div><span>{t.realizedGrossPnl}</span><b>{formatMoney(report.realizedGrossPnl)}</b></div><div><span>{t.chargesMatchedQuantity}</span><b>{formatMoney(report.realizedCharges)}</b></div><div><span>{t.fillRowsMatches}</span><b>{report.fills} · {report.realizedMatches.length}</b></div><div><span>{t.openInventoryQuantity}</span><b>{report.openQuantity}</b></div><div><span>{t.openInventoryCharges}</span><b>{formatMoney(report.openCharges)}</b></div></div>
        <div className="report-table-block"><div className="equity-chart-head"><b>{t.bySegment}</b><span>{t.reportReference}</span></div><div className="table-wrap"><table><thead><tr><th>{t.segmentLabel}</th><th>{t.matchedQuantity}</th><th>{t.matchesLabel}</th><th>{t.grossPnl}</th><th>{t.chargesLabel}</th><th>{t.netPnl}</th></tr></thead><tbody>{report.bySegment.map((segment) => <tr key={segment.segment}><td>{segment.segment}</td><td>{segment.matchedQuantity}</td><td>{segment.matches}</td><td>{formatMoney(segment.grossPnl)}</td><td>{formatMoney(segment.charges)}</td><td className={segment.netPnl >= 0 ? "metric-positive" : "metric-negative"}>{formatMoney(segment.netPnl)}</td></tr>)}</tbody></table></div></div>
        {report.openInventory.length > 0 && <div className="report-table-block"><div className="equity-chart-head"><b>{t.unmatchedInventory}</b><span>{report.openInventory.length} {t.positionsLabel}</span></div><div className="table-wrap"><table><thead><tr><th>{t.segmentInstrument}</th><th>{t.directionLabel}</th><th>{t.openQuantity}</th><th>{t.averageEntry}</th><th>{t.unallocatedEntryCharges}</th></tr></thead><tbody>{report.openInventory.map((item, index) => <tr key={`${item.segment}-${item.symbol}-${item.direction}-${index}`}><td>{item.segment}<small>{item.symbol}</small></td><td>{item.direction}</td><td>{item.quantity}</td><td>{formatMoney(item.averageEntryPrice)}</td><td>{formatMoney(item.unallocatedEntryCharges)}</td></tr>)}</tbody></table></div></div>}
        {report.realizedMatches.length > 0 && <div className="report-table-block"><div className="equity-chart-head"><b>{t.recentFifoMatches}</b><span>{t.showingUpTo50}</span></div><div className="table-wrap"><table><thead><tr><th>{t.instrumentLabel}</th><th>{t.directionLabel}</th><th>{t.quantityLabel}</th><th>{t.entryToExit}</th><th>{t.chargesLabel}</th><th>{t.netPnl}</th></tr></thead><tbody>{report.realizedMatches.slice(-50).reverse().map((match, index) => <tr key={`${match.entryTradeId}-${match.exitTradeId}-${index}`}><td>{match.symbol}<small>{match.segment}</small></td><td>{match.direction}</td><td>{match.quantity}</td><td>{formatMoney(match.entryPrice)} → {formatMoney(match.exitPrice)}</td><td>{formatMoney(match.charges)}</td><td className={match.netPnl >= 0 ? "metric-positive" : "metric-negative"}>{formatMoney(match.netPnl)}</td></tr>)}</tbody></table></div></div>}
        <ul className="backtest-warnings">{report.warnings.map((warning) => <li key={warning}>{warning}</li>)}</ul>
      </>}
    </section></div>
  </section>;
}

function CandleChart({ candles, label, locale }: { candles: Candle[]; label: string; locale: string }) {
  const visible = candles.slice(-100);
  const values = visible.flatMap((item) => [number(item.high), number(item.low)]).filter(Number.isFinite);
  const floor = Math.min(...values), ceiling = Math.max(...values), range = Math.max(ceiling - floor, 0.05);
  const width = 860, height = 264, left = 54, right = 8, top = 12, bottom = 24;
  const plotWidth = width - left - right, plotHeight = height - top - bottom;
  const y = (value: number) => top + ((ceiling - value) / range) * plotHeight;
  const slot = plotWidth / visible.length;
  const candleWidth = Math.max(2, Math.min(7, slot * 0.55));
  return <svg className="candlestick-svg" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" role="img" aria-label={label}>
    {[0, 1, 2, 3].map((index) => { const value = ceiling - range * index / 3; const lineY = top + plotHeight * index / 3; return <g key={index}>
      <line x1={left} y1={lineY} x2={width - right} y2={lineY} className="chart-gridline"/>
      <text x={left - 9} y={lineY + 3} textAnchor="end" className="chart-axis-label">{value.toFixed(1)}</text>
    </g>; })}
    {visible.map((bar, index) => {
      const x = left + slot * index + slot / 2, open = number(bar.open), close = number(bar.close), up = close >= open;
      const color = up ? "#31c58b" : "#ef7181", bodyY = Math.min(y(open), y(close)), bodyHeight = Math.max(Math.abs(y(open) - y(close)), 1);
      return <g key={`${bar.time}-${index}`}>
        <line x1={x} x2={x} y1={y(number(bar.high))} y2={y(number(bar.low))} stroke={color} strokeWidth="1.25"/>
        <rect x={x - candleWidth / 2} y={bodyY} width={candleWidth} height={bodyHeight} rx="0.7" fill={color} opacity=".9"/>
      </g>;
    })}
    <text x={left} y={height - 4} className="chart-axis-label">{visible[0] ? chartTimeIst(visible[0].time, locale) : ""} IST</text>
    <text x={width - right} y={height - 4} textAnchor="end" className="chart-axis-label">{visible.at(-1) ? chartTimeIst(visible.at(-1)!.time, locale) : ""} IST</text>
  </svg>;
}

function chartTimeIst(timestamp: string, locale: string) {
  return new Intl.DateTimeFormat(locale, { hour: "2-digit", minute: "2-digit", timeZone: "Asia/Kolkata" }).format(new Date(timestamp));
}

function formatSessionDate(sessionDate: string, locale: string) {
  const date = new Date(`${sessionDate}T00:00:00+05:30`);
  if (!Number.isFinite(date.getTime())) return sessionDate;
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeZone: "Asia/Kolkata" }).format(date);
}

function JournalView({ t, journal, note, setNote, setup, setSetup, emotion, setEmotion, result, setResult, draft, setDraft, onSave, onExport, onDelete }: {
  t: Record<string, string>; journal: Entry[]; note: string; setNote: (value: string) => void; setup: string; setSetup: (value: string) => void;
  emotion: string; setEmotion: (value: string) => void; draft: JournalDraft; setDraft: React.Dispatch<React.SetStateAction<JournalDraft>>;
  result: string; setResult: (value: string) => void; onSave: (event: React.FormEvent) => void; onExport: () => void; onDelete: () => void;
}) {
  const updateDraft = <K extends keyof JournalDraft>(key: K, value: JournalDraft[K]) => setDraft((current) => ({ ...current, [key]: value }));
  return <section className="page-section"><div className="page-heading"><div><div className="eyebrow">{t.processPrivate}</div><h1>{t.journal}</h1><p>{t.journalDescription}</p></div><button className="button button-outline" onClick={onExport}><Icon name="download" size={16}/>{t.export}</button></div>
    <div className="journal-grid"><section className="panel journal-compose"><div className="panel-kicker">{t.postTradeReview}</div><h2>{t.recentActivity}</h2><form onSubmit={onSave}>
      <label>{t.setupTag}<input value={setup} onChange={(event) => setSetup(event.target.value)} placeholder={t.setupPlaceholder} maxLength={80}/></label>
      <label>{t.emotion}<select className="journal-select" value={emotion} onChange={(event) => setEmotion(event.target.value)}><option value="calm">{t.calm}</option><option value="confident">{t.confident}</option><option value="anxious">{t.anxious}</option><option value="fomo">{t.fomo}</option><option value="revenge">{t.revenge}</option><option value="bored">{t.bored}</option><option value="other">{t.other}</option></select></label>
      <label>{t.journalTradeTime}<input type="datetime-local" value={draft.tradeTime} onChange={(event) => updateDraft("tradeTime", event.target.value)}/><small>{t.journalTradeTimeHint}</small></label>
      <label>{t.journalQuantity}<input type="number" min="1" max="1000000000" step="1" value={draft.quantity} onChange={(event) => updateDraft("quantity", event.target.value)} inputMode="numeric"/></label>
      <div className="journal-field-grid"><label>{t.plannedStop}<input type="number" min="0.01" max="1000000000" step="any" value={draft.plannedStop} onChange={(event) => updateDraft("plannedStop", event.target.value)} inputMode="decimal"/></label>
        <label>{t.actualStop}<input type="number" min="0.01" max="1000000000" step="any" value={draft.actualStop} disabled={draft.stopRemoved} onChange={(event) => updateDraft("actualStop", event.target.value)} inputMode="decimal"/></label></div>
      <label className="journal-checkbox"><input type="checkbox" checked={draft.stopRemoved} onChange={(event) => {
        const removed = event.target.checked; updateDraft("stopRemoved", removed); if (removed) updateDraft("actualStop", "");
      }}/><span>{t.stopRemoved}</span></label>
      <div className="journal-field-grid"><label>{t.plannedTarget}<input type="number" min="0.01" max="1000000000" step="any" value={draft.plannedTarget} onChange={(event) => updateDraft("plannedTarget", event.target.value)} inputMode="decimal"/></label>
        <label>{t.actualTarget}<input type="number" min="0.01" max="1000000000" step="any" value={draft.actualTarget} onChange={(event) => updateDraft("actualTarget", event.target.value)} inputMode="decimal"/></label></div>
      <label>{t.resultRupees}<input type="number" min="-1000000000" max="1000000000" step="0.01" value={result} onChange={(event) => setResult(event.target.value)} inputMode="decimal" placeholder={t.optionalManualOnly}/></label>
      <label>{t.note}<textarea value={note} onChange={(event) => setNote(event.target.value)} maxLength={2000} rows={5} required placeholder={t.journalNotePlaceholder}/></label>
      <button className="button button-primary" disabled={!note.trim()}>{t.saveEntry}<Icon name="arrow" size={16}/></button></form><div className="journal-local-note">◈ {t.localJournal}</div></section>
      <section className="panel journal-list-panel"><div className="journal-list-head"><div><div className="panel-kicker">{journal.length} {t.entriesLabel}</div><h2>{t.recentActivity}</h2></div>{journal.length > 0 && <button className="text-button danger-text" onClick={onDelete}>{t.delete}</button>}</div>
        {journal.length === 0 ? <div className="journal-empty"><div className="empty-journal-icon"><Icon name="book" size={24}/></div><b>{t.noJournal}</b><span>{t.localJournal}</span></div> : <div className="journal-entries">{journal.map((entry) => <article className="journal-entry" key={entry.id}><div className="entry-meta"><span>{formatDateTimeIst(entry.tradeTime ?? entry.time, t.dateLocale)}</span><b>{entry.symbol}</b></div><h3>{entry.setup}</h3><div className="emotion-tag">{t.emotion}: <b>{entry.emotion ? t[entry.emotion] ?? entry.emotion : t.other}</b></div><p>{entry.note}</p>
          {(entry.quantity !== undefined || entry.plannedStop || entry.actualStop || entry.stopRemoved || entry.plannedTarget || entry.actualTarget) && <div className="journal-trade-details">
            {entry.quantity !== undefined && <span>{t.journalQuantity}: <b>{entry.quantity}</b></span>}
            {entry.plannedStop && <span>{t.plannedStop}: <b>{formatMoney(number(entry.plannedStop))}</b></span>}
            {entry.actualStop && <span>{t.actualStop}: <b>{formatMoney(number(entry.actualStop))}</b></span>}
            {entry.stopRemoved && <span className="metric-negative">{t.stopRemoved}</span>}
            {entry.plannedTarget && <span>{t.plannedTarget}: <b>{formatMoney(number(entry.plannedTarget))}</b></span>}
            {entry.actualTarget && <span>{t.actualTarget}: <b>{formatMoney(number(entry.actualTarget))}</b></span>}
          </div>}{entry.result && <div className="entry-result">{t.manualPnl} <b>{Number.isFinite(Number(entry.result)) && entry.result.trim() ? formatMoney(Number(entry.result)) : entry.result}</b></div>}</article>)}</div>}</section></div>
    <JournalInsightsView entries={journal} t={t}/>
    <div className="journal-privacy"><Icon name="shield" size={17}/> {t.journalPrivacy}</div>
  </section>;
}

function RiskView({ t, policy, draft, setDraft, onSave, busy, killSwitch, toggleKill, retryKill, killBusy }: {
  t: Record<string, string>; policy: RiskPolicy; draft: { maxQuantity: string; maxOrderNotional: string; maxDailyLoss: string; maxTradeLoss: string; maxOpenOrders: string; maxOrdersPerDay: string; noNewOrdersAfter: string; noOrdersFirstMinutes: string };
  setDraft: (value: { maxQuantity: string; maxOrderNotional: string; maxDailyLoss: string; maxTradeLoss: string; maxOpenOrders: string; maxOrdersPerDay: string; noNewOrdersAfter: string; noOrdersFirstMinutes: string }) => void;
  onSave: (event: React.FormEvent) => void; busy: boolean; killSwitch: KillSwitch; toggleKill: () => void; retryKill: () => void; killBusy: boolean;
}) {
  const set = (key: keyof typeof draft, value: string) => setDraft({ ...draft, [key]: value });
  const cutoffParts = policy.noNewOrdersAfter.slice(0, 5).split(":").map(Number);
  const noActiveEntryWindow = cutoffParts.length === 2 && cutoffParts.every(Number.isFinite)
    && cutoffParts[0] * 60 + cutoffParts[1] <= 555 + policy.noOrdersFirstMinutes;
  return <section className="page-section"><div className="page-heading"><div><div className="eyebrow">{t.guardrailsAccount}</div><h1>{t.risk}</h1><p>{t.riskDescription}</p></div><span className="mock-only-pill"><i/>{t.mockAccount}</span></div>
    <div className="risk-layout"><section className="panel risk-controls"><div className="panel-title-row"><div><div className="panel-kicker">{t.backendPolicy}</div><h2>{t.riskLimits}</h2></div><span className="policy-saved">{t.persisted}</span></div>
      <form className="limits-form" onSubmit={onSave}><label>{t.maxQty}<span><input type="number" min="1" max="10000" step="1" value={draft.maxQuantity} onChange={(event) => set("maxQuantity", event.target.value)} required/></span></label>
        <label>{t.maxOrder}<span className="input-prefix">₹<input inputMode="decimal" value={draft.maxOrderNotional} onChange={(event) => set("maxOrderNotional", event.target.value)} required/></span></label>
        <label>{t.dailyLoss}<span className="input-prefix">₹<input inputMode="decimal" value={draft.maxDailyLoss} onChange={(event) => set("maxDailyLoss", event.target.value)} required/></span></label>
        <label>{t.tradeLoss}<span className="input-prefix">₹<input inputMode="decimal" value={draft.maxTradeLoss} onChange={(event) => set("maxTradeLoss", event.target.value)} required/></span></label>
        <label>{t.maxOpenOrders}<span><input type="number" min="1" max="50" step="1" value={draft.maxOpenOrders} onChange={(event) => set("maxOpenOrders", event.target.value)} required/></span></label>
        <label>{t.maxOrdersPerDay}<span><input type="number" min="1" max="100" step="1" value={draft.maxOrdersPerDay} onChange={(event) => set("maxOrdersPerDay", event.target.value)} required/></span></label>
        <label>{t.cutoffTime}<span><input type="time" min="09:16" max="15:30" step="60" value={draft.noNewOrdersAfter} onChange={(event) => set("noNewOrdersAfter", event.target.value)} required/></span></label>
        <label>{t.openingCooldown}<span><input type="number" min="0" max="120" step="1" value={draft.noOrdersFirstMinutes} onChange={(event) => set("noOrdersFirstMinutes", event.target.value)} required/></span></label>
        <button className="button button-primary" disabled={busy}>{busy ? t.loading : t.saveLimits}<Icon name="arrow" size={16}/></button></form>
      <p className="risk-disclaimer daily-order-entry-note">{t.dailyOrderCount}: <b>{policy.acceptedOrderEntriesToday} / {policy.maxOrdersPerDay}</b>. {t.dailyOrderEntryNote}</p>
      {noActiveEntryWindow ? <div className="policy-pending" role="alert">{t.noEntryWindowWarning}</div> : null}
      {policy.pending?.effectiveOn ? <div className="policy-pending" role="status"><b>{t.pendingPolicy} {formatSessionDate(policy.pending.effectiveOn, t.dateLocale)}.</b><span>{t.pendingPolicyBody}</span>
        <small>{t.currentActiveLimits} {t.maxQty}: {policy.maxQuantity} · {t.maxOrder}: {formatMoney(number(policy.maxOrderNotional))} · {t.maxOpenOrders}: {policy.maxOpenOrders} · {t.dailyLoss}: {formatMoney(number(policy.maxDailyLoss))} · {t.tradeLoss}: {formatMoney(number(policy.maxTradeLoss))} · {t.maxOrdersPerDay}: {policy.maxOrdersPerDay} · {t.cutoffTime}: {policy.noNewOrdersAfter} · {t.openingCooldown}: {policy.noOrdersFirstMinutes}</small></div> : null}
      <div className="policy-foot">{t.policyUpdated} {policy.updatedAt ? formatDateTimeIst(policy.updatedAt, t.dateLocale) : t.withYourAccount}.</div></section>
      <section className="panel kill-panel"><div className="kill-emblem"><Icon name="shield" size={27}/></div><div className="panel-kicker">{t.emergencyControl}</div><h2>{t.killSwitch}</h2><p>{t.killSwitchDescription}</p>
        <div className={`kill-state ${killSwitch.active ? "on" : "off"}`}><i/>{killSwitch.active ? t.killOn : t.killOff}</div>
        <button className={`button ${killSwitch.active ? "button-resume" : "button-danger"}`} onClick={toggleKill} disabled={killBusy}>{killSwitch.active ? t.deactivateKill : t.activateKill}<Icon name="arrow" size={16}/></button>
        {killSwitch.active && killSwitch.unresolvedOrders > 0 && <button className="button button-outline kill-retry" onClick={retryKill} disabled={killBusy}>{t.retryKillCancellation}</button>}
        <div className="kill-counts"><span>{t.confirmedCancelled} <b>{killSwitch.confirmedCancelled}</b></span><span>{t.unresolvedOpen} <b>{killSwitch.unresolvedOrders}</b></span></div>
        <small className="risk-disclaimer">{t.mockControlDisclaimer}</small></section></div>
  </section>;
}

function decimalPlaces(value: string | number) { const text = String(value); const dot = text.indexOf("."); return dot < 0 ? 0 : text.length - dot - 1; }
function isTickAligned(value: number, tick: number) { if (!(value > 0) || !(tick > 0)) return false; const units = value / tick; return Math.abs(units - Math.round(units)) < 1e-7; }

