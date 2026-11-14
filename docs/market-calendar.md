# Demo market calendar

## Verified coverage

`NseBseEquityFnoDemoCalendar` covers regular NSE/BSE equity cash and equity-derivatives sessions from **5 November 2025 through 31 December 2026**. It uses Asia/Kolkata time and the regular 09:15–15:30 session. Holidays for NSE capital-market/F&O and BSE equity/equity-derivatives scopes were cross-checked against the official exchange notices linked below. The 15 January 2026 Maharashtra election closure is included for both NSE segments and BSE equity/equity-derivatives segments.

The 2025 date is the earliest supported boundary. It is a full-day holiday; the preceding 2025 Muhurat session is deliberately outside coverage. From 2025, the supported-window holidays are:

| Date | Closure |
| --- | --- |
| 2025-11-05 | Prakash Gurpurb Sri Guru Nanak Dev |
| 2025-12-25 | Christmas |

The verified 2026 full-day closures are:

| Date | Closure |
| --- | --- |
| 2026-01-15 | Maharashtra municipal corporation elections |
| 2026-01-26 | Republic Day |
| 2026-03-03 | Holi |
| 2026-03-26 | Shri Ram Navami |
| 2026-03-31 | Shri Mahavir Jayanti |
| 2026-04-03 | Good Friday |
| 2026-04-14 | Dr. Baba Saheb Ambedkar Jayanti |
| 2026-05-01 | Maharashtra Day |
| 2026-05-28 | Bakri Id |
| 2026-06-26 | Muharram |
| 2026-09-14 | Ganesh Chaturthi |
| 2026-10-02 | Mahatma Gandhi Jayanti |
| 2026-10-20 | Dussehra |
| 2026-11-10 | Diwali-Balipratipada |
| 2026-11-24 | Prakash Gurpurb Sri Guru Nanak Dev |
| 2026-12-25 | Christmas |

Official source notices:

- NSE 2025 [Capital Market](https://nsearchives.nseindia.com/content/circulars/CMTR65587.pdf) and [F&O](https://nsearchives.nseindia.com/content/circulars/FAOP65588.pdf) calendars.
- BSE 2025 [Equity, Equity Derivatives and SLB notice 20241213-30](https://www.bseindia.com/markets/MarketInfo/DispNewNoticesCirculars.aspx?page=20241213-30).
- NSE 2026 [Capital Market](https://nsearchives.nseindia.com/content/circulars/CMTR71775.pdf) and [F&O](https://nsearchives.nseindia.com/content/circulars/FAOP71777.pdf) calendars.
- BSE 2026 [Equity and Equity Derivatives notice 20251212-8](https://www.bseindia.com/markets/MarketInfo/DispNewNoticesCirculars.aspx?page=20251212-8).
- 15 January 2026 additions: NSE [Capital Market](https://nsearchives.nseindia.com/content/circulars/CMTR72260.pdf) and [F&O](https://nsearchives.nseindia.com/content/circulars/FAOP72262.pdf); BSE [notice 20260112-8](https://www.bseindia.com/markets/MarketInfo/DispNewNoticesCirculars.aspx?page=20260112-8).

## Analysis lookback and completeness

Provider analysis reads a separate bounded **350-calendar-day** history window. The chart-candle API remains capped at seven days. The analysis query loads finalized one-minute bars only, does not append the unfinalized current minute, and caps raw rows at 100,000. Aggregated payloads keep the latest 5,000 candles per timeframe to stay within the analysis-engine request limit; series completeness is checked before this payload cap.

Calendar-dependent analysis fails closed when any date in the requested analysis window falls outside verified coverage or on an unmodeled special-session date. A missing regular trading day between two observed sessions, an internal missing bucket, a gap record, stale data, or an instrument-master boundary also withholds directional output. A published holiday between otherwise adjacent trading days is not treated as a missing session.

The 2026 Muhurat session is on Sunday, 8 November, outside the regular-session calendar. That date is marked unsupported, so an analysis window crossing it cannot be considered complete. The 2025 Muhurat session falls before the supported window. Special/shortened sessions are not represented.

## Fail-closed behavior and limits

- Calendar-dependent market-tick and order checks reject unsupported dates with `MARKET_CALENDAR_UNAVAILABLE` (HTTP 503). Risk-policy scheduling returns the same error when no supported next session is available.
- This is a maintained demo calendar, not a real-time exchange status feed. Unscheduled closures, halts, auctions, future circular amendments, and instrument-specific exceptions remain unsupported.
- The daily EMA-200 needs at least 200 valid daily bars. An insufficient history remains non-actionable even if the data source reports success.
- The CSV backtest uses its own regular-session validation and does not yet apply exchange holiday dates.
- This calendar and analysis path do not authorize live data ingestion or live trading.

When updating, re-check official exchange notices and segment scope, special sessions, add tests for holidays and boundaries, and update the coverage limits here with the Java implementation.
