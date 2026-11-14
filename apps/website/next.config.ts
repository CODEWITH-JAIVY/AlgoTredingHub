import type { NextConfig } from "next";

const isolatedBuildDir = process.env.ALGO_BUILD_DIST_DIR?.trim();
if (isolatedBuildDir && !/^\.next-[A-Za-z0-9-]+$/.test(isolatedBuildDir)) {
  throw new Error("ALGO_BUILD_DIST_DIR must name a dedicated .next-<name> directory.");
}

const config: NextConfig = {
  distDir: isolatedBuildDir || ".next",
  transpilePackages: ["@algotrade/dashboard", "@algotrade/contracts"],
  poweredByHeader: false,
  reactStrictMode: true,
};

export default config;
