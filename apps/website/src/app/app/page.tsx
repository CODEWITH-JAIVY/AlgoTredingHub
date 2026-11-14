"use client";

import { TradingDashboard } from "@algotrade/dashboard";
import { useEffect } from "react";
import { resolveApiBase } from "../apiBase";

const apiBase = resolveApiBase(process.env.NEXT_PUBLIC_API_BASE_URL, process.env.NODE_ENV === "development");

export default function WorkspacePage() {
  useEffect(() => {
    if ("serviceWorker" in navigator) void navigator.serviceWorker.register("/sw.js", { scope: "/" }).catch(() => undefined);
  }, []);
  return <TradingDashboard apiBase={apiBase} storageKey="algotrade.web.session.v1" />;
}
