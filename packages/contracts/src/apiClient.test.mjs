import assert from "node:assert/strict";
import test from "node:test";
import { ApiClient, ApiError } from "./index.ts";

const expiredSession = {
  accessToken: "expired-access",
  refreshToken: "one-time-refresh",
  tokenType: "Bearer",
  accessExpiresInSeconds: 1,
  userId: "user-1",
};
const renewedSession = {
  accessToken: "renewed-access",
  refreshToken: "rotated-refresh",
  tokenType: "Bearer",
  accessExpiresInSeconds: 900,
  userId: "user-1",
};

function jsonResponse(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

async function withFetch(fakeFetch, run) {
  const previous = globalThis.fetch;
  globalThis.fetch = fakeFetch;
  try { await run(); }
  finally { globalThis.fetch = previous; }
}

test("parallel expired-access GETs share one rotating refresh token", async () => {
  let refreshCount = 0;
  await withFetch(async (input, init = {}) => {
    const path = new URL(String(input)).pathname;
    assert.ok(init.signal instanceof AbortSignal, "requests have a bounded network timeout");
    if (path === "/api/v1/auth/refresh") {
      refreshCount += 1;
      await new Promise((resolve) => setTimeout(resolve, 10));
      return jsonResponse(renewedSession);
    }
    const authorization = new Headers(init.headers).get("Authorization");
    if (authorization === `Bearer ${expiredSession.accessToken}`) return jsonResponse({ code: "TOKEN_EXPIRED" }, 401);
    assert.equal(authorization, `Bearer ${renewedSession.accessToken}`);
    return jsonResponse({ path });
  }, async () => {
    let currentSession = expiredSession;
    let updateCount = 0;
    const client = new ApiClient("http://127.0.0.1:8080", () => currentSession, (tokens) => {
      updateCount += 1;
      currentSession = tokens;
    });

    const results = await Promise.all([client.instruments(), client.quotes(), client.dataStatus()]);

    assert.equal(refreshCount, 1);
    assert.equal(updateCount, 1);
    assert.deepEqual(currentSession, renewedSession);
    assert.deepEqual(results.map((result) => result.path), [
      "/api/v1/market/instruments", "/api/v1/market/quotes", "/api/v1/market/data-status",
    ]);
  });
});

test("server alerts use authenticated rule, zone, and in-app notification routes", async () => {
  const calls = [];
  const rule = { id: "rule-1", instrumentId: "instrument-1", ruleType: "PRICE_CROSSING", direction: "ABOVE", price: "123.45", upperPrice: null, state: "PAUSED", reasonCode: "LICENSED_SOURCE_NOT_CONFIRMED", createdAt: "2026-10-22T04:00:00Z" };
  const zoneRule = { id: "rule-2", instrumentId: "instrument-1", ruleType: "ZONE_TOUCH", direction: null, price: "123.45", upperPrice: "124.50", state: "PAUSED", reasonCode: "LICENSED_SOURCE_NOT_CONFIRMED", createdAt: "2026-10-22T04:00:00Z" };
  const volumeRule = { id: "rule-3", instrumentId: "instrument-1", ruleType: "VOLUME_SPIKE", direction: null, price: null, upperPrice: null, volumeMultiplier: "2.00", lookbackCandles: 20, state: "PAUSED", reasonCode: "LICENSED_SOURCE_NOT_CONFIRMED", createdAt: "2026-10-22T04:00:00Z" };
  const alertEvent = { id: "event-1", ruleId: "rule-1", instrumentId: "instrument-1", ruleType: "PRICE_CROSSING", direction: "ABOVE", thresholdPrice: "123.45", upperPrice: null, triggerPrice: "123.45", candleTime: "2026-10-22T04:00:00Z", sourceId: "LICENSED_FEED", createdAt: "2026-10-22T04:01:00Z", readAt: null };
  await withFetch(async (input, init = {}) => {
    const url = new URL(String(input));
    const method = init.method ?? "GET";
    calls.push({ path: url.pathname, search: url.search, method, body: init.body ? JSON.parse(init.body) : null,
      authorization: new Headers(init.headers).get("Authorization") });
    if (method === "DELETE") return new Response(null, { status: 204 });
    if (method === "PATCH") return jsonResponse({ ...alertEvent, readAt: "2026-10-22T04:02:00Z" });
    if (method === "POST") {
      const type = JSON.parse(init.body).ruleType;
      return jsonResponse(type === "ZONE_TOUCH" ? zoneRule : type === "VOLUME_SPIKE" ? volumeRule : rule, 201);
    }
    if (url.pathname.endsWith("/events")) return jsonResponse([alertEvent]);
    if (url.pathname.endsWith("/events/event-1/read")) return jsonResponse(alertEvent);
    if (url.pathname === "/api/v1/alerts") return jsonResponse([rule]);
    return jsonResponse({ code: "NOT_FOUND" }, 404);
  }, async () => {
    const client = new ApiClient("http://127.0.0.1:8080", () => expiredSession, () => {});
    assert.deepEqual(await client.alerts(), [rule]);
    assert.deepEqual(await client.createAlert("instrument-1", "ABOVE", "123.45"), rule);
    assert.deepEqual(await client.createZoneTouchAlert("instrument-1", "123.45", "124.50"), zoneRule);
    assert.deepEqual(await client.createVolumeSpikeAlert("instrument-1", "2.00"), volumeRule);
    assert.deepEqual(await client.alertEvents(100), [alertEvent]);
    assert.equal((await client.markAlertEventRead("event-1")).readAt, "2026-10-22T04:02:00Z");
    await client.deleteAlert("rule-1");
  });

  assert.equal(calls.length, 7);
  assert.ok(calls.every((call) => call.authorization === `Bearer ${expiredSession.accessToken}`));
  assert.deepEqual(calls.map((call) => call.method), ["GET", "POST", "POST", "POST", "GET", "PATCH", "DELETE"]);
  assert.equal(calls[1].path, "/api/v1/alerts");
  assert.deepEqual(calls[1].body, { instrumentId: "instrument-1", ruleType: "PRICE_CROSSING", direction: "ABOVE", price: "123.45" });
  assert.deepEqual(calls[2].body, { instrumentId: "instrument-1", ruleType: "ZONE_TOUCH", price: "123.45", upperPrice: "124.50" });
  assert.deepEqual(calls[3].body, { instrumentId: "instrument-1", ruleType: "VOLUME_SPIKE", volumeMultiplier: "2.00" });
  assert.equal(calls[4].search, "?limit=100");
  assert.equal(calls[5].path, "/api/v1/alerts/events/event-1/read");
  assert.equal(calls[6].path, "/api/v1/alerts/rule-1");
});

test("a late unauthorized response uses the current account token without refreshing an old family", async () => {
  let releaseFirstRequest;
  let markFirstRequestStarted;
  const firstRequestStarted = new Promise((resolve) => { markFirstRequestStarted = resolve; });
  const firstRequestReleased = new Promise((resolve) => { releaseFirstRequest = resolve; });
  let refreshCount = 0;

  await withFetch(async (input, init = {}) => {
    const path = new URL(String(input)).pathname;
    if (path === "/api/v1/auth/refresh") {
      refreshCount += 1;
      return jsonResponse(renewedSession);
    }
    const authorization = new Headers(init.headers).get("Authorization");
    if (authorization === `Bearer ${expiredSession.accessToken}`) {
      markFirstRequestStarted();
      await firstRequestReleased;
      return jsonResponse({ code: "TOKEN_EXPIRED" }, 401);
    }
    assert.equal(authorization, `Bearer ${renewedSession.accessToken}`);
    return jsonResponse([{ id: "instrument-1" }]);
  }, async () => {
    let currentSession = expiredSession;
    const client = new ApiClient("http://127.0.0.1:8080", () => currentSession, (tokens) => { currentSession = tokens; });
    const pending = client.instruments();
    await firstRequestStarted;
    currentSession = renewedSession;
    releaseFirstRequest();

    const result = await pending;

    assert.equal(refreshCount, 0);
    assert.deepEqual(result, [{ id: "instrument-1" }]);
  });
});

test("an ambiguous refresh network failure preserves the session and never replays its one-time token", async () => {
  let currentSession = expiredSession;
  let updateCount = 0;
  let refreshCount = 0;

  await withFetch(async (input) => {
    const path = new URL(String(input)).pathname;
    if (path === "/api/v1/auth/refresh") {
      refreshCount += 1;
      throw new TypeError("network unavailable");
    }
    if (currentSession === renewedSession) return jsonResponse([{ id: "instrument-1" }]);
    return jsonResponse({ code: "TOKEN_EXPIRED" }, 401);
  }, async () => {
    const client = new ApiClient("http://127.0.0.1:8080", () => currentSession, (tokens) => {
      updateCount += 1;
      currentSession = tokens;
    });

    await assert.rejects(client.instruments(), (error) => error instanceof ApiError && error.status === 0);
    await assert.rejects(client.instruments(), (error) => error instanceof ApiError && error.status === 0);
    assert.equal(currentSession, expiredSession);
    assert.equal(updateCount, 0);
    assert.equal(refreshCount, 1, "a possibly-rotated token is not sent a second time");

    currentSession = renewedSession;
    assert.deepEqual(await client.instruments(), [{ id: "instrument-1" }]);
  });
});

test("an invalid rotated refresh token ends the matching session", async () => {
  let currentSession = expiredSession;
  let updateCount = 0;

  await withFetch(async (input) => {
    const path = new URL(String(input)).pathname;
    if (path === "/api/v1/auth/refresh") return jsonResponse({ code: "INVALID_REFRESH_TOKEN" }, 401);
    return jsonResponse({ code: "TOKEN_EXPIRED" }, 401);
  }, async () => {
    const client = new ApiClient("http://127.0.0.1:8080", () => currentSession, (tokens) => {
      updateCount += 1;
      currentSession = tokens;
    });

    await assert.rejects(client.instruments(), (error) => error instanceof ApiError && error.status === 401);
    assert.equal(currentSession, null);
    assert.equal(updateCount, 1);
  });
});
