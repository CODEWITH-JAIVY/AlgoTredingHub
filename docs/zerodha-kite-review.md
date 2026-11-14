# Zerodha Kite Connect readiness review

**Reviewed: 22 October 2026.** This is a technical readiness note based on Zerodha/Kite's public documentation. It is not legal advice and does not grant API, exchange, or market-data rights.

## Decision

Zerodha remains a candidate for the first future broker adapter. The project is still mock-first: no Zerodha account, API key, API secret, access token, subscription, or broker request has been configured or used. Live order placement remains **0%** and disabled. The user selected synthetic/mock market data until a licensed source is confirmed.

Keep a future Kite adapter behind the existing `BrokerAdapter` boundary. Do not connect Kite's live feed to synthetic/mock-trading workflows or enable an order route just because API credentials become available. The account, applicable exchange approvals, provider terms, data-use permissions, network, secret storage, and recovery behavior must be resolved first.

## Authentication and credentials

- The developer account needs an API key, secret, and registered redirect URL. Login returns a short-lived, single-use request token; the backend exchanges it using SHA-256 of `api_key + request_token + api_secret`.
- Never put the API secret or access token in the website, Chrome extension, desktop webview, source control, `.env`, logs, or telemetry. Kite's API is not CORS-enabled; desktop/mobile login requires a remote backend to perform the handshake.
- The access token normally expires at 6 AM the following day. Refresh tokens are available only to certain approved platforms. Plan for an explicit broker login when a session expires; do not build an undocumented unattended refresh flow.
- A successful token exchange only establishes an API session. It does not establish that an account has the required exchange, segment, product, or order permissions.

References: [login and token exchange](https://kite.trade/docs/connect/v3/user/), [Kite API overview](https://kite.trade/docs/connect/v3/).

## Fees and market-data rights

- The current Personal plan is free for order, GTT, alerts, margin, and portfolio APIs, but includes no historical or real-time market data.
- The current Connect plan is ₹500 per app per month and includes WebSocket real-time data and historical candles. The pricing page currently describes an October 2026 brokerage-credit offer; do not rely on an offer or purchase a plan until the developer console confirms the current price and eligibility.
- A subscription does not automatically establish permission for every planned use. The Kite terms prohibit using APIs/live data for virtual or mock-trading apps and restrict public display/redistribution. Storage, derived analytics, backtests, and end-user display must be reviewed for the actual account and product use before enabling any such path.
- This project currently uses synthetic data. Keep it separate from any future Kite feed, and do not route real Kite data into demo/backtest/mock workflows without explicit written rights covering that use.

References: [current API pricing](https://support.zerodha.com/category/trading-and-markets/general-kite/kite-api/articles/what-are-the-charges-for-kite-apis), [Kite Connect terms](https://kite.trade/terms/).

## Market data and instrument identity

- The daily instrument CSV contains instrument token, exchange token, symbol, expiry, strike, tick size, lot size, instrument type, segment, and exchange. It is generated once per day; its `last_price` is not real-time. Persist a dated/versioned snapshot and never hard-code derivative tokens, expiry, lot size, or tick size.
- Quote snapshots accept up to 250 instruments per request. The documented limits are 1 quote request/second, 3 historical-candle requests/second, and 10 requests/second for other endpoints.
- A Kite WebSocket supports up to 3,000 instruments per connection and up to 3 connections per API key. Market packets are binary; the client must parse them, reconnect safely, detect stale/missing instruments, and recover subscriptions without treating connection health as proof of fresh ticks.
- Historical candle intervals listed are minute, 3, 5, 10, 15, 30 minutes, and daily; the official FAQ says weekly/monthly candle timeframes are unavailable. Continuous history is documented for NFO and MCX futures at daily resolution; expired contracts require instrument-token snapshots collected before expiry.
- The public API reference does not specify a guaranteed maximum historical range per request. Use configurable, conservative chunks, respect the 3 requests/second limit across all jobs, checkpoint idempotently, and confirm provider limits before bulk import. Do not invent a supported span from forum posts.
- Treat omitted quote keys, expired/unknown tokens, empty responses, stale timestamps, session gaps, and reconnect backfills as explicit missing/stale states. Never substitute synthetic prices into a provider response.

References: [market data and instruments](https://kite.trade/docs/connect/v3/market-data-and-instruments/), [WebSocket streaming](https://kite.trade/docs/connect/v3/websocket/), [historical candles](https://kite.trade/docs/connect/v3/historical/), [Kite Connect FAQ](https://support.zerodha.com/category/trading-and-markets/general-kite/kite-api/articles/kite-connect-api-faqs), [rate limits and errors](https://kite.trade/docs/connect/v3/exceptions/).

## Order behavior and limits

- Documented order varieties include regular, AMO, cover, iceberg, and auction. Order types include MARKET, LIMIT, SL, and SL-M; products include CNC, NRML, MIS, and MTF. Availability still depends on the account, segment, instrument, exchange, and current broker rules.
- A successful placement response returns an order ID but does not prove exchange receipt or execution. Orders may be rejected, remain open, or fill partially. Reconcile order history, trades, postbacks/WebSocket events, and positions; model unknown, partial, cancelled, rejected, and complete states separately.
- Current documented limits include 10 order placements/second, 400/minute, 5,000/day per single user/API key across segments and varieties, and at most 25 modifications per order. A 429 must trigger bounded backoff and reconciliation, not a blind resubmit.
- `autoslice` can create multiple child orders, with successful and failed slices in the same response and at most 10 slices. Treat every child as independently reconcilable exposure.
- The `guid` response field is described as an unusable request ID to avoid duplication. Do not rely on it as an idempotency key. If a placement response is ambiguous, query the broker order/trade book and reconcile before any retry; never retry automatically based only on a timeout.
- The Kite terms say the APIs are not meant for fully automated trades without manual intervention and advise seeking necessary exchange approvals for full automation. Any unattended strategy runner remains out of scope until those approvals and operating controls are documented.

References: [orders](https://kite.trade/docs/connect/v3/orders/), [rate limits and errors](https://kite.trade/docs/connect/v3/exceptions/), [Kite Connect terms](https://kite.trade/terms/).

## Static IP discrepancy

Order placement requires a whitelisted static IP. The current support article says the requirement is effective 1 April 2026, while the official FAQ still says 1 April 2025. Both require a static IP for API orders, but the effective dates conflict. The support instructions say up to two IPs can be configured, whitelist changes are limited to one per calendar week, and shared use is limited to the account holder and immediate family. Confirm the current rule and change procedure with Zerodha before selecting local ISP, cloud, or VPN egress. A dynamic home IP must not be assumed acceptable.

References: [static IP instructions](https://support.zerodha.com/category/trading-and-markets/general-kite/kite-api/articles/static-ip), [Kite Connect FAQ](https://support.zerodha.com/category/trading-and-markets/general-kite/kite-api/articles/kite-connect-api-faqs).

## Integration gates before any live request

1. User establishes their own broker developer account and confirms current API plan/fees, registered redirect, enabled exchanges/segments, and permitted use. No credential is to be sent in chat.
2. Obtain written confirmation for the app's intended market-data storage, display, derived analytics, and backtest use; confirm any exchange approval needed for the intended level of automation.
3. Select and verify the order-capable static egress IP and broker whitelisting procedure. Keep API secret and tokens server-side in an appropriate secret store; document daily re-authentication, expiry, revocation, and recovery.
4. Implement a read-only adapter and instrument-master sync first, with no live order capability. Validate token churn, expiry, missing symbols, rate limits, stale data, gaps, reconnects, malformed payloads, and session boundaries against deterministic fakes.
5. Add order execution only after separate approval of the live-trading gates. Use deterministic contract tests and a manual, explicit confirmation path; never place a real order as a test. Reconcile each submission and each autoslice child before permitting another action.
6. Verify restart, timeout, duplicate-submit, partial-fill, cancel-race, rate-limit, broker outage, and kill-switch behavior. Keep live routing disabled by default and fail closed if any gate, account permission, market-data right, or broker status is unknown.

## Remaining unknowns

- Current requirements for the user's specific Zerodha account, segments, API app, redirect, and static IP have not been confirmed.
- The maximum allowed historical request span is not specified in the public API reference reviewed here.
- Written permissions for this app's proposed historical storage, derived indicators, backtesting, and display have not been obtained.
- Full automation approval, production network design, secret vault, and real-account recovery procedure have not been reviewed.

No account calls, credential setup, subscription purchase, or orders were made during this review.
