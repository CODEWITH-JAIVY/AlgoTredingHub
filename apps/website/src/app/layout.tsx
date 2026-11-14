import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import "@algotrade/dashboard/style.css";

export const metadata: Metadata = {
  title: "AlgoTrade Hub — Paper-first multi-broker workspace",
  description: "Practise with mock orders, review broker requirements and explore the AlgoTrade Hub Chrome side-panel preview. Live trading is not available.",
  applicationName: "AlgoTrade Hub",
  manifest: "/manifest.webmanifest",
};

export const viewport: Viewport = { themeColor: "#091613", width: "device-width", initialScale: 1, viewportFit: "cover" };

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return <html lang="en"><body>{children}</body></html>;
}
