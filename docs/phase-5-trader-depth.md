# Phase 5 — Trader depth

The owner asked the project to continue phase by phase without routine approval pauses. Phase 5 is therefore being built with local-only, mock-safe workflows first. It adds no live broker call and no market-data provider.

## Phase scope and acceptance criteria

- **Richer backtesting:** report net trade expectancy, profit factor, max notional exposure, daily-return Sharpe with its formula, and a chronological walk-forward replay. Keep the fixed strategy and its version visible; never tune against the evaluation window. No results imply future performance.
- **Reference P&L report:** import a normalized executed-trade CSV in the browser, match lots FIFO by full symbol and segment, allocate user-supplied charges, and report realized P&L by segment. Show open inventory separately. Export summary/matches/open inventory to formula-safe CSV and support browser print-to-PDF. Do not calculate tax liability or file returns. Do not upload or persist the CSV.
- **Behavior review:** provide neutral weekly/monthly observations from the user's local journal: manually reported P&L by IST hour, weekday, setup and symbol/quantity; entries after a reported loss; same-symbol quantity increases after a reported loss; reported stop changes/removals; and days that meet the user's journal-entry threshold. Label every result self-reported; never use journal figures to change order permissions or call them broker P&L.
- **Mobile polish:** provide reachable navigation and responsive layouts for existing and new Phase 4/5 workflows at 360 px width; order summary and confirmation remain readable and mock-labeled. Verify visual layout where the local runtime is available.
- **Scanner readiness:** require fresh, rights-cleared multi-instrument data and the shared analysis engine. Until such data is available, show a clear unavailable state; do not scan synthetic fixtures or reimplement analysis rules in the browser.
- **Smart-order readiness:** only implement an order type after a verified broker capability and complete lifecycle support exist. Current mock orders remain single MARKET requests with a protective stop; this phase does not describe them as bracket/OCO/GTT orders.

## Offline P&L import contract

Canonical CSV columns: `trade_id,timestamp,segment,symbol,side,quantity,price,charges`. Supported segments are `CASH_INTRADAY`, `CASH_DELIVERY`, and `FNO`; `side` is `BUY` or `SELL`. Each CSV row represents one executed fill; `charges` is the user's total rupee charge for that fill, with at most two decimal places. Require unique trade IDs, explicit timezone or local IST timestamps, finite positive price and whole-unit quantity, non-negative charges, fill notional at or below ₹1,000,000,000, and a full instrument key that distinguishes expiries and option strikes. Files are capped at 5 MB and 50,000 rows.

FIFO matches opposing fills in timestamp order, then trade ID for equal timestamps. It handles a fill that closes one side and opens the other. Realized P&L includes only charges allocated to closed quantity; unmatched quantity and its remaining entry charges stay in the open-inventory section. CSV text fields are quoted and formula-prefixed when needed; numeric values remain numeric. Browser print-to-PDF contains only the P&L report. Results are historical arithmetic from the supplied file, not reconciled broker data or tax advice.

## Explicit exclusions

No licensed-data scanner, broker-native smart orders, broker fill reconciliation, statutory-charge schedule, income-tax calculation, order automation, cloud deployment, or live execution is included. Each remains gated by source rights, official capability evidence, security review, and the relevant operational approvals.

## Current implementation status

- Implemented: fixed-strategy performance metrics, three chronological anchored walk-forward windows with a fresh capital balance per window, and a fail-closed scanner information screen that does not scan synthetic data.
- Implemented: local normalized tradebook import, FIFO realized P&L by segment, charge allocation, long/short reversals, unmatched inventory reporting, formula-safe CSV export, and print-to-PDF layout.
- Implemented: local weekly, monthly, and all-entry behavior review with IST grouping and bounded, user-selected thresholds. It uses only manually entered journal values, keeps the report in the browser, and does not feed the risk manager. New journal fields capture trade time, quantity, planned/actual stop and target, and an explicit stop-removed acknowledgement; existing local journal entries remain readable.
- Implemented: 360 px responsive breakpoints, compact side-panel navigation for all tabs, visible sign-out and server-managed price-crossing/zone-touch alert rules (paused until licensed feed rights are confirmed), scroll-safe order review, dialog focus trap, Escape close, focus restoration, and confirmation-step focus announcement.
- Implemented: richer statistics, fixed-version anchored walk-forward windows, and fail-closed scanner state. No smart order is enabled because no broker capability or lifecycle evidence has been supplied.
- Verification: authenticated dashboard/trading views passed browser visual review at 360 px in Hindi, and sign-in/account-creation screens passed in English and Hindi. The simple/advanced order dialog fits; Escape and focus restoration work; required attestations remain unchecked and Review stays disabled. Still pending: browser mock-order submission/cancellation lifecycle, local-file import UI, Chrome extension runtime, and Docker/PostgreSQL/Redis verification. No order was submitted.
- The tradebook report is intentionally not an income-tax calculator. Statutory charge schedules, delivery tax-lot rules, corporate actions, broker reconciliation, and accountant-ready filing output remain unimplemented.
- Journal behavior observations depend on accurate manual entries; they are not reconciled with broker fills. Automatic trade capture, entry/exit screenshots, and analysis snapshots remain unavailable until an authoritative fill lifecycle and permitted data source exist.
