import React from "react";
import { createRoot } from "react-dom/client";
import { TradingDashboard } from "@algotrade/dashboard";
import "@algotrade/dashboard/style.css";
import "./desktop.css";

const apiBase = "http://127.0.0.1:8080";
const rootElement = document.getElementById("root");

if (!rootElement) {
  throw new Error("AlgoTrade Hub desktop root element is missing.");
}

createRoot(rootElement).render(
  <React.StrictMode>
    <TradingDashboard apiBase={apiBase} storageKey="algotrade.desktop.session.v1" />
  </React.StrictMode>,
);
