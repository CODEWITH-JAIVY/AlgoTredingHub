import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";
import { EXTENSION_API_BASE } from "./apiBase.ts";

const manifest = JSON.parse(await readFile(new URL("../public/manifest.json", import.meta.url), "utf8"));

test("extension API base is the backend's IPv4 loopback origin", () => {
  assert.equal(EXTENSION_API_BASE, "http://127.0.0.1:8080");
  assert.ok(EXTENSION_API_BASE.startsWith("http://127.0.0.1:"));
});

test("extension permissions and CSP expose only the loopback API host", () => {
  assert.deepEqual(manifest.host_permissions, ["http://127.0.0.1:8080/*"]);
  assert.equal(manifest.content_security_policy.extension_pages,
    "script-src 'self'; object-src 'self'; connect-src 'self' http://127.0.0.1:8080");
  assert.deepEqual(manifest.permissions, ["sidePanel"]);
});
