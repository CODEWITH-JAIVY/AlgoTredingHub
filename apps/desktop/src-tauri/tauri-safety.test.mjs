import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const desktopRoot = fileURLToPath(new URL("../", import.meta.url));
const readDesktopFile = (path) => readFileSync(join(desktopRoot, path), "utf8");
const tauriConfig = JSON.parse(readDesktopFile("src-tauri/tauri.conf.json"));
const desktopMain = readDesktopFile("src/main.tsx");
const rustEntry = readDesktopFile("src-tauri/src/lib.rs");
const rustManifest = readDesktopFile("src-tauri/Cargo.toml");
const expectedApiBase = "http://127.0.0.1:8080";

function connectSources(policy) {
  const directive = policy.split(";").map((part) => part.trim())
    .find((part) => part.startsWith("connect-src "));
  assert.ok(directive, "CSP must define connect-src explicitly");
  return directive.slice("connect-src ".length).split(/\s+/);
}

function readFrontendTextAssets(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return readFrontendTextAssets(path);
    if (/\.(?:html|js|css|json|webmanifest)$/i.test(entry.name)) {
      return [readFileSync(path, "utf8")];
    }
    return [];
  });
}

test("desktop frontend uses only the fixed local mock API", () => {
  assert.match(desktopMain, /const apiBase = "http:\/\/127\.0\.0\.1:8080";/);
  assert.doesNotMatch(desktopMain, /import\.meta\.env|process\.env|location\.search/);
});

test("production and development CSPs allow only the intended local connections", () => {
  const { csp, devCsp } = tauriConfig.app.security;
  assert.deepEqual(connectSources(csp), ["'self'", expectedApiBase]);
  assert.deepEqual(connectSources(devCsp), ["'self'", expectedApiBase, "ws://127.0.0.1:1420"]);
  assert.doesNotMatch(csp, /\*|https?:\/\/(?!127\.0\.0\.1)/);
});

test("Tauri ships with no native capabilities, global bridge, or active bundle", () => {
  assert.equal(tauriConfig.app.withGlobalTauri, false);
  assert.deepEqual(tauriConfig.app.security.capabilities, []);
  assert.equal(tauriConfig.bundle.active, false);
  assert.equal(tauriConfig.app.windows.length, 1);
  assert.equal(tauriConfig.app.windows[0].resizable, true);
  assert.equal(tauriConfig.app.windows[0].fullscreen, false);
});

test("Rust entry point adds no plugins or custom IPC commands", () => {
  assert.doesNotMatch(rustEntry, /\.plugin\s*\(|\.invoke_handler\s*\(/);
  assert.doesNotMatch(rustManifest, /tauri-plugin-/);
});

test("built desktop bundle contains the local API and no broker endpoint or credential constants", () => {
  const distributionDirectory = join(desktopRoot, "dist");
  const frontendFiles = readFrontendTextAssets(distributionDirectory);
  assert.ok(frontendFiles.length > 0, "build the desktop frontend before running this safety check");

  const bundle = frontendFiles.join("\n");
  assert.ok(bundle.includes(expectedApiBase), "bundle must point to the fixed local API");
  assert.doesNotMatch(bundle, /https?:\/\/(?:api\.)?kite\.trade|kiteconnect/i);
  assert.doesNotMatch(bundle, /(?:KITE|ZERODHA)[_-]?(?:API)?[_-]?(?:KEY|SECRET|TOKEN)\s*[:=]/i);
});
