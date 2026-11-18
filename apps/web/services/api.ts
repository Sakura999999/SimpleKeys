import type {
  ApiResponse,
  PaginatedData,
  UserProfile,
  OrderBrief,
  OrderDetail,
  OrderStatus,
  PointRecord,
  PointsData,
  ProductCard,
  ProductDetail,
  ProductSpec,
  Category,
  Cart,
  CreateOrderRequest,
  CreateCartOrderRequest,
  CreateOrderResult,
  DeliverResult,
  SiteConfig,
  SiteConfigKV,
  DashboardStats,
  SalesTrend,
  CardKeyStockSummary,
  CardKeyListItem,
  CardImportBatch,
  OrderCardKey,
  AdminUserItem,
  AdminOrderItem,
  PaymentChannelItem,
  OperationLog,
  RiskConfig,
  WholesaleRule,
  CaptchaResult,
  AuthResult,
  CurrencyItem,
  TxidVerifyResult,
  EmailLookupSendResult,
  EmailLookupVerifyResult,
} from "@/types"

// ============================================================
// Config
// ============================================================

const API_BASE = process.env.NEXT_PUBLIC_API_BASE || "/api"

export interface SettlementQuoteRequest {
  product_id?: string; spec_id?: string | null; quantity?: number
  from_cart?: boolean; payment_method?: string
}
export interface SettlementQuote {
  quote_token: string; total_amount: number; settlement_currency: string
  payment_currency: string; payment_amount: number
  items: { key: string; product_id: string; title: string; source_currency: string; quantity: number;
    source_unit_price: number; exchange_rate: number; unit_price: number; subtotal: number }[]
}
export interface ExchangeRate { code: string; name: string; rate_to_cny: number | null; updated_at: string | null }
export const adminExchangeApi = {
  list: () => request<ExchangeRate[]>("/admin/currencies"),
  save: (rates: Record<string,string>) => request<null>("/admin/currencies/exchange-rates", { method: "PUT", body: JSON.stringify(rates) }),
}

// ============================================================
// Error
// ============================================================

class ApiError extends Error {
  code: number
  params?: Record<string, string | number>
  constructor(code: number, message: string, params?: Record<string, string | number>) {
    super(message)
    this.code = code
    this.params = params
    this.name = "ApiError"
  }
}

// ============================================================
// Token management
// ============================================================

function getToken(): string | null {
  if (typeof window === "undefined") return null
  return localStorage.getItem("auth_token")
}

export function setToken(token: string) {
  localStorage.setItem("auth_token", token)
}

export function clearToken() {
  localStorage.removeItem("auth_token")
}

/**
 * JWT 过期/无效时：清除本地登录态，跳转登录页
 * 使用防抖避免多个并发请求同时触发多次跳转
 */
let redirecting = false
function handleUnauthorized() {
  if (typeof window === "undefined" || redirecting) return
  redirecting = true
  clearToken()
  localStorage.removeItem("userProfile")
  const currentPath = window.location.pathname
  // 已经在登录页则不再跳转
  if (currentPath === "/login") {
    redirecting = false
    return
  }
  window.location.href = `/login?redirect=${encodeURIComponent(currentPath)}`
}

// ============================================================
// Session Token (guest cart)
// ============================================================

function getSessionToken(): string | null {
  if (typeof window === "undefined") return null
  let token = localStorage.getItem("session_token")
  if (!token) { token = typeof crypto.randomUUID === "function" ? crypto.randomUUID() : Array.from(crypto.getRandomValues(new Uint8Array(32)), b => b.toString(16).padStart(2, "0")).join(""); localStorage.setItem("session_token", token) }
  return token
}

function setSessionToken(token: string) {
  localStorage.setItem("session_token", token)
}

export function clearSessionToken() {
  localStorage.removeItem("session_token")
}

// ============================================================
// Guest order lookup grant（邮箱验证码换来的短期查单凭证）
// ============================================================
//
// 存 sessionStorage 而非 localStorage：关闭标签页即失效，符合"短期通行证"的定位。
// 同时记录 email，页面据此判断「当前输入的邮箱是否已经验证过」，
// 避免用户验证了 A 邮箱却在查 B 邮箱时被静默拒绝。

const LOOKUP_GRANT_KEY = "order_lookup_grant"

export interface StoredLookupGrant {
  token: string
  /** 已小写的邮箱，与后端规范化规则一致 */
  email: string
  /** 到期时间（epoch millis） */
  expiresAt: number
}

export function getLookupGrant(): StoredLookupGrant | null {
  if (typeof window === "undefined") return null
  try {
    const raw = sessionStorage.getItem(LOOKUP_GRANT_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as StoredLookupGrant
    if (!parsed?.token || !parsed?.email || !parsed?.expiresAt) return null
    if (parsed.expiresAt <= Date.now()) {
      sessionStorage.removeItem(LOOKUP_GRANT_KEY)
      return null
    }
    return parsed
  } catch {
    return null
  }
}

/** 取指定邮箱的有效凭证；邮箱不匹配视为没有凭证 */
export function getLookupGrantFor(email: string): StoredLookupGrant | null {
  const grant = getLookupGrant()
  if (!grant) return null
  return grant.email === email.trim().toLowerCase() ? grant : null
}

export function saveLookupGrant(grant: StoredLookupGrant): void {
  if (typeof window === "undefined") return
  try {
    sessionStorage.setItem(LOOKUP_GRANT_KEY, JSON.stringify(grant))
  } catch {
    /* 隐私模式下 sessionStorage 可能不可写，忽略即可 */
  }
}

export function clearLookupGrant(): void {
  if (typeof window === "undefined") return
  sessionStorage.removeItem(LOOKUP_GRANT_KEY)
}

// ============================================================
// Query builder
// ============================================================

function buildQuery(params: Record<string, string | number | boolean | undefined | null>): string {
  const sp = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") {
      sp.set(key, String(value))
    }
  }
  return sp.toString()
}

// ============================================================
// Device Fingerprint
// ============================================================

let cachedDeviceId: string | null = null

async function ensureDeviceId(): Promise<string> {
  if (cachedDeviceId) return cachedDeviceId
  if (typeof window === "undefined") return ""
  try {
    const { getDeviceId } = await import("@/lib/fingerprint")
    cachedDeviceId = await getDeviceId()
    return cachedDeviceId
  } catch {
    return ""
  }
}

// 启动时异步预热，不阻塞首屏
if (typeof window !== "undefined") {
  ensureDeviceId()
}

// ============================================================
// Turnstile token（模块级别，由页面组件设置）
// ============================================================

let pendingTurnstileToken: string | null = null

/** 设置 Turnstile token（在调用受保护 API 前由页面组件调用） */
export function setTurnstileHeaders(token: string) {
  pendingTurnstileToken = token
}

/** 消费并清除 Turnstile token（request 内部使用） */
function consumeTurnstileToken(): string | null {
  const t = pendingTurnstileToken
  pendingTurnstileToken = null
  return t
}

// ============================================================
// Core request
// ============================================================

async function request<T>(
  path: string,
  options: RequestInit = {}
): Promise<T> {
  const token = getToken()
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(options.headers as Record<string, string>),
  }

  if (token) {
    headers["Authorization"] = `Bearer ${token}`
  }
  // 始终发送 session token（购物车等功能需要：JWT 无效/过期时作为身份回退）
  const sessionToken = getSessionToken()
  if (sessionToken) {
    headers["X-Session-Token"] = sessionToken
  }

  // 设备指纹 — 始终发送
  const deviceId = cachedDeviceId || (await ensureDeviceId())
  if (deviceId) {
    headers["X-Device-Id"] = deviceId
  }

  // Turnstile token — 如果有 pending token 则发送（单次消费）
  const turnstileToken = consumeTurnstileToken()
  if (turnstileToken) {
    headers["X-Turnstile-Token"] = turnstileToken
  }

  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers,
  })

  // Capture session token from response
  const newSessionToken = res.headers.get("X-Session-Token")
  if (newSessionToken) {
    setSessionToken(newSessionToken)
  }

  if (!res.ok) {
    if (res.status === 401) {
      handleUnauthorized()
    }
    const body = await res.json().catch(() => ({ code: res.status, message: res.statusText }))
    throw new ApiError(body.code || res.status, body.message || res.statusText, body.params)
  }

  const body: ApiResponse<T> = await res.json()

  if (body.code !== 0) {
    throw new ApiError(body.code, body.message, body.params)
  }

  return body.data
}

async function uploadRequest<T>(path: string, formData: FormData): Promise<T> {
  const token = getToken()
  const headers: Record<string, string> = {}
  if (token) {
    headers["Authorization"] = `Bearer ${token}`
  }

  const res = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers,
    body: formData,
  })

  if (!res.ok) {
    if (res.status === 401) {
      handleUnauthorized()
    }
    const body = await res.json().catch(() => ({ code: res.status, message: res.statusText }))
    throw new ApiError(body.code || res.status, body.message || res.statusText, body.params)
  }

  const body: ApiResponse<T> = await res.json()

  if (body.code !== 0) {
    throw new ApiError(body.code, body.message, body.params)
  }

  return body.data
}

// ============================================================
// Mock Fallback
// ============================================================

/**
 * Wraps an API call with a mock fallback.
 * Only network-level errors (TypeError from fetch) trigger fallback.
 * Business errors (ApiError with code!=0) propagate normally.
 */
export async function withMockFallback<T>(
  apiCall: () => Promise<T>,
  mockFn: () => T
): Promise<T> {
  try {
    return await apiCall()
  } catch (err) {
    if (err instanceof ApiError) {
      throw err // business error — let UI handle it
    }
    // Network error (TypeError) or unexpected — fallback to mock
    console.warn("[API] Network error, falling back to mock data:", err)
    return mockFn()
  }
}


// ============================================================
// Auth
// ============================================================

export const authApi = {
  getCaptcha: () =>
    request<CaptchaResult>("/auth/captcha"),
  register: (data: { username: string; password: string; email: string; captcha_id: string; captcha: string }) =>
    request<AuthResult>("/auth/register", { method: "POST", body: JSON.stringify(data) }),
  login: (data: { account: string; password: string }) =>
    request<AuthResult>("/auth/login", { method: "POST", body: JSON.stringify(data) }),
  logout: () =>
    request<null>("/auth/logout", { method: "POST" }),
}

// ============================================================
// User
// ============================================================

export const userApi = {
  getProfile: () =>
    request<UserProfile>("/user/profile"),
  updatePassword: (data: { old_password: string; new_password: string }) =>
    request<null>("/user/password", { method: "PUT", body: JSON.stringify(data) }),
  getOrders: (params: { page?: number; page_size?: number; status?: string }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<OrderBrief>>(`/user/orders?${qs}`)
  },
  getPoints: (params: { page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PointsData>(`/user/points?${qs}`)
  },
}

// ============================================================
// Product
// ============================================================

export const productApi = {
  getList: (params: { page?: number; page_size?: number; category_id?: string; keyword?: string }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<ProductCard>>(`/products?${qs}`)
  },
  getDetail: (id: string) =>
    request<ProductDetail>(`/products/${id}`),
  getCategories: () =>
    request<Category[]>("/categories"),
}

// ============================================================
// Cart
// ============================================================

export const cartApi = {
  get: () =>
    request<Cart>("/cart"),
  addItem: (data: { product_id: string; spec_id: string | null; quantity: number }) =>
    request<null>("/cart/items", { method: "POST", body: JSON.stringify(data) }),
  updateItem: (itemId: string, quantity: number) =>
    request<null>(`/cart/items/${itemId}`, { method: "PUT", body: JSON.stringify({ quantity }) }),
  removeItem: (itemId: string) =>
    request<null>(`/cart/items/${itemId}`, { method: "DELETE" }),
}

// ============================================================
// Order
// ============================================================

export const orderApi = {
  quote: (data: SettlementQuoteRequest) =>
    request<SettlementQuote>("/orders/quote", { method: "POST", body: JSON.stringify(data) }),
  create: (data: CreateOrderRequest) =>
    request<CreateOrderResult>("/orders", { method: "POST", body: JSON.stringify(data) }),
  createFromCart: (data: CreateCartOrderRequest) =>
    request<CreateOrderResult>("/orders/from-cart", { method: "POST", body: JSON.stringify(data) }),
  getStatus: (orderId: string) =>
    request<{ order_id: string; status: OrderStatus; expires_at: string; remaining_seconds: number; payment_processing?: boolean; payment_url?: string }>(`/orders/${orderId}/status`),
  cancel: (orderId: string) =>
    request<{ status: OrderStatus }>(`/orders/${orderId}/cancel`, { method: "POST" }),
  refreshStatus: (orderId: string) =>
    request<{ status: OrderStatus; payment_processing?: boolean }>(`/orders/${orderId}/refresh`, { method: "POST" }),
  query: (data: { order_ids?: string[]; emails?: string[] }, opts?: { lookupToken?: string }) =>
    request<OrderBrief[]>("/orders/query", {
      method: "POST",
      body: JSON.stringify(data),
      headers: opts?.lookupToken ? { "X-Order-Lookup-Token": opts.lookupToken } : undefined,
    }),
  deliver: (data: { order_ids: string[] }, opts?: { lookupToken?: string }) =>
    request<DeliverResult[]>("/orders/deliver", {
      method: "POST",
      body: JSON.stringify(data),
      headers: opts?.lookupToken ? { "X-Order-Lookup-Token": opts.lookupToken } : undefined,
    }),
  /** 发送查单验证码。无论该邮箱有无订单，返回都相同。 */
  sendLookupCode: (email: string) =>
    request<EmailLookupSendResult>("/order-lookup/send", {
      method: "POST",
      body: JSON.stringify({ email }),
    }),
  /** 校验验证码，换取短期查单凭证 */
  verifyLookupCode: (email: string, code: string) =>
    request<EmailLookupVerifyResult>("/order-lookup/verify", {
      method: "POST",
      body: JSON.stringify({ email, code }),
    }),
  /** 主动作废当前查单凭证 */
  revokeLookupGrant: (lookupToken: string) =>
    request<void>("/order-lookup/revoke", {
      method: "POST",
      headers: { "X-Order-Lookup-Token": lookupToken },
    }),
  exportKeys: (orderId: string) =>
    request<string>(`/orders/${orderId}/export`),
  submitTxid: (orderId: string, txid: string) =>
    request<TxidVerifyResult>(`/orders/${orderId}/txid-verify`, {
      method: "POST",
      body: JSON.stringify({ txid }),
    }),
  repay: (orderId: string, device?: string) =>
    request<import("@/types").PaymentCreateResult>(`/orders/${orderId}/repay`, {
      method: "POST",
      body: JSON.stringify({ device }),
    }),
}

// ============================================================
// Site Config (public)
// ============================================================

export const siteApi = {
  getConfig: () =>
    request<SiteConfig>("/site/config"),
}

// ============================================================
// Payment Channels (public, for store display)
// ============================================================

export const paymentApi = {
  getChannels: () =>
    request<PaymentChannelItem[]>("/payment-channels"),
}

// ============================================================
// Currencies (public)
// ============================================================

export const currencyApi = {
  getList: () =>
    request<CurrencyItem[]>("/currencies"),
}

// ============================================================
// Admin Dashboard
// ============================================================

export const adminDashboardApi = {
  getStats: () =>
    request<DashboardStats>("/admin/dashboard"),
  getSalesTrend: (params: { period?: string; start_date?: string; end_date?: string }) => {
    const qs = buildQuery(params)
    return request<SalesTrend[]>(`/admin/dashboard/sales-trend?${qs}`)
  },
}

// ============================================================
// Admin Product
// ============================================================

export const adminProductApi = {
  getList: (params: { page?: number; page_size?: number; category_id?: string; is_enabled?: number; keyword?: string }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<ProductDetail>>(`/admin/products?${qs}`)
  },
  getDetail: (id: string) =>
    request<ProductDetail>(`/admin/products/${id}`),
  create: (data: {
    title: string; description?: string; detail_md?: string; cover_url?: string;
    title_en?: string; description_en?: string; detail_md_en?: string;
    base_price: number; category_id: string; low_stock_threshold?: number;
    wholesale_enabled?: boolean; is_enabled?: boolean; sort_order?: number
  }) =>
    request<ProductDetail>("/admin/products", { method: "POST", body: JSON.stringify(data) }),
  update: (id: string, data: Partial<{
    title: string; description: string; detail_md: string; cover_url: string;
    title_en: string; description_en: string; detail_md_en: string;
    base_price: number; category_id: string; low_stock_threshold: number;
    wholesale_enabled: boolean; is_enabled: boolean; sort_order: number
  }>) =>
    request<null>(`/admin/products/${id}`, { method: "PUT", body: JSON.stringify(data) }),
  delete: (id: string) =>
    request<null>(`/admin/products/${id}`, { method: "DELETE" }),
  // Specs
  getSpecs: (productId: string) =>
    request<ProductSpec[]>(`/admin/products/${productId}/specs`),
  addSpec: (productId: string, data: { name: string; price: number; is_visible?: boolean; sort_order?: number }) =>
    request<ProductSpec>(`/admin/products/${productId}/specs`, { method: "POST", body: JSON.stringify(data) }),
  updateSpec: (productId: string, specId: string, data: Partial<{ name: string; price: number; is_visible: boolean; sort_order: number }>) =>
    request<null>(`/admin/products/${productId}/specs/${specId}`, { method: "PUT", body: JSON.stringify(data) }),
  deleteSpec: (productId: string, specId: string) =>
    request<null>(`/admin/products/${productId}/specs/${specId}`, { method: "DELETE" }),
  // Wholesale rules
  getWholesaleRules: (productId: string) =>
    request<WholesaleRule[]>(`/admin/products/${productId}/wholesale-rules`),
  setWholesaleRules: (productId: string, data: { spec_id?: string | null; rules: { min_quantity: number; unit_price: number }[] }) =>
    request<null>(`/admin/products/${productId}/wholesale-rules`, { method: "POST", body: JSON.stringify(data) }),
  // Image upload
  uploadImage: (file: File) => {
    const formData = new FormData()
    formData.append("file", file)
    return uploadRequest<{ url: string }>("/upload/image", formData)
  },
}

// ============================================================
// Admin Category
// ============================================================

export const adminCategoryApi = {
  reorder: (ids: string[]) =>
    request<null>("/admin/categories/reorder", { method: "PUT", body: JSON.stringify(ids) }),
  getList: () =>
    request<Category[]>("/admin/categories"),
  create: (data: { name: string; sort_order?: number }) =>
    request<null>("/admin/categories", { method: "POST", body: JSON.stringify(data) }),
  update: (id: string, data: { name?: string; sort_order?: number }) =>
    request<null>(`/admin/categories/${id}`, { method: "PUT", body: JSON.stringify(data) }),
  delete: (id: string) =>
    request<null>(`/admin/categories/${id}`, { method: "DELETE" }),
}

// ============================================================
// Admin Card Keys
// ============================================================

export const adminCardKeyApi = {
  getList: (params: { product_id: string; spec_id?: string | null; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<CardKeyListItem>>(`/admin/card-keys/list?${qs}`)
  },
  getStock: (params?: { product_id?: string; spec_id?: string }) => {
    const qs = buildQuery(params ?? {})
    return request<CardKeyStockSummary[]>(`/admin/card-keys/stock?${qs}`)
  },
  import: (data: { product_id: string; spec_id?: string | null; content: string }) =>
    request<CardImportBatch>("/admin/card-keys/import", { method: "POST", body: JSON.stringify(data) }),
  getImportBatches: (params: { product_id?: string; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<CardImportBatch>>(`/admin/card-keys/import-batches?${qs}`)
  },
  invalidate: (id: string) =>
    request<null>(`/admin/card-keys/${id}/invalidate`, { method: "POST" }),
  batchInvalidate: (params: { product_id: string; spec_id?: string | null }) => {
    const qs = buildQuery(params)
    return request<{ invalidated_count: number }>(`/admin/card-keys/batch-invalidate?${qs}`, { method: "POST" })
  },
  getByOrder: (orderId: string) =>
    request<OrderCardKey[]>(`/admin/card-keys/by-order/${orderId}`),
}

// ============================================================
// Admin Order
// ============================================================

export const adminOrderApi = {
  getList: (params: {
    page?: number; page_size?: number; status?: string; order_type?: string;
    payment_method?: string; is_risk_flagged?: number; keyword?: string
  }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<AdminOrderItem>>(`/admin/orders?${qs}`)
  },
  getDetail: (id: string) =>
    request<AdminOrderItem>(`/admin/orders/${id}`),
  retryDelivery: (id: string) => request<null>(`/admin/orders/${id}/retry-delivery`, { method: "POST" }),
  requestRefund: (id: string, reason: string) => request<unknown>(`/admin/orders/${id}/refund-request`, { method: "POST", body: JSON.stringify({ reason }) }),
  recordRefund: (id: string, reference: string) => request<null>(`/admin/orders/${id}/refund-record`, { method: "POST", body: JSON.stringify({ reference }) }),
  markPaid: (id: string) =>
    request<null>(`/admin/orders/${id}/mark-paid`, { method: "POST" }),
}

// ============================================================
// Admin User
// ============================================================

export const adminUserApi = {
  getList: (params: { page?: number; page_size?: number; keyword?: string }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<AdminUserItem>>(`/admin/users?${qs}`)
  },
  toggleStatus: (id: string, isDeleted: 0 | 1) =>
    request<null>(`/admin/users/${id}/toggle`, { method: "POST", body: JSON.stringify({ is_deleted: isDeleted }) }),
}

// ============================================================
// Admin Payment
// ============================================================

export const adminPaymentApi = {
  getList: () =>
    request<PaymentChannelItem[]>("/admin/payment-channels"),
  create: (data: { channel_code: string; channel_name: string; provider_type: string; config_data?: Record<string, unknown>; is_enabled?: boolean; sort_order?: number }) =>
    request<null>("/admin/payment-channels", { method: "POST", body: JSON.stringify(data) }),
  update: (id: string, data: Partial<{ channel_name: string; provider_type: string; config_data: Record<string, unknown>; is_enabled: boolean; sort_order: number }>) =>
    request<null>(`/admin/payment-channels/${id}`, { method: "PUT", body: JSON.stringify(data) }),
  delete: (id: string) =>
    request<null>(`/admin/payment-channels/${id}`, { method: "DELETE" }),
}

// ============================================================
// Admin Config
// ============================================================

export const adminConfigApi = {
  get: () =>
    request<SiteConfigKV[]>("/admin/site-config"),
  update: (data: { configs: { config_key: string; config_value: string }[] }) =>
    request<null>("/admin/site-config", { method: "PUT", body: JSON.stringify(data) }),
  toggleMaintenance: (enabled: boolean) =>
    request<null>("/admin/site-config/maintenance", { method: "POST", body: JSON.stringify({ enabled }) }),
}

// ============================================================
// Admin Log
// ============================================================

export const adminLogApi = {
  getList: (params: { page?: number; page_size?: number; user_id?: string; action?: string; target_type?: string; start_date?: string; end_date?: string }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<OperationLog>>(`/admin/operation-logs?${qs}`)
  },
}

// ============================================================
// Admin Risk
// ============================================================

export const adminRiskApi = {
  getConfig: () =>
    request<RiskConfig>("/admin/risk-config"),
  updateConfig: (data: Partial<RiskConfig>) =>
    request<null>("/admin/risk-config", { method: "PUT", body: JSON.stringify(data) }),
  getFlaggedOrders: (params: { page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<AdminOrderItem>>(`/admin/risk/flagged-orders?${qs}`)
  },
}

// ============================================================
// Admin TXID Review
// ============================================================

export const adminTxidReviewApi = {
  getList: (params: { status?: string; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<import("@/types").UnmatchedTransaction>>(`/admin/txid-reviews?${qs}`)
  },
  approve: (id: string) =>
    request<null>(`/admin/txid-reviews/${id}/approve`, { method: "POST" }),
  reject: (id: string, reason: string) =>
    request<null>(`/admin/txid-reviews/${id}/reject`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    }),
}

export { ApiError }

// ============================================================
// Error code → i18n key mapping
// ============================================================

// 只映射用户可见的前台错误码，后台管理页面不映射（后台统一中文界面）
const ERROR_CODE_I18N: Record<number, string> = {
  // 通用
  10002: "error.unauthorized",
  10003: "error.forbidden",
  10005: "error.tooManyRequests",
  10006: "error.serverError",
  10007: "error.maintenance",
  // Auth
  20001: "error.usernameExists",
  20002: "error.emailExists",
  20003: "error.captchaInvalid",
  20004: "error.invalidCredentials",
  20005: "error.oldPasswordWrong",
  20006: "error.accountDisabled",
  // Product
  30001: "error.productNotFound",
  30002: "error.insufficientStock",
  30003: "error.specNotFound",
  30004: "error.purchaseLimitExceeded",
  30005: "error.specHasCardKeys",
  30006: "error.specNameDuplicate",
  // Order
  40001: "error.orderNotFound",
  40002: "error.orderExpired",
  40003: "error.orderNotPaid",
  40004: "error.orderOutOfStock",
  40005: "error.orderProcessing",
  40006: "error.purchaseLimitPerUser",
  40007: "error.unpaidOrderExists",
  40008: "error.cartEmpty",
  // Payment
  50001: "error.channelUnavailable",
  50003: "error.txidInvalidFormat",
  50004: "error.txidAlreadyUsed",
  50005: "error.txidVerifyFailed",
  50006: "error.orderNotUsdt",
}

/**
 * 从 API 错误中提取用户可见的提示文案。
 * 已映射 → i18n 文案 + 参数插值
 * 未映射 → 后端原始 message（兜底，不丢信息）
 * 非 ApiError → 原始 error message
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function getApiErrorMessage(err: unknown, t: (key: any) => string): string {
  if (err instanceof ApiError) {
    const i18nKey = ERROR_CODE_I18N[err.code]
    if (i18nKey) {
      let msg = t(i18nKey)
      // 参数插值: {available} → 实际值
      if (err.params) {
        for (const [k, v] of Object.entries(err.params)) {
          msg = msg.replace(`{${k}}`, String(v))
        }
      }
      return msg
    }
    // 未映射的 code → 直接返回后端原始 message（兜底，不丢信息）
    return err.message
  }
  return err instanceof Error ? err.message : t("common.error")
}

export interface BusinessRecord {
  id: string; customer_name: string; purchase_content: string; bd: string | null; channel: string | null;
  quantity: number; unit_price_usd: number | null; unit_price_cny: number | null; unit_cost_usd: number | null;
  received: boolean; ordered_at: string | null; received_at: string | null; notes: string | null;
  created_at: string; updated_at: string;
}
export type BusinessRecordInput = Omit<BusinessRecord, "id" | "created_at" | "updated_at">
export const adminBusinessApi = {
  list: (params: Record<string, string | number | boolean | undefined>) => request<{ content: BusinessRecord[]; total_elements: number; total_pages: number }>(`/admin/business-records?${buildQuery(params)}`),
  create: (data: BusinessRecordInput) => request<BusinessRecord>("/admin/business-records", { method: "POST", body: JSON.stringify(data) }),
  update: (id: string, data: BusinessRecordInput) => request<BusinessRecord>(`/admin/business-records/${id}`, { method: "PUT", body: JSON.stringify(data) }),
}

export interface PendingPaymentEvent {
  id: string
  order_id: string
  channel_code: string
  retry_count: number
  next_attempt_at: string
  last_error?: string
}
export const adminPaymentInboxApi = {
  pending: () => request<{ list: PendingPaymentEvent[]; total: number }>("/admin/payment-inbox"),
  retry: (id: string) => request<boolean>(`/admin/payment-inbox/${id}/retry`, { method: "POST" }),
}
