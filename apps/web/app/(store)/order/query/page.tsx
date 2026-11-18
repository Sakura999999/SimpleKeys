"use client"

import { useState, useEffect, useCallback, useRef } from "react"
import { useSearchParams } from "next/navigation"
import Link from "next/link"
import { Search, Copy, Download, FileText, CheckCircle2, X, Clock, HelpCircle, ExternalLink, Loader2, AlertCircle, Info, Mail, ShieldCheck } from "lucide-react"
import { toast } from "sonner"
import { useLocale, useSiteConfig } from "@/lib/context"
import type { TranslationKey } from "@/lib/i18n"
import {
  orderApi,
  withMockFallback,
  getApiErrorMessage,
  getLookupGrantFor,
  saveLookupGrant,
} from "@/services/api"
import { mockQueryOrders, mockDeliver } from "@/lib/mock-data"
import { OrderStatusBadge } from "@/components/shared/order-status-badge"
import { PaymentIcon, getPaymentLabel } from "@/components/shared/payment-icon"
import type { OrderBrief, DeliverResult, TxidVerifyResult } from "@/types"
import { cn, stripInvisible } from "@/lib/utils"
import { Modal } from "@/components/ui/modal"

interface RecentQuery {
  value: string
  timestamp: number
}

/**
 * 邮箱查单需要先验证邮箱归属。这里只表示「当前被拦住了」，
 * 不放任何订单信息——拦住的理由本身不能泄露邮箱下有没有订单。
 */
type EmailGate = { mode: "verify" | "disabled"; email: string }

/** 将后端返回的 TXID 验证错误码转为 i18n 翻译文本 */
function formatTxidReason(reason: string, t: (key: TranslationKey) => string): string {
  // 带参数的错误码格式: "AMOUNT_TOO_LARGE:1.23" 或 "AMOUNT_MISMATCH:0.5"
  const colonIndex = reason.indexOf(":")
  const code = colonIndex > 0 ? reason.substring(0, colonIndex) : reason
  const param = colonIndex > 0 ? reason.substring(colonIndex + 1) : ""

  const i18nKey = `order.usdt.reason.${code}` as TranslationKey
  const translated = t(i18nKey)

  // 如果 i18n 没有对应 key，返回值等于 key 本身，此时回退显示原始 reason
  if (translated === i18nKey) return reason

  return param ? translated.replace("{diff}", param) : translated
}

export default function OrderQueryPage() {
  const { t } = useLocale()
  const { config } = useSiteConfig()
  const searchParams = useSearchParams()
  const [queryValue, setQueryValue] = useState("")
  const [orders, setOrders] = useState<OrderBrief[]>([])
  const [deliverResults, setDeliverResults] = useState<DeliverResult[]>([])
  const [isSearching, setIsSearching] = useState(false)
  const [searched, setSearched] = useState(false)
  const [recentQueries, setRecentQueries] = useState<RecentQuery[]>([])
  const [deleteConfirm, setDeleteConfirm] = useState<string | null>(null)
  const initRef = useRef(false)

  // USDT TXID submission state
  const [txidExpandedOrder, setTxidExpandedOrder] = useState<string | null>(null)
  const [txidInput, setTxidInput] = useState("")
  const [txidSubmitting, setTxidSubmitting] = useState(false)
  const [txidResult, setTxidResult] = useState<Record<string, TxidVerifyResult>>({})

  // 邮箱验证（跨设备找回访客订单）
  const [emailGate, setEmailGate] = useState<EmailGate | null>(null)
  const [verifyCode, setVerifyCode] = useState("")
  const [codeSent, setCodeSent] = useState(false)
  const [sendingCode, setSendingCode] = useState(false)
  const [verifying, setVerifying] = useState(false)
  const [resendIn, setResendIn] = useState(0)
  /** 当前生效的已验证邮箱，用于顶部「已验证」提示 */
  const [verifiedEmail, setVerifiedEmail] = useState<string | null>(null)
  /** 「另有 N 笔订单属于注册账号」的提示 */
  const [registeredNotice, setRegisteredNotice] = useState<string | null>(null)

  const emailVerificationEnabled = config?.email_verification_enabled === true

  // 重发验证码倒计时
  useEffect(() => {
    if (resendIn <= 0) return
    const timer = setTimeout(() => setResendIn(v => Math.max(0, v - 1)), 1000)
    return () => clearTimeout(timer)
  }, [resendIn])

  // Load recent queries + handle URL params on mount
  useEffect(() => {
    if (initRef.current) return
    initRef.current = true

    let recent: RecentQuery[] = []
    try {
      const saved = localStorage.getItem("recentOrderQueries")
      if (saved) recent = JSON.parse(saved)
    } catch { /* empty */ }
    setRecentQueries(recent)

    // Auto-query from URL params
    const orderIdParam = searchParams.get("orderId")
    if (orderIdParam) {
      setQueryValue(orderIdParam)
      doSearch(orderIdParam, recent)
    }
  }, [searchParams])

  // Core search logic: query → deliver for DELIVERED orders
  const doSearch = useCallback(async (searchValue: string, currentRecent?: RecentQuery[]) => {
    const trimmed = searchValue.trim()
    if (!trimmed) {
      setSearched(true)
      setOrders([])
      setDeliverResults([])
      return
    }

    const isEmail = trimmed.includes("@")
    const lookupEmail = isEmail ? trimmed.toLowerCase() : ""
    // 邮箱查询前的两道门。订单号查询完全不受影响——沿用「订单号即凭证」的既有行为。
    // config 尚未加载时不拦（此时后端没有凭证只会返回同会话订单，不会多给任何东西）。
    if (isEmail && config) {
      if (!emailVerificationEnabled) {
        setOrders([])
        setDeliverResults([])
        setSearched(true)
        setEmailGate({ mode: "disabled", email: lookupEmail })
        return
      }
      if (!getLookupGrantFor(lookupEmail)) {
        setOrders([])
        setDeliverResults([])
        setSearched(false)
        setEmailGate({ mode: "verify", email: lookupEmail })
        setVerifyCode("")
        setCodeSent(false)
        setResendIn(0)
        return
      }
    }
    setEmailGate(null)

    setIsSearching(true)
    setOrders([])
    setDeliverResults([])

    try {
      const lookupToken = isEmail ? getLookupGrantFor(lookupEmail)?.token : undefined
      const queryParams = isEmail
        ? { emails: [lookupEmail] }
        : { order_ids: [trimmed] }

      const found = await withMockFallback(
        () => orderApi.query(queryParams, lookupToken ? { lookupToken } : undefined),
        () => mockQueryOrders(queryParams)
      )

      // Auto-deliver: PAID 触发发货分配卡密，DELIVERED 幂等返回已分配卡密
      let finalOrders = found
      let finalDeliver: Awaited<ReturnType<typeof orderApi.deliver>> = []
      const deliverableIds = found.filter(o => o.status === "PAID" || o.status === "DELIVERED").map(o => o.id)
      if (deliverableIds.length > 0) {
        finalDeliver = await withMockFallback(
          () => orderApi.deliver({ order_ids: deliverableIds }, lookupToken ? { lookupToken } : undefined),
          () => mockDeliver(deliverableIds)
        )
        // deliver 可能将 PAID 变为 DELIVERED，同步更新状态
        const statusMap = new Map(finalDeliver.map(d => [d.order_id, d.status]))
        finalOrders = found.map(o => {
          const newStatus = statusMap.get(o.id)
          return newStatus && newStatus !== o.status ? { ...o, status: newStatus } : o
        })
      }

      // 所有数据就绪后一次性渲染，避免中间态抖动
      setOrders(finalOrders)
      setDeliverResults(finalDeliver)

      // Save to recent queries
      if (found.length > 0) {
        setRecentQueries(prev => {
          const base = currentRecent ?? prev
          const entry: RecentQuery = { value: trimmed, timestamp: Date.now() }
          const updated = [entry, ...base.filter(q => q.value !== trimmed)].slice(0, 5)
          localStorage.setItem("recentOrderQueries", JSON.stringify(updated))
          return updated
        })
      }

    } catch (err) {
      // 40001 = ORDER_NOT_FOUND，沿用下方空状态提示即可。
      // 其余业务错误必须告知用户 —— 例如订单属于注册账号需登录（403），
      // 静默失败会让用户误以为订单不存在。
      const code = (err as { code?: number })?.code
      if (code !== 40001) {
        toast.error(getApiErrorMessage(err, t))
      }
    } finally {
      setSearched(true)
      setIsSearching(false)
    }
  }, [t, config, emailVerificationEnabled])

  const handleSearch = useCallback((value?: string) => {
    doSearch(value || queryValue)
  }, [queryValue, doSearch])

  const confirmRemoveQuery = (value: string) => {
    setRecentQueries(prev => {
      const updated = prev.filter(q => q.value !== value)
      localStorage.setItem("recentOrderQueries", JSON.stringify(updated))
      return updated
    })
    setDeleteConfirm(null)
  }

  const formatTimeAgo = (timestamp: number) => {
    const diff = Date.now() - timestamp
    const hours = Math.floor(diff / (1000 * 60 * 60))
    const days = Math.floor(hours / 24)
    if (days > 0) return `${days} ${t("order.daysAgo")}`
    if (hours > 0) return `${hours} ${t("order.hoursAgo")}`
    return `1 ${t("order.hoursAgo")}`
  }

  const quickQuery = (value: string) => {
    setQueryValue(value)
    doSearch(value)
  }

  // ── 邮箱验证流程 ──

  const sendLookupCode = async () => {
    if (!emailGate || emailGate.mode !== "verify" || sendingCode || resendIn > 0) return
    setSendingCode(true)
    try {
      const result = await orderApi.sendLookupCode(emailGate.email)
      setCodeSent(true)
      setResendIn(result.retry_after > 0 ? result.retry_after : 60)
      // 文案用「如果存在可找回的订单」：该邮箱有无订单不能对外区分
      toast.success(t("order.verify.codeSent"))
    } catch (err) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setSendingCode(false)
    }
  }

  const submitLookupCode = async () => {
    if (!emailGate || emailGate.mode !== "verify" || verifying) return
    const code = verifyCode.trim()
    if (!/^\d{6}$/.test(code)) {
      toast.error(t("order.verify.codeFormat"))
      return
    }
    setVerifying(true)
    try {
      const result = await orderApi.verifyLookupCode(emailGate.email, code)
      saveLookupGrant({ token: result.token, email: emailGate.email, expiresAt: result.expires_at })
      setVerifiedEmail(emailGate.email)
      setRegisteredNotice(
        result.registered_orders > 0
          ? t("order.verify.registeredNotice").replace("{n}", String(result.registered_orders))
          : null
      )
      const email = emailGate.email
      setEmailGate(null)
      setVerifyCode("")
      setCodeSent(false)
      setResendIn(0)
      toast.success(t("order.verify.success"))
      // 凭证已写入 sessionStorage，这里重新走一次查询即可
      doSearch(email)
    } catch (err) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setVerifying(false)
    }
  }

  const getDeliverForOrder = (orderId: string) => {
    return deliverResults.find(d => d.order_id === orderId)
  }

  const copyToClipboard = (text: string) => {
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(text).then(
        () => toast.success(t("order.copied")),
        () => fallbackCopy(text)
      )
    } else {
      fallbackCopy(text)
    }
    function fallbackCopy(val: string) {
      const ta = document.createElement("textarea")
      ta.value = val
      ta.style.position = "fixed"
      ta.style.opacity = "0"
      document.body.appendChild(ta)
      ta.select()
      document.execCommand("copy")
      document.body.removeChild(ta)
      toast.success(t("order.copied"))
    }
  }

  const copyAllKeys = (deliver: DeliverResult) => {
    const allKeys = deliver.groups.flatMap(g => g.card_keys).join("\n")
    copyToClipboard(allKeys)
  }

  const downloadKeys = (deliver: DeliverResult) => {
    const lines: string[] = []
    deliver.groups.forEach(g => {
      lines.push(`--- ${g.product_title}${g.spec_name ? ` (${g.spec_name})` : ""} ---`)
      g.card_keys.forEach(k => lines.push(k))
      lines.push("")
    })
    const blob = new Blob([lines.join("\n")], { type: "text/plain" })
    const url = URL.createObjectURL(blob)
    const a = document.createElement("a")
    a.href = url
    a.download = `order-${deliver.order_id.slice(0, 8)}-keys.txt`
    a.click()
    URL.revokeObjectURL(url)
  }

  const handleTxidSubmit = useCallback(async (orderId: string) => {
    const txid = txidInput.trim()
    if (!txid) return
    setTxidSubmitting(true)
    try {
      const result = await orderApi.submitTxid(orderId, txid)
      setTxidResult(prev => ({ ...prev, [orderId]: result }))
      if (result.result === "AUTO_APPROVED") {
        toast.success(t("order.usdt.autoApproved"))
        // Re-query to refresh order status
        doSearch(queryValue)
      }
    } catch (err) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setTxidSubmitting(false)
    }
  }, [txidInput, t, doSearch, queryValue])

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-6">
      {/* Header */}
      <div>
        <h1 className="text-xl font-bold text-foreground">{t("order.query")}</h1>
        <p className="mt-1 text-sm text-muted-foreground">{t("order.queryDesc")}</p>
      </div>

      {/* Search Form */}
      <div className="rounded-lg border border-border bg-card p-4">
        <div className="flex gap-2">
          <div className="relative flex-1">
            <input
              type="text"
              placeholder={t("order.queryPlaceholder")}
              value={queryValue}
              onChange={(e) => setQueryValue(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && handleSearch()}
              className="h-10 w-full rounded-lg border border-input bg-background px-3 pr-8 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            />
            {queryValue && (
              <button
                type="button"
                onClick={() => {
                  setQueryValue("")
                  setOrders([])
                  setDeliverResults([])
                  setSearched(false)
                }}
                className="absolute right-2 top-1/2 -translate-y-1/2 rounded p-0.5 text-muted-foreground transition-colors hover:text-foreground"
              >
                <X className="h-4 w-4" />
              </button>
            )}
          </div>
          <button
            onClick={() => handleSearch()}
            disabled={isSearching}
            className="inline-flex h-10 items-center gap-2 rounded-lg bg-primary px-5 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:pointer-events-none disabled:opacity-50"
          >
            <Search className="h-4 w-4" />
            {t("order.search")}
          </button>
        </div>
      </div>

      {/* 邮箱查单入口关闭时的明确提示 */}
      {emailGate?.mode === "disabled" && (
        <div className="flex items-start gap-3 rounded-lg border border-border bg-muted/40 p-4">
          <Info className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />
          <div>
            <p className="text-sm font-medium text-foreground">{t("order.verify.disabledTitle")}</p>
            <p className="mt-1 text-sm text-muted-foreground">{t("order.verify.disabledDesc")}</p>
          </div>
        </div>
      )}

      {/* 邮箱验证步骤 */}
      {emailGate?.mode === "verify" && (
        <div className="rounded-lg border border-border bg-card p-4">
          <div className="flex items-start gap-3">
            <Mail className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
            <div className="min-w-0 flex-1">
              <p className="text-sm font-medium text-foreground">{t("order.verify.title")}</p>
              <p className="mt-1 text-sm text-muted-foreground">{t("order.verify.desc")}</p>
              <p className="mt-2 break-all font-mono text-sm text-foreground">{emailGate.email}</p>
            </div>
          </div>

          <div className="mt-4 flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={sendLookupCode}
              disabled={sendingCode || resendIn > 0}
              className="inline-flex h-9 items-center gap-2 rounded-lg border border-border bg-background px-4 text-sm font-medium text-foreground transition-colors hover:bg-accent disabled:pointer-events-none disabled:opacity-50"
            >
              {sendingCode ? <Loader2 className="h-4 w-4 animate-spin" /> : <Mail className="h-4 w-4" />}
              {resendIn > 0
                ? `${resendIn}s`
                : codeSent
                  ? t("order.verify.resend")
                  : t("order.verify.sendCode")}
            </button>

            {codeSent && (
              <>
                <input
                  type="text"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  maxLength={6}
                  value={verifyCode}
                  onChange={(e) => setVerifyCode(e.target.value.replace(/\D/g, ""))}
                  onKeyDown={(e) => e.key === "Enter" && submitLookupCode()}
                  placeholder={t("order.verify.codePlaceholder")}
                  className="h-9 w-36 rounded-lg border border-input bg-background px-3 text-center font-mono text-sm tracking-[0.3em] text-foreground placeholder:tracking-normal placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                />
                <button
                  type="button"
                  onClick={submitLookupCode}
                  disabled={verifying || verifyCode.length !== 6}
                  className="inline-flex h-9 items-center gap-2 rounded-lg bg-primary px-4 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:pointer-events-none disabled:opacity-50"
                >
                  {verifying ? <Loader2 className="h-4 w-4 animate-spin" /> : <ShieldCheck className="h-4 w-4" />}
                  {verifying ? t("order.verify.verifying") : t("order.verify.submit")}
                </button>
              </>
            )}
          </div>

          <p className="mt-3 text-xs leading-relaxed text-muted-foreground">{t("order.verify.hint")}</p>
        </div>
      )}

      {/* 已验证状态 + 注册订单提示 */}
      {verifiedEmail && !emailGate && (
        <div className="flex items-start gap-2 rounded-lg border border-border bg-muted/40 p-3">
          <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
          <div className="text-xs leading-relaxed text-muted-foreground">
            <p>{t("order.verify.verifiedAs").replace("{email}", verifiedEmail)}</p>
            {registeredNotice && <p className="mt-1 text-foreground">{registeredNotice}</p>}
          </div>
        </div>
      )}

      {/* Results — 有查询结果时优先展示在最近订单上方 */}
      {isSearching && (
        <div className="flex items-center justify-center py-12">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        </div>
      )}

      {searched && !isSearching && orders.length === 0 && (
        <div className="flex flex-col items-center justify-center py-12">
          <FileText className="mb-3 h-10 w-10 text-muted-foreground/30" />
          <p className="text-sm text-muted-foreground">{t("order.noResult")}</p>
        </div>
      )}

      {orders.map((order) => {
        const deliver = getDeliverForOrder(order.id)
        return (
          <div key={order.id} className="flex flex-col gap-4 rounded-lg border border-border bg-card p-5">
            {/* Order Header */}
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-muted-foreground">{t("payment.orderNo")}</p>
                <span className="flex items-center gap-1">
                  <span
                    className="cursor-pointer font-mono text-sm font-medium text-foreground underline-offset-4 transition-all hover:underline hover:text-primary"
                    title={order.id}
                    onClick={() => copyToClipboard(order.id)}
                  >
                    {order.id.length > 30 ? `${order.id.slice(0, 12)}...${order.id.slice(-8)}` : order.id}
                  </span>
                  <button
                    type="button"
                    onClick={() => copyToClipboard(order.id)}
                    className="text-muted-foreground transition-colors hover:text-foreground"
                  >
                    <Copy className="h-3.5 w-3.5" />
                  </button>
                </span>
              </div>
              <OrderStatusBadge status={order.status} paymentProcessing={order.payment_processing} />
            </div>

            <hr className="border-border" />

            {/* Order Summary */}
            <div className="flex flex-col gap-1 text-sm">
              <div className="flex justify-between">
                <span className="text-muted-foreground">{t("order.amount")}</span>
                <span className="font-bold text-foreground">
                  {"\u00A5"}{order.actual_amount.toFixed(2)}
                </span>
              </div>
              {/* 支付方式行 */}
              {order.payment_method && (
                <div className="flex justify-between">
                  <span className="text-muted-foreground">{t("order.paymentMethod")}</span>
                  <span className="flex items-center gap-1.5 text-foreground">
                    <PaymentIcon method={order.payment_method} className="h-4 w-4" />
                    {getPaymentLabel(order.payment_method, t)}
                  </span>
                </div>
              )}
              <div className="flex justify-between">
                <span className="text-muted-foreground">{t("order.createdAt")}</span>
                <span className="text-foreground">
                  {new Date(order.created_at).toLocaleString()}
                </span>
              </div>
              {/* USDT 交易哈希（已支付/已发货时显示） */}
              {order.payment_method?.startsWith("usdt_") && order.usdt_tx_id && (
                <div className="flex justify-between">
                  <span className="text-muted-foreground">{t("order.usdt.txHash")}</span>
                  <span className="flex items-center gap-1 font-mono text-xs text-foreground">
                    {order.usdt_tx_id.length > 20
                      ? `${order.usdt_tx_id.slice(0, 8)}...${order.usdt_tx_id.slice(-8)}`
                      : order.usdt_tx_id}
                    <button type="button" onClick={() => copyToClipboard(order.usdt_tx_id!)} className="text-muted-foreground hover:text-foreground">
                      <Copy className="h-3 w-3" />
                    </button>
                  </span>
                </div>
              )}
            </div>

            {/* 继续支付按钮 — PENDING 且非 USDT */}
            {order.status === "PENDING" && !order.payment_processing && order.payment_method && !order.payment_method.startsWith("usdt_") && (
              <Link
                href={`/pay/${order.id}?method=${order.payment_method}`}
                className="inline-flex h-9 w-full items-center justify-center gap-1.5 rounded-lg bg-primary text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
              >
                {t("payment.continuePay")}
              </Link>
            )}

            {/* USDT 补单区域 — 仅 USDT + PENDING/EXPIRED */}
            {order.payment_method?.startsWith("usdt_") &&
             (order.status === "PENDING" || order.status === "EXPIRED" || order.status === "CANCELLED") && !order.payment_processing && (
              <div className="rounded-lg border border-amber-200 bg-amber-50 p-4 dark:border-amber-900/50 dark:bg-amber-950/30">
                {/* TXID 验证结果反馈（本次会话即时结果优先） */}
                {txidResult[order.id] ? (
                  <div className={cn(
                    "rounded-md p-3 text-sm",
                    txidResult[order.id].result === "AUTO_APPROVED" && "bg-emerald-50 text-emerald-800 dark:bg-emerald-950/30 dark:text-emerald-200",
                    txidResult[order.id].result === "AUTO_REJECTED" && "bg-red-50 text-red-800 dark:bg-red-950/30 dark:text-red-200",
                    txidResult[order.id].result === "PENDING_REVIEW" && "bg-yellow-50 text-yellow-800 dark:bg-yellow-950/30 dark:text-yellow-200"
                  )}>
                    {txidResult[order.id].result === "AUTO_APPROVED" && t("order.usdt.autoApproved")}
                    {txidResult[order.id].result === "AUTO_REJECTED" && t("order.usdt.autoRejected").replace("{reason}", formatTxidReason(txidResult[order.id].reason, t))}
                    {txidResult[order.id].result === "PENDING_REVIEW" && t("order.usdt.pendingReview")}
                  </div>
                ) : order.txid_review_status && txidExpandedOrder !== order.id ? (
                  /* 后端持久化的审核结果（跨会话可见） */
                  <div className="flex flex-col gap-2">
                    <div className={cn(
                      "rounded-md p-3 text-sm",
                      (order.txid_review_status === "REJECTED" || order.txid_review_status === "AUTO_REJECTED") && "bg-red-50 text-red-800 dark:bg-red-950/30 dark:text-red-200",
                      order.txid_review_status === "PENDING_REVIEW" && "bg-yellow-50 text-yellow-800 dark:bg-yellow-950/30 dark:text-yellow-200",
                      (order.txid_review_status === "APPROVED" || order.txid_review_status === "AUTO_APPROVED") && "bg-emerald-50 text-emerald-800 dark:bg-emerald-950/30 dark:text-emerald-200"
                    )}>
                      {(order.txid_review_status === "APPROVED" || order.txid_review_status === "AUTO_APPROVED") &&
                        t("order.usdt.reviewApproved")}
                      {order.txid_review_status === "PENDING_REVIEW" &&
                        t("order.usdt.reviewPending")}
                      {(order.txid_review_status === "REJECTED" || order.txid_review_status === "AUTO_REJECTED") &&
                        t("order.usdt.reviewRejected").replace("{reason}", order.txid_review_reason || "")}
                    </div>
                    {/* 被拒绝后允许重新提交 */}
                    {(order.txid_review_status === "REJECTED" || order.txid_review_status === "AUTO_REJECTED") && (
                      <button
                        type="button"
                        onClick={() => { setTxidExpandedOrder(order.id); setTxidInput(""); setTxidResult(prev => { const n = { ...prev }; delete n[order.id]; return n }) }}
                        className="self-start text-xs font-medium text-primary underline-offset-4 hover:underline"
                      >
                        {t("order.usdt.submitTxidLink")}
                      </button>
                    )}
                  </div>
                ) : txidExpandedOrder === order.id ? (
                  /* TXID 输入表单 */
                  <div className="flex flex-col gap-3">
                    <p className="text-sm font-medium text-amber-900 dark:text-amber-100">
                      {t("order.usdt.txidInputTitle")}
                    </p>
                    <p className="text-xs text-amber-700 dark:text-amber-300">
                      {t("order.usdt.txidInputDesc")}
                    </p>
                    <input
                      type="text"
                      placeholder="0x... / 64-char hex"
                      value={txidInput}
                      onChange={(e) => setTxidInput(e.target.value)}
                      className="h-10 w-full rounded-lg border border-input bg-background px-3 font-mono text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                    />
                    <p className="flex items-start gap-1 text-xs text-amber-600 dark:text-amber-400">
                      <Info className="mt-0.5 h-3 w-3 shrink-0" />
                      {t("order.usdt.txidInputHint")}
                    </p>
                    <div className="flex justify-end gap-2">
                      <button
                        onClick={() => { setTxidExpandedOrder(null); setTxidInput("") }}
                        className="rounded-lg border border-border px-4 py-2 text-xs font-medium text-foreground transition-colors hover:bg-accent"
                      >
                        {t("order.cancel")}
                      </button>
                      <button
                        onClick={() => handleTxidSubmit(order.id)}
                        disabled={txidSubmitting || !txidInput.trim()}
                        className="inline-flex items-center gap-1.5 rounded-lg bg-primary px-4 py-2 text-xs font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
                      >
                        {txidSubmitting && <Loader2 className="h-3 w-3 animate-spin" />}
                        {t("order.usdt.submitVerify")}
                      </button>
                    </div>
                  </div>
                ) : (
                  /* 引导提示 */
                  <div className="flex flex-col gap-2 text-xs text-amber-800 dark:text-amber-200">
                    <p className="flex items-start gap-1.5 text-sm font-medium">
                      <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
                      {t("order.usdt.notDetected")}
                    </p>
                    <p className="mt-1">{t("order.usdt.ifCompleted")}</p>
                    <ul className="ml-4 list-disc space-y-0.5">
                      <li>{t("order.usdt.waitConfirm")}</li>
                      <li>{t("order.usdt.checkAmount")}</li>
                      <li>{t("order.usdt.checkChain").replace("{chain}",
                        order.payment_method?.includes("trc20") ? "TRC-20" : "BEP-20")}</li>
                    </ul>
                    <p className="mt-2">
                      {t("order.usdt.waitOver5min")}
                      <button
                        type="button"
                        onClick={() => { setTxidExpandedOrder(order.id); setTxidInput(""); setTxidResult(prev => { const n = { ...prev }; delete n[order.id]; return n }) }}
                        className="ml-1 font-medium text-primary underline-offset-4 hover:underline"
                      >
                        {t("order.usdt.submitTxidLink")}
                      </button>
                    </p>
                  </div>
                )}
              </div>
            )}

            {/* Card Keys */}
            {deliver && deliver.groups.length > 0 && (
              <>
                <hr className="border-border" />
                <div>
                  <div className="mb-3 flex items-center justify-between">
                    <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
                      <CheckCircle2 className="h-4 w-4 text-emerald-500" />
                      {t("order.cardKeys")}
                    </h3>
                    <div className="flex gap-2">
                      <button
                        onClick={() => copyAllKeys(deliver)}
                        className="inline-flex h-7 items-center gap-1 rounded border border-border px-2 text-xs text-foreground transition-colors hover:bg-accent"
                      >
                        <Copy className="h-3 w-3" />
                        {t("order.copyAll")}
                      </button>
                      <button
                        onClick={() => downloadKeys(deliver)}
                        className="inline-flex h-7 items-center gap-1 rounded border border-border px-2 text-xs text-foreground transition-colors hover:bg-accent"
                      >
                        <Download className="h-3 w-3" />
                        {t("order.download")}
                      </button>
                    </div>
                  </div>
                  {deliver.groups.map((group, gIdx) => (
                    <div key={gIdx} className="mb-2">
                      <p className="mb-1 text-sm font-medium text-muted-foreground">
                        {group.product_title}{group.spec_name ? ` - ${group.spec_name}` : ""}
                      </p>
                      <div className="rounded-md bg-muted p-3" onCopy={(e) => { const t = window.getSelection()?.toString(); if (t) { e.clipboardData.setData("text/plain", stripInvisible(t)); e.preventDefault() } }}>
                        {group.card_keys.map((key, kIdx) => (
                          <div
                            key={kIdx}
                            className="flex items-center justify-between py-1"
                          >
                            <code className="min-w-0 break-all font-mono text-sm text-foreground">{key}</code>
                            <button
                              onClick={() => copyToClipboard(key)}
                              className="text-muted-foreground hover:text-foreground"
                            >
                              <Copy className="h-3.5 w-3.5" />
                            </button>
                          </div>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              </>
            )}
          </div>
        )
      })}

      {/* Recent Queries — 无查询结果时显示，有结果时自动隐藏 */}
      {recentQueries.length > 0 && orders.length === 0 && !isSearching && (
        <div className="rounded-lg border border-border bg-card p-4">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-semibold text-foreground">{t("order.recentOrders")}</h2>
          </div>
          <p className="mb-3 text-xs text-muted-foreground">{t("order.recentOrdersDesc")}</p>

          <div className="space-y-2">
            {recentQueries.map((recent) => (
              <div
                key={recent.value}
                className="flex items-center justify-between rounded-lg border border-border bg-background p-3 transition-colors hover:border-primary/30 hover:bg-accent"
              >
                <button
                  onClick={() => quickQuery(recent.value)}
                  className="flex flex-1 items-center gap-3 text-left"
                >
                  <Clock className="h-4 w-4 shrink-0 text-muted-foreground" />
                  <div className="flex-1 min-w-0">
                    <p className="font-mono text-sm font-medium text-foreground truncate">
                      {recent.value.length > 30 ? `${recent.value.slice(0, 12)}...${recent.value.slice(-8)}` : recent.value}
                    </p>
                    <p className="text-xs text-muted-foreground">
                      {formatTimeAgo(recent.timestamp)}
                    </p>
                  </div>
                </button>
                <button
                  onClick={(e) => {
                    e.stopPropagation()
                    setDeleteConfirm(recent.value)
                  }}
                  className="ml-2 rounded p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                >
                  <X className="h-4 w-4" />
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Help — 联系客服提示（始终在页面底部） */}
      {(config?.contact_telegram || config?.contact_email) && (
        <div className="flex flex-wrap items-center justify-center gap-x-1 text-sm text-muted-foreground">
          <HelpCircle className="h-3.5 w-3.5" />
          <span>{t("order.needHelp")}</span>
          {config.contact_telegram && (
            <a
              href={config.contact_telegram}
              target="_blank"
              rel="noopener noreferrer"
              className="flex items-center gap-0.5 underline-offset-4 transition-colors hover:text-foreground hover:underline"
            >
              {t("order.contactTelegram")}
              <ExternalLink className="h-3 w-3" />
            </a>
          )}
          {config.contact_telegram && config.contact_email && (
            <span>·</span>
          )}
          {config.contact_email && (
            <a
              href={`mailto:${config.contact_email}`}
              className="underline-offset-4 transition-colors hover:text-foreground hover:underline"
            >
              {t("order.contactEmail")}
            </a>
          )}
        </div>
      )}

      {/* Delete Confirmation Dialog */}
      <Modal open={deleteConfirm !== null} onClose={() => setDeleteConfirm(null)} className="max-w-md">
            <div className="p-6">
              <div className="mb-2">
                <h3 className="text-lg font-semibold text-foreground">{t("order.deleteConfirmTitle")}</h3>
              </div>
              <p className="mb-6 text-sm text-muted-foreground">
                {t("order.deleteConfirmMessage")}
              </p>
              <div className="flex justify-end gap-3">
                <button
                  onClick={() => setDeleteConfirm(null)}
                  className="rounded-lg border border-border px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-accent"
                >
                  {t("order.cancel")}
                </button>
                <button
                  onClick={() => deleteConfirm && confirmRemoveQuery(deleteConfirm)}
                  className="rounded-lg bg-red-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-red-700"
                >
                  {t("order.delete")}
                </button>
              </div>
            </div>
      </Modal>
    </div>
  )
}
