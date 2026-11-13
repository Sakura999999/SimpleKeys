"use client"

import { cn } from "@/lib/utils"
import type { OrderStatus } from "@/types"
import { useLocale } from "@/lib/context"
import type { TranslationKey } from "@/lib/i18n"

const statusStyles: Record<OrderStatus, string> = {
  PENDING: "bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400",
  PAID: "bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400",
  DELIVERED: "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/30 dark:text-emerald-400",
  PAID_REVIEW: "bg-orange-100 text-orange-800",
  REFUNDED: "bg-zinc-100 text-zinc-600",
  EXPIRED: "bg-zinc-100 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400",
}

const statusKeys: Record<OrderStatus, TranslationKey> = {
  PENDING: "status.PENDING",
  PAID: "status.PAID",
  DELIVERED: "status.DELIVERED",
  EXPIRED: "status.EXPIRED",
  PAID_REVIEW: "status.PAID_REVIEW",
  REFUNDED: "status.REFUNDED",
}

export function OrderStatusBadge({ status, paymentProcessing = false }: { status: OrderStatus; paymentProcessing?: boolean }) {
  const { t } = useLocale()
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium",
        statusStyles[status]
      )}
    >
      {t(paymentProcessing && status === "PENDING" ? "status.PAYMENT_PROCESSING" : statusKeys[status])}
    </span>
  )
}
