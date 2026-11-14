import { useState } from "react";

const brokers = [
  { id: "zerodha", name: "Zerodha", api: "Kite Connect", docs: "https://kite.trade/docs/connect/v3/", note: "An active trading account and a registered Kite Connect app are required. API key, secret and redirect handling belong on the protected backend; re-authentication and token expiry must be handled there.", noteHi: "Active trading account aur registered Kite Connect app chahiye. API key, secret aur redirect sirf protected backend par handle hon; token expiry aur re-authentication bhi backend ko sambhalni hogi." },
  { id: "angelone", name: "Angel One", api: "SmartAPI", docs: "https://smartapi.angelone.in/docs", note: "Integration is not verified yet. Confirm account eligibility, current SmartAPI access, authentication/2FA flow, rate limits, order lifecycle and market-data terms against the official documentation.", noteHi: "Integration abhi verify nahi hai. Official docs mein account eligibility, SmartAPI access, authentication/2FA, rate limits, order lifecycle aur market-data terms check karne honge." },
  { id: "upstox", name: "Upstox", api: "Developer API", docs: "https://upstox.com/developer/api-documentation/", note: "Integration is not verified yet. Confirm app registration, redirect/auth flow, scopes, subscriptions, rate limits, supported order types and data-use terms before implementation.", noteHi: "Integration abhi verify nahi hai. App registration, redirect/auth flow, scopes, subscriptions, rate limits, supported order types aur data-use terms implementation se pehle check karne honge." },
  { id: "dhan", name: "Dhan", api: "DhanHQ API", docs: "https://dhanhq.co/docs/v2/", note: "Integration is not verified yet. Confirm current account/API eligibility, token lifecycle, static network/IP requirements, order capabilities, rate limits and market-data terms before implementation.", noteHi: "Integration abhi verify nahi hai. Account/API eligibility, token lifecycle, static network/IP requirements, order capabilities, rate limits aur market-data terms pehle check karne honge." },
] as const;

const guide = {
  en: {
    title: "Broker connections", intro: "Choose a broker to review its setup checklist and feature requirements.", connected: "NO BROKER CONNECTED", safety: "Paper mode only. This extension has no broker login or credential form. API secrets must never be entered into this side panel.", safetyLead: "Paper mode only.", setup: "setup requirements", planned: "PLANNED · NOT CONNECTED", docs: "Open official API docs", prereqs: "Before a connector can be enabled", status: "Feature availability", kicker: "CONNECTORS · SETUP GUIDE", providerNote: (broker: (typeof brokers)[number]) => broker.note,
    checklist: [
      ["Confirm account and API eligibility", "Check current broker account, app/API access, charges, supported segments and permissions with the provider."],
      ["Register a secure authentication flow", "Use an approved redirect and backend-mediated login. Never put an API secret in extension code, a URL, logs or browser storage."],
      ["Review data rights separately", "Trading API access does not automatically grant rights to display, store, redistribute or derive analytics from market data."],
      ["Verify order and recovery behavior", "Test rejected/partial orders, timeouts, duplicate requests, cancel races, token expiry, reconnect and broker-position reconciliation."],
      ["Keep live mode locked until release gates pass", "Independent security, operational, legal/data-rights and broker checks are required before any live order route exists."],
    ],
    features: [
      ["Paper order review", "Available", "Local mock API only. A mock acknowledgement is not a fill, position or broker order."],
      ["Live order place / modify / cancel", "Unavailable", "Verified broker adapter, protected server credentials, explicit user authorization, idempotency, state reconciliation and kill/cancel recovery."],
      ["Live quotes and charts", "Unavailable", "Provider entitlement plus display/storage/derived-use rights, freshness checks and a verified market-data feed."],
      ["Broker positions and fills", "Unavailable", "Broker API permissions and authoritative fill/position reconciliation, including partial fills and outages."],
      ["Historical backtest", "Local CSV only", "User-provided CSV. Data rights, symbol mapping, corporate actions, fees and slippage assumptions must be checked by the user."],
      ["Alerts and scanner", "Paused", "Eligible licensed feed, permitted use, fresh finalized candles and verified alert delivery/recovery."],
      ["Journal and reports", "Manual / local", "Journal notes are user-entered. Broker-synced performance and tax reports are not available."],
    ],
    footer: "Current connector status is always shown explicitly. Documentation can change; verify all provider terms before enabling an integration. This preview never submits live orders.",
  },
  hi: {
    title: "ब्रोकर कनेक्शन", intro: "सेटअप और फीचर की ज़रूरतें देखने के लिए ब्रोकर चुनें।", connected: "कोई ब्रोकर कनेक्ट नहीं", safety: "सिर्फ़ पेपर मोड। इस एक्सटेंशन में ब्रोकर लॉगिन या क्रेडेंशियल फ़ॉर्म नहीं है। API secret इस साइड पैनल में कभी न डालें।", safetyLead: "सिर्फ़ पेपर मोड।", setup: "सेटअप की ज़रूरतें", planned: "योजना में · कनेक्ट नहीं", docs: "आधिकारिक API डॉक्यूमेंट खोलें", prereqs: "कनेक्टर चालू करने से पहले", status: "फ़ीचर की उपलब्धता", kicker: "कनेक्टर · सेटअप गाइड", providerNote: (broker: (typeof brokers)[number]) => broker.noteHi,
    checklist: [
      ["अकाउंट और API पात्रता जाँचें", "ब्रोकर से मौजूदा अकाउंट, ऐप/API एक्सेस, शुल्क, सेगमेंट और अनुमतियाँ जाँचें।"],
      ["सुरक्षित authentication flow दर्ज करें", "स्वीकृत redirect और backend login इस्तेमाल करें। API secret extension code, URL, logs या browser storage में न रखें।"],
      ["डेटा अधिकार अलग से जाँचें", "Trading API access से market data को दिखाने, रखने, बाँटने या उससे analytics बनाने के अधिकार अपने-आप नहीं मिलते।"],
      ["ऑर्डर और recovery व्यवहार जाँचें", "Rejected/partial orders, timeout, duplicate request, cancel race, token expiry, reconnect और position reconciliation टेस्ट करें।"],
      ["सभी release checks तक live बंद रखें", "Live route से पहले security, operations, कानूनी/data-rights और broker की स्वतंत्र जाँच ज़रूरी है।"],
    ],
    features: [
      ["पेपर ऑर्डर समीक्षा", "उपलब्ध", "सिर्फ़ लोकल mock API। Mock acknowledgement असली fill, position या broker order नहीं है।"],
      ["Live order place / modify / cancel", "उपलब्ध नहीं", "Verified broker adapter, protected server credentials, user confirmation, idempotency और order recovery चाहिए।"],
      ["Live quotes और charts", "उपलब्ध नहीं", "Provider entitlement, display/storage/derived-use rights, freshness checks और verified feed चाहिए।"],
      ["Broker positions और fills", "उपलब्ध नहीं", "Broker API permissions और partial fills/outage समेत authoritative reconciliation चाहिए।"],
      ["Historical backtest", "सिर्फ़ local CSV", "User की CSV। Data rights, symbol mapping, corporate actions, fees और slippage मान्यताएँ user को जाँचनी होंगी।"],
      ["Alerts और scanner", "रोका हुआ", "Licensed feed, permitted use, fresh finalized candles और verified alert recovery चाहिए।"],
      ["Journal और reports", "Manual / local", "Journal user के notes हैं। Broker-synced performance और tax reports उपलब्ध नहीं हैं।"],
    ],
    footer: "Connector status हमेशा साफ़ दिखेगा। Provider docs बदल सकते हैं; integration enable करने से पहले शर्तें फिर जाँचें। यह preview live order नहीं भेजता।",
  },
} as const;

export function BrokerSetupPanel({ language }: { language: "en" | "hi" }) {
  const [selectedId, setSelectedId] = useState<(typeof brokers)[number]["id"]>("zerodha");
  const selected = brokers.find((broker) => broker.id === selectedId) ?? brokers[0];
  const t = guide[language];

  return <main className="broker-guide" lang={language}>
    <header className="broker-guide-header"><div><span className="broker-kicker">{t.kicker}</span><h1>{t.title}</h1><p>{t.intro}</p></div><span className="broker-offline"><i />{t.connected}</span></header>
    <div className="broker-safety-note" role="note"><b>{t.safetyLead}</b> {t.safety.replace(t.safetyLead, "")}</div>
    <nav className="broker-picker" aria-label={language === "hi" ? "ब्रोकर चुनें" : "Choose broker"}>{brokers.map((broker) => <button key={broker.id} type="button" className={selectedId === broker.id ? "selected" : ""} aria-pressed={selectedId === broker.id} onClick={() => setSelectedId(broker.id)}><span className="broker-avatar">{broker.name[0]}</span><span><b>{broker.name}</b><small>{broker.api}</small></span><i>↗</i></button>)}</nav>
    <section className="broker-detail-card"><div className="broker-detail-top"><div><span className="broker-kicker">{selected.api.toUpperCase()}</span><h2>{selected.name} {t.setup}</h2></div><span className="broker-roadmap-state">{t.planned}</span></div><p className="broker-provider-note">{t.providerNote(selected)}</p><a className="broker-doc-link" href={selected.docs} target="_blank" rel="noreferrer">{t.docs} <span>↗</span></a>
      <h3>{t.prereqs}</h3><ol className="broker-checklist">{t.checklist.map(([title, body], index) => <li key={title}><span>0{index + 1}</span><div><b>{title}</b><small>{body}</small></div></li>)}</ol>
    </section>
    <section className="broker-feature-card"><div className="broker-detail-top"><div><span className="broker-kicker">{language === "hi" ? "हब के फ़ीचर" : "WHAT THE HUB CAN DO"}</span><h2>{t.status}</h2></div><span className="broker-live-lock">{language === "hi" ? "LIVE बंद" : "LIVE LOCKED"}</span></div><div className="broker-feature-list">{t.features.map(([feature, state, requirement]) => <article key={feature}><div><b>{feature}</b><span className={state.includes("Unavailable") || state.includes("Paused") || state.includes("नहीं") || state.includes("रोका") ? "feature-state muted" : "feature-state"}>{state}</span></div><p>{requirement}</p></article>)}</div></section>
    <footer className="broker-guide-foot">{t.footer}</footer>
  </main>;
}
