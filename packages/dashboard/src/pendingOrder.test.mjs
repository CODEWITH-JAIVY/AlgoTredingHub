import assert from "node:assert/strict";
import test from "node:test";
import { clearPendingOrder, isAmbiguousOrderResponseStatus, pendingOrderStorageKey, readPendingOrder, reconcilePendingOrder, savePendingOrder } from "./pendingOrder.ts";

class MemoryStorage {
  values = new Map();
  failReads = false;
  failWrites = false;
  getItem(key) {
    if (this.failReads) throw new Error("storage unavailable");
    return this.values.get(key) ?? null;
  }
  setItem(key, value) {
    if (this.failWrites) throw new Error("storage unavailable");
    this.values.set(key, String(value));
  }
  removeItem(key) {
    if (this.failWrites) throw new Error("storage unavailable");
    this.values.delete(key);
  }
}

const request = {
  clientOrderId: "a1b2c3d4-e5f6-47a8-9123-abcdef012345",
  instrumentId: "01234567-89ab-cdef-0123-456789abcdef",
  side: "BUY",
  orderType: "MARKET",
  quantity: 25,
  protectiveStopPrice: 102.5,
};
const order = {
  ...request,
  id: "fedcba98-7654-4321-9876-abcdef012345",
  status: "OPEN",
  brokerOrderId: "MOCK-fedcba98",
  mode: "MOCK",
  createdAt: "2026-10-21T03:00:00Z",
};

test("uncertain order HTTP responses retain recovery state for reconciliation", () => {
  for (const status of [null, undefined, 0, 408, 409, 429, 500, 502, 503, 504]) {
    assert.equal(isAmbiguousOrderResponseStatus(status), true, `status ${status} must be reconciled`);
  }
  for (const status of [200, 400, 401, 403, 404, 422]) {
    assert.equal(isAmbiguousOrderResponseStatus(status), false, `status ${status} is a definitive rejection`);
  }
});

test("pending-order key is scoped to the dashboard session key and account", () => {
  assert.notEqual(pendingOrderStorageKey("desktop", "user-a"), pendingOrderStorageKey("desktop", "user-b"));
  assert.notEqual(pendingOrderStorageKey("desktop", "user-a"), pendingOrderStorageKey("web", "user-a"));
});

test("a saved exact request can be restored after a window restart", () => {
  const storage = new MemoryStorage();
  const key = pendingOrderStorageKey("desktop", "user-a");
  assert.equal(savePendingOrder(storage, key, request), true);
  assert.deepEqual(readPendingOrder(storage, key), { kind: "pending", request });
});

test("a pending ID cannot be overwritten with a different order payload", () => {
  const storage = new MemoryStorage();
  const key = pendingOrderStorageKey("desktop", "user-a");
  assert.equal(savePendingOrder(storage, key, request), true);
  assert.equal(savePendingOrder(storage, key, { ...request, quantity: 50 }), false);
  assert.deepEqual(readPendingOrder(storage, key), { kind: "pending", request });
});

test("corrupt and unsupported recovery records fail closed", () => {
  const storage = new MemoryStorage();
  const key = pendingOrderStorageKey("desktop", "user-a");
  storage.setItem(key, "{");
  assert.deepEqual(readPendingOrder(storage, key), { kind: "blocked", reason: "invalid" });
  storage.setItem(key, JSON.stringify({ version: 99, request }));
  assert.deepEqual(readPendingOrder(storage, key), { kind: "blocked", reason: "invalid" });
});

test("a recovery record with a non-UUID client order ID is rejected", () => {
  const storage = new MemoryStorage();
  const key = pendingOrderStorageKey("desktop", "user-a");
  storage.setItem(key, JSON.stringify({ version: 1, request: { ...request, clientOrderId: "12345678" } }));
  assert.deepEqual(readPendingOrder(storage, key), { kind: "blocked", reason: "invalid" });
  assert.equal(savePendingOrder(storage, key, { ...request, clientOrderId: "12345678" }), false);
});

test("storage failures block recovery instead of allowing a new request", () => {
  const storage = new MemoryStorage();
  const key = pendingOrderStorageKey("desktop", "user-a");
  storage.failWrites = true;
  assert.equal(savePendingOrder(storage, key, request), false);
  storage.failWrites = false;
  storage.failReads = true;
  assert.deepEqual(readPendingOrder(storage, key), { kind: "blocked", reason: "unavailable" });
});

test("a pending record is removed only after reconciliation succeeds", () => {
  const storage = new MemoryStorage();
  const key = pendingOrderStorageKey("desktop", "user-a");
  assert.equal(savePendingOrder(storage, key, request), true);
  assert.equal(clearPendingOrder(storage, key), true);
  assert.deepEqual(readPendingOrder(storage, key), { kind: "none" });
});

test("order reconciliation requires the exact client ID, payload, and mock mode", () => {
  assert.deepEqual(reconcilePendingOrder([], request), { kind: "pending" });
  assert.deepEqual(reconcilePendingOrder([order], request), { kind: "resolved", order });
  assert.deepEqual(reconcilePendingOrder([{ ...order, quantity: 50 }], request), { kind: "conflict", order: { ...order, quantity: 50 } });
  assert.deepEqual(reconcilePendingOrder([{ ...order, mode: "LIVE" }], request), { kind: "conflict", order: { ...order, mode: "LIVE" } });
});
