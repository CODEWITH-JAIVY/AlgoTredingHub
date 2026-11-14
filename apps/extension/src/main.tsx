import React from "react";
import { createRoot } from "react-dom/client";
import { useState } from "react";
import { TradingDashboard } from "@algotrade/dashboard";
import { BrokerSetupPanel } from "./BrokerSetupPanel";
import { EXTENSION_API_BASE } from "./apiBase";
import "@algotrade/dashboard/style.css";
import "./broker-setup.css";

function ExtensionApp() {
  const [page, setPage] = useState<"workspace" | "brokers">("workspace");
  const [language, setLanguage] = useState<"en" | "hi">("en");
  return <div className="extension-root">
    <nav className="extension-switcher" aria-label="Extension sections">
      <button type="button" className={page === "workspace" ? "active" : ""} aria-pressed={page === "workspace"} onClick={() => setPage("workspace")}><span>▦</span>{language === "hi" ? "पेपर वर्कस्पेस" : "Paper workspace"}</button>
      <button type="button" className={page === "brokers" ? "active" : ""} aria-pressed={page === "brokers"} onClick={() => setPage("brokers")}><span>⛨</span>{language === "hi" ? "ब्रोकर सेटअप" : "Broker setup"}</button>
      <button type="button" className="extension-language" aria-label={language === "hi" ? "Switch language to English" : "भाषा हिंदी में बदलें"} onClick={() => setLanguage(language === "en" ? "hi" : "en")}>{language === "en" ? "हिंदी" : "EN"}</button>
      <span className="extension-mock-label"><i /> {language === "hi" ? "सिर्फ़ मॉक" : "MOCK ONLY"}</span>
    </nav>
    {page === "workspace" ? <TradingDashboard apiBase={EXTENSION_API_BASE} storageKey="algotrade.extension.session.v1" compact /> : <BrokerSetupPanel language={language} />}
  </div>;
}

createRoot(document.getElementById("root")!).render(<React.StrictMode><ExtensionApp /></React.StrictMode>);
