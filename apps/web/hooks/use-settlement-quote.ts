"use client"

import { useEffect, useState } from "react"
import { orderApi, type SettlementQuote, type SettlementQuoteRequest } from "@/services/api"

export function useSettlementQuote(request: SettlementQuoteRequest, enabled: boolean, revision = "") {
  const key = JSON.stringify(request) + revision
  const [state, setState] = useState<{ key: string; quote?: SettlementQuote; error?: string }>({ key: "" })
  const [refresh, setRefresh] = useState(0)
  useEffect(() => {
    if (!enabled) return
    let active = true
    const timer = setTimeout(() => {
      setState({ key })
      orderApi.quote(request).then(quote => { if (active) setState({ key, quote }) })
        .catch(error => { if (active) setState({ key, error: error instanceof Error ? error.message : "无法获取结算金额" }) })
    }, 250)
    return () => { active = false; clearTimeout(timer) }
    // The serialized key captures every request field and cart revision.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, enabled, refresh])
  return {
    quote: enabled && state.key === key ? state.quote : undefined,
    error: enabled && state.key === key ? state.error : undefined,
    refresh: () => { setState({ key: "" }); setRefresh(value => value + 1) },
  }
}
