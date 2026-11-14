import assert from "node:assert/strict";
import test from "node:test";
import { resolveApiBase } from "./apiBase.ts";

test("development defaults to an IPv4 loopback backend", () => {
  assert.equal(resolveApiBase(undefined, true), "http://127.0.0.1:8080");
  assert.equal(resolveApiBase("  ", true), "http://127.0.0.1:8080");
});

test("production without configuration stays same-origin and never guesses localhost", () => {
  assert.equal(resolveApiBase(undefined, false), "");
  assert.equal(resolveApiBase("", false), "");
});

test("same-origin paths and HTTPS service URLs are supported", () => {
  assert.equal(resolveApiBase("/api", false), "/api");
  assert.equal(resolveApiBase("/api/", false), "/api");
  assert.equal(resolveApiBase("https://api.example.test/v1/", false), "https://api.example.test/v1");
});

test("plain HTTP is accepted only for loopback during development", () => {
  assert.equal(resolveApiBase("http://localhost:8080/", true), "http://localhost:8080");
  assert.equal(resolveApiBase("http://[::1]:8080", true), "http://[::1]:8080");
  assert.equal(resolveApiBase("http://api.example.test", true), "");
  assert.equal(resolveApiBase("http://127.0.0.1:8080", false), "");
});

test("protocol-relative URLs, credentials, query strings, and malformed URLs fail closed", () => {
  for (const value of [
    "//api.example.test",
    "http://user:password@localhost:8080",
    "https://api.example.test?token=secret",
    "https://api.example.test/#fragment",
    "javascript:alert(1)",
    "not a URL",
    "/api?token=secret",
    "/\\api.example.test",
  ]) {
    assert.equal(resolveApiBase(value, true), "", value);
    assert.equal(resolveApiBase(value, false), "", value);
  }
});
