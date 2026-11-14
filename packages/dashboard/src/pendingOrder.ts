import type { Order, OrderRequest } from "@algotrade/contracts";

export type PendingOrderState =
  | { kind: "none" }
  | { kind: "pending"; request: OrderRequest }
  | { kind: "blocked"; reason: "invalid" | "unavailable" };
export type PendingOrderReconciliation =
  | { kind: "pending" }
  | { kind: "resolved"; order: Order }
  | { kind: "conflict"; order: Order };

type PendingRecord = { version: 1; request: OrderRequest };
type StorageLike = Pick<Storage, "getItem" | "setItem" | "removeItem">;

export function isAmbiguousOrderResponseStatus(status: number | null | undefined): boolean {
  return status == null || status === 0 || status === 408 || status === 409 || status === 429 || status >= 500;
}

export function pendingOrderStorageKey(sessionKey: string, userId: string): string {
  return `algotrade.pending-order.v1.${sessionKey}.${userId}`;
}

export function readPendingOrder(storage: StorageLike, key: string): PendingOrderState {
  let raw: string | null;
  try { raw = storage.getItem(key); }
  catch { return { kind: "blocked", reason: "unavailable" }; }
  if (raw === null) return { kind: "none" };

  let parsed: unknown;
  try { parsed = JSON.parse(raw); }
  catch { return { kind: "blocked", reason: "invalid" }; }
  if (!isRecord(parsed) || parsed.version !== 1 || !isOrderRequest(parsed.request)) {
    return { kind: "blocked", reason: "invalid" };
  }
  return { kind: "pending", request: sanitizeOrderRequest(parsed.request) };
}

export function savePendingOrder(storage: StorageLike, key: string, request: OrderRequest): boolean {
  if (!isOrderRequest(request)) return false;
  try {
    const existing = storage.getItem(key);
    if (existing !== null) {
      const state = readPendingOrder(storage, key);
      return state.kind === "pending" && sameRequest(state.request, request);
    }
    const record: PendingRecord = { version: 1, request: sanitizeOrderRequest(request) };
    storage.setItem(key, JSON.stringify(record));
    const saved = readPendingOrder(storage, key);
    return saved.kind === "pending" && sameRequest(saved.request, request);
  } catch {
    return false;
  }
}

export function clearPendingOrder(storage: StorageLike, key: string): boolean {
  try {
    storage.removeItem(key);
    return storage.getItem(key) === null;
  } catch {
    return false;
  }
}

export function reconcilePendingOrder(orders: readonly Order[], request: OrderRequest): PendingOrderReconciliation {
  const existing = orders.find((order) => order.clientOrderId === request.clientOrderId);
  if (!existing) return { kind: "pending" };
  const matches = existing.instrumentId === request.instrumentId
    && existing.side === request.side
    && existing.orderType === request.orderType
    && existing.quantity === request.quantity
    && Number(existing.protectiveStopPrice) === request.protectiveStopPrice
    && existing.mode === "MOCK";
  return matches ? { kind: "resolved", order: existing } : { kind: "conflict", order: existing };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isOrderRequest(value: unknown): value is OrderRequest {
  if (!isRecord(value)) return false;
  return typeof value.clientOrderId === "string"
    && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value.clientOrderId)
    && typeof value.instrumentId === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.instrumentId)
    && (value.side === "BUY" || value.side === "SELL")
    && value.orderType === "MARKET"
    && typeof value.quantity === "number" && Number.isSafeInteger(value.quantity) && value.quantity > 0
    && typeof value.protectiveStopPrice === "number" && Number.isFinite(value.protectiveStopPrice) && value.protectiveStopPrice > 0;
}

function sanitizeOrderRequest(request: OrderRequest): OrderRequest {
  return {
    clientOrderId: request.clientOrderId,
    instrumentId: request.instrumentId,
    side: request.side,
    orderType: "MARKET",
    quantity: request.quantity,
    protectiveStopPrice: request.protectiveStopPrice,
  };
}

function sameRequest(left: OrderRequest, right: OrderRequest): boolean {
  return left.clientOrderId === right.clientOrderId
    && left.instrumentId === right.instrumentId
    && left.side === right.side
    && left.orderType === right.orderType
    && left.quantity === right.quantity
    && left.protectiveStopPrice === right.protectiveStopPrice;
}
