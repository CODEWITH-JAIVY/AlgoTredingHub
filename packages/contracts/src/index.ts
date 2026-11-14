export type TokenResponse = {
  accessToken: string;
  refreshToken: string;
  tokenType: "Bearer";
  accessExpiresInSeconds: number;
  userId: string;
};

export type DecimalValue = number | string;

export type Instrument = {
  id: string;
  exchange: string;
  segment: string;
  symbol: string;
  instrumentType: string;
  lotSize: number;
  tickSize: DecimalValue;
  masterVersion: string;
  source: string;
  synthetic: boolean;
  updatedAt: string;
};

export type Quote = {
  instrumentId: string;
  exchange: string;
  symbol: string;
  price: DecimalValue;
  source: string;
  synthetic: boolean;
  freshness: string;
  fresh: boolean;
  asOf?: string | null;
};

export type Candle = {
  time: string;
  open: DecimalValue;
  high: DecimalValue;
  low: DecimalValue;
  close: DecimalValue;
  volume: number;
  tradeCount: number;
  finalized: boolean;
  quality: string;
  instrumentMasterVersion: string;
};

export type CandleSeries = {
  instrumentId: string;
  timeframe: string;
  source: string;
  synthetic: boolean;
  freshness: string;
  fresh: boolean;
  timestampZone: string;
  from: string;
  to: string;
  candles: Candle[];
  gaps: Array<{ from: string; to: string; reason: string; source: string }>;
  warnings: string[];
};

export type DemoStatus = {
  licensedSourceConfigured: boolean;
  statusMessage: string;
  demoRangeStart: string;
  demoRangeEnd: string;
  sources: Array<{ id: string; enabled: boolean; synthetic: boolean; usageRightsConfirmed: boolean; live: boolean }>;
};

export type AnalysisSummary = {
  instrumentId: string;
  timeframe: "5m" | "15m" | "1h" | "1d";
  source: string;
  freshness: string;
  dataTimestamp: string;
  algorithmVersion: string;
  complete: boolean;
  signalStatus: "AVAILABLE" | "SUPPRESSED";
  actionable: boolean;
  bias: "BULLISH" | "NEUTRAL" | "BEARISH";
  candleCount: number;
  zoneCount: number;
  indicators: AnalysisIndicators;
  warnings: string[];
  summaryEnglish: string[];
  summaryHindi: string[];
};

export type AnalysisIndicators = {
  ema20: DecimalValue | null;
  ema50: DecimalValue | null;
  ema200: DecimalValue | null;
  adx14: DecimalValue | null;
  rsi14: DecimalValue | null;
  macd: DecimalValue | null;
  macdSignal: DecimalValue | null;
  macdHistogram: DecimalValue | null;
  atr14: DecimalValue | null;
  volumeAverage20: DecimalValue | null;
  latestVolumeRatio: DecimalValue | null;
};

export type RiskPolicyLimits = {
  maxQuantity: number;
  maxOrderNotional: DecimalValue;
  maxOpenOrders: number;
  maxDailyLoss: DecimalValue;
  maxTradeLoss: DecimalValue;
  maxOrdersPerDay: number;
  noNewOrdersAfter: string;
  noOrdersFirstMinutes: number;
};

export type PendingRiskPolicy = {
  maxQuantity?: number | null;
  maxOrderNotional?: DecimalValue | null;
  maxOpenOrders?: number | null;
  maxDailyLoss?: DecimalValue | null;
  maxTradeLoss?: DecimalValue | null;
  maxOrdersPerDay?: number | null;
  noNewOrdersAfter?: string | null;
  noOrdersFirstMinutes?: number | null;
  effectiveOn: string;
};

export type RiskPolicy = RiskPolicyLimits & {
  acceptedOrderEntriesToday: number;
  pending?: PendingRiskPolicy | null;
  updatedAt: string;
};

export type KillSwitch = { active: boolean; reason?: string | null; confirmedCancelled: number; unresolvedOrders: number };

export type AlertRuleType = "PRICE_CROSSING" | "ZONE_TOUCH" | "VOLUME_SPIKE";

export type AlertRule = {
  id: string;
  instrumentId: string;
  ruleType: AlertRuleType;
  direction?: "ABOVE" | "BELOW" | null;
  price?: DecimalValue | null;
  upperPrice?: DecimalValue | null;
  volumeMultiplier?: DecimalValue | null;
  lookbackCandles?: number | null;
  state: "PAUSED" | "WAITING_FOR_DATA" | "ARMED" | "TRIGGERED";
  reasonCode?: string | null;
  createdAt: string;
  triggeredAt?: string | null;
};

export type AlertEvent = {
  id: string;
  ruleId: string;
  instrumentId: string;
  ruleType: AlertRuleType;
  direction?: "ABOVE" | "BELOW" | null;
  thresholdPrice?: DecimalValue | null;
  upperPrice?: DecimalValue | null;
  triggerPrice: DecimalValue;
  volumeMultiplier?: DecimalValue | null;
  lookbackCandles?: number | null;
  triggerVolume?: number | null;
  referenceAverageVolume?: DecimalValue | null;
  candleTime: string;
  sourceId: string;
  createdAt: string;
  readAt?: string | null;
};

export type Order = {
  id: string;
  clientOrderId: string;
  instrumentId: string;
  side: "BUY" | "SELL";
  orderType: "MARKET" | "LIMIT" | "STOP_MARKET" | "STOP_LIMIT";
  quantity: number;
  limitPrice?: DecimalValue | null;
  triggerPrice?: DecimalValue | null;
  protectiveStopPrice: DecimalValue;
  status: string;
  brokerOrderId: string;
  mode: string;
  createdAt: string;
};

export type OrderRequest = {
  clientOrderId: string;
  instrumentId: string;
  side: "BUY" | "SELL";
  orderType: Order["orderType"];
  quantity: number;
  limitPrice?: number;
  triggerPrice?: number;
  protectiveStopPrice: number;
};

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

type Session = Pick<TokenResponse, "accessToken" | "refreshToken" | "userId">;

export class ApiClient {
  private readonly baseUrl: string;
  private readonly session: () => Session | null;
  private readonly updateSession: (tokens: TokenResponse | null) => void;
  private refreshInFlight: { refreshToken: string; promise: Promise<TokenResponse> } | null = null;

  constructor(baseUrl: string, session: () => Session | null,
              updateSession: (tokens: TokenResponse | null) => void) {
    this.baseUrl = baseUrl;
    this.session = session;
    this.updateSession = updateSession;
  }

  private async request<T>(path: string, init: RequestInit = {}, allowRefresh = true,
                           sessionOverride?: Session | null): Promise<T> {
    const method = (init.method ?? "GET").toUpperCase();
    const headers = new Headers(init.headers);
    headers.set("Accept", "application/json");
    if (init.body) headers.set("Content-Type", "application/json");
    const current = sessionOverride === undefined ? this.session() : sessionOverride;
    if (current?.accessToken) headers.set("Authorization", `Bearer ${current.accessToken}`);
    headers.set("X-Correlation-ID", crypto.randomUUID());

    let response: Response;
    try {
      const timeoutSignal = AbortSignal.timeout(15_000);
      const signal = init.signal ? AbortSignal.any([init.signal, timeoutSignal]) : timeoutSignal;
      response = await fetch(`${this.baseUrl.replace(/\/$/, "")}${path}`, { ...init, headers, cache: "no-store", signal });
    } catch {
      throw new ApiError(0, "API_UNREACHABLE", "Backend se connection nahi ho paaya. API URL aur local services check karein.");
    }
    if (response.status === 401 && method === "GET" && allowRefresh && current?.refreshToken) {
      const latest = this.session();
      if (!latest || latest.userId !== current.userId) {
        throw new ApiError(401, "SESSION_EXPIRED", "Session expire ho gaya. Dobara sign in karein.");
      }
      if (latest.refreshToken !== current.refreshToken) {
        return this.request<T>(path, init, false, latest);
      }
      try {
        const refreshed = await this.refreshSession(current.refreshToken);
        const active = this.session();
        if (!active || active.userId !== current.userId) {
          throw new ApiError(401, "SESSION_EXPIRED", "Session expire ho gaya. Dobara sign in karein.");
        }
        return this.request<T>(path, init, false, refreshed);
      } catch (cause) {
        if (cause instanceof ApiError) throw cause;
        throw new ApiError(0, "API_UNREACHABLE", "Backend se connection nahi ho paaya. API URL aur local services check karein.");
      }
    }
    if (!response.ok) {
      let payload: { detail?: { code?: string; message?: string }; code?: string } = {};
      try { payload = await response.json(); } catch { /* Keep a safe generic message. */ }
      const detail = payload.detail;
      const code = detail?.code ?? payload.code ?? `HTTP_${response.status}`;
      const message = detail?.message ?? safeMessage(code, response.status);
      throw new ApiError(response.status, code, message);
    }
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }

  private refreshSession(refreshToken: string): Promise<TokenResponse> {
    if (this.refreshInFlight?.refreshToken === refreshToken) return this.refreshInFlight.promise;

    const promise = this.request<TokenResponse>("/api/v1/auth/refresh", {
      method: "POST", body: JSON.stringify({ refreshToken }),
    }, false).then((tokens) => {
      if (this.session()?.refreshToken === refreshToken) this.updateSession(tokens);
      return tokens;
    }).catch((cause: unknown) => {
      if (cause instanceof ApiError && (cause.status === 401 || cause.status === 403)
        && this.session()?.refreshToken === refreshToken) {
        this.updateSession(null);
      }
      throw cause;
    });

    this.refreshInFlight = { refreshToken, promise };
    return promise;
  }

  private post<T>(path: string, body: unknown): Promise<T> {
    return this.request<T>(path, { method: "POST", body: JSON.stringify(body) }, false);
  }
  private put<T>(path: string, body: unknown): Promise<T> {
    return this.request<T>(path, { method: "PUT", body: JSON.stringify(body) }, false);
  }

  signup(email: string, password: string) { return this.post<TokenResponse>("/api/v1/auth/signup", { email, password }); }
  login(email: string, password: string) { return this.post<TokenResponse>("/api/v1/auth/login", { email, password }); }
  logout(refreshToken: string) { return this.post<void>("/api/v1/auth/logout", { refreshToken }); }
  instruments() { return this.request<Instrument[]>("/api/v1/market/instruments"); }
  quotes() { return this.request<Quote[]>("/api/v1/market/quotes"); }
  dataStatus() { return this.request<DemoStatus>("/api/v1/market/data-status"); }
  candles(instrumentId: string, from: string, to: string) {
    const query = new URLSearchParams({ instrumentId, timeframe: "1m", from, to, source: "SYNTHETIC_DEMO" });
    return this.request<CandleSeries>(`/api/v1/market/candles?${query}`);
  }
  analysis(instrumentId: string, timeframe: AnalysisSummary["timeframe"], from: string, to: string, source: string) {
    const query = new URLSearchParams({ timeframe, from, to, source });
    return this.request<AnalysisSummary>(`/api/v1/analysis/${encodeURIComponent(instrumentId)}?${query}`);
  }
  riskPolicy() { return this.request<RiskPolicy>("/api/v1/risk/policy"); }
  updateRiskPolicy(policy: RiskPolicyLimits) { return this.put<RiskPolicy>("/api/v1/risk/policy", policy); }
  killSwitch() { return this.request<KillSwitch>("/api/v1/risk/kill-switch"); }
  setKillSwitch(active: boolean, reason: string) { return this.put<KillSwitch>("/api/v1/risk/kill-switch", { active, reason }); }
  orders() { return this.request<Order[]>("/api/v1/orders"); }
  placeOrder(body: OrderRequest) { return this.post<Order>("/api/v1/orders", body); }
  cancelOrder(id: string) { return this.request<Order>(`/api/v1/orders/${id}/cancel`, { method: "POST" }, false); }
  alerts() { return this.request<AlertRule[]>("/api/v1/alerts"); }
  createAlert(instrumentId: string, direction: AlertRule["direction"], price: DecimalValue) {
    return this.post<AlertRule>("/api/v1/alerts", { instrumentId, ruleType: "PRICE_CROSSING", direction, price });
  }
  createZoneTouchAlert(instrumentId: string, lowerPrice: DecimalValue, upperPrice: DecimalValue) {
    return this.post<AlertRule>("/api/v1/alerts", { instrumentId, ruleType: "ZONE_TOUCH", price: lowerPrice, upperPrice });
  }
  createVolumeSpikeAlert(instrumentId: string, volumeMultiplier: DecimalValue) {
    return this.post<AlertRule>("/api/v1/alerts", { instrumentId, ruleType: "VOLUME_SPIKE", volumeMultiplier });
  }
  deleteAlert(id: string) { return this.request<void>(`/api/v1/alerts/${encodeURIComponent(id)}`, { method: "DELETE" }, false); }
  alertEvents(limit = 50) {
    const query = new URLSearchParams({ limit: String(limit) });
    return this.request<AlertEvent[]>(`/api/v1/alerts/events?${query}`);
  }
  markAlertEventRead(id: string) {
    return this.request<AlertEvent>(`/api/v1/alerts/events/${encodeURIComponent(id)}/read`, { method: "PATCH" }, false);
  }
}

function safeMessage(code: string, status: number): string {
  const messages: Record<string, string> = {
    ORDER_RISK_LIMIT: "Order configured risk limits se zyada hai.",
    KILL_SWITCH_ACTIVE: "Kill switch active hai. Order submit nahi hua.",
    INSTRUMENT_NOT_FOUND: "Instrument available nahi hai.",
    IDEMPOTENCY_KEY_REUSED: "Order request conflict hua. Orders list refresh karein.",
    ORDER_NOT_CANCELLABLE: "Yeh order ab cancel nahi ho sakta.",
    SIGNUP_CLOSED: "Personal account setup pehle hi complete hai. Existing account se sign in karein.",
  };
  return messages[code] ?? (status >= 500 ? "Server ne request complete nahi ki. Thodi der baad retry karein." : `Request reject hui (${code}).`);
}
