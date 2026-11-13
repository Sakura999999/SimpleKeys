"use client"

import { useCallback, useEffect, useState } from "react"
import { adminExchangeApi, type ExchangeRate } from "@/services/api"
import { toast } from "sonner"

export default function ExchangeRatesPage() {
  const [rates,setRates] = useState<ExchangeRate[]>([])
  const [values,setValues] = useState<Record<string,string>>({})
  const [error,setError] = useState("")
  const [loading,setLoading] = useState(true)
  const [saving,setSaving] = useState(false)
  const load = useCallback(async () => {
    setLoading(true); setError("")
    try { const data = await adminExchangeApi.list(); setRates(data); setValues(Object.fromEntries(data.map(r => [r.code, r.code === "CNY" ? "1" : r.rate_to_cny == null ? "" : String(r.rate_to_cny)]))) }
    catch (e) { setError(e instanceof Error ? e.message : "加载汇率失败") }
    finally { setLoading(false) }
  },[])
  useEffect(() => { load() },[load])
  const save = async (event: React.FormEvent) => {
    event.preventDefault(); setSaving(true)
    try { await adminExchangeApi.save(values); toast.success("汇率已保存，新订单将使用新汇率；已有订单保持原金额"); await load() }
    catch (e) { toast.error(e instanceof Error ? e.message : "汇率保存失败") }
    finally { setSaving(false) }
  }
  return <div className="max-w-3xl space-y-6"><header><h1 className="text-2xl font-bold">汇率设置</h1></header>
    {error ? <p role="alert" className="text-destructive">{error} <button className="underline" onClick={load}>重试</button></p> : loading ? <p>加载中…</p> : <form onSubmit={save} className="rounded-xl border border-border bg-card p-6"><div className="space-y-5">{rates.map(rate => <label key={rate.code} className="grid items-center gap-2 sm:grid-cols-2"><span className="text-sm font-medium">1 {rate.code} · {rate.name}</span><div><div className="flex items-center gap-2"><input aria-label={`${rate.code} 人民币汇率`} type="number" min="0.00000001" max="1000000" step="0.00000001" disabled={rate.code === "CNY" || saving} value={values[rate.code] ?? ""} onChange={e => setValues(v => ({ ...v,[rate.code]:e.target.value }))} placeholder="未配置" className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm disabled:opacity-60" /><span className="text-sm text-muted-foreground">CNY</span></div>{rate.updated_at && <p className="mt-1 text-xs text-muted-foreground">更新：{rate.updated_at.replace("T"," ").slice(0,16)}</p>}</div></label>)}</div><button disabled={saving} className="mt-6 rounded-lg bg-primary px-5 py-2.5 text-sm font-semibold text-primary-foreground disabled:opacity-50">{saving ? "保存中…" : "保存汇率"}</button></form>}
    <div className="space-y-3 text-sm leading-6 text-muted-foreground">
      <p className="rounded-xl border border-border bg-card p-4">例如：只有在你决定按 1 USD = 7.20 CNY 收款时，才将 USD 填为 7.20。商品换算后的单价保留两位小数，再乘数量；下单时锁定价格和汇率。USDT 作为商品标价时也需要单独配置人民币汇率。</p>
      <p>填写 1 单位币种对应的人民币金额。汇率由你手动维护，不自动获取市场报价。人民币基准为 1，其他币种留空时不可结算。</p>
    </div>
  </div>
}
