"use client"

import type { SettlementQuote } from "@/services/api"
import { useLocale } from "@/lib/context"

export function SettlementSummary({ quote, error, usdt = false, retry }: { quote?: SettlementQuote; error?: string; usdt?: boolean; retry: () => void }) {
  const { locale } = useLocale()
  const zh = locale === "zh"
  return <div className="rounded-xl border border-border bg-muted/30 p-4 text-sm" aria-live="polite">
    {error ? <div role="alert" className="text-destructive">{error} <button type="button" onClick={retry} className="ml-2 underline">{zh ? "重试" : "Retry"}</button></div> : !quote ? <p className="text-muted-foreground">{zh ? "正在计算结算金额…" : "Calculating checkout amount…"}</p> : <div className="space-y-2">
      <div className="flex justify-between gap-2"><span>{zh ? "人民币结算合计" : "Order total (CNY)"}</span><strong>¥{quote.total_amount.toFixed(2)}</strong></div>
      {quote.items.filter(item => item.source_currency !== "CNY").map(item => <p key={item.key} className="break-words text-xs text-muted-foreground">{item.title} · {item.source_currency} {item.source_unit_price.toFixed(2)} × {item.quantity} · {zh ? "汇率" : "Rate"} 1 {item.source_currency} = {item.exchange_rate} CNY → ¥{item.subtotal.toFixed(2)}</p>)}
      <p className="text-xs text-muted-foreground">{usdt
        ? (zh ? `将按 ${quote.payment_currency} ${quote.payment_amount.toFixed(2)} 向 USDT 网关创建支付。实际需转入的 USDT 金额及网络以支付页为准，请勿自行换算转账。` : `The USDT gateway will receive ${quote.payment_currency} ${quote.payment_amount.toFixed(2)}. Send only the exact USDT amount on the network shown on the payment page.`)
        : (zh ? `实际支付：人民币 ¥${quote.payment_amount.toFixed(2)}。汇率由本站设置，下单后锁定。` : `Pay CNY ¥${quote.payment_amount.toFixed(2)}. Store exchange rates are locked when the order is placed.`)}</p>
    </div>}
  </div>
}
