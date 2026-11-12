"use client"

import { useCallback, useEffect, useState } from "react"
import { Plus, Pencil, Search, ChevronLeft, ChevronRight } from "lucide-react"
import { toast } from "sonner"
import { Modal } from "@/components/ui/modal"
import { adminBusinessApi, type BusinessRecord, type BusinessRecordInput } from "@/services/api"

type Form = { customer_name: string; purchase_content: string; bd: string; channel: string; quantity: string; unit_price_usd: string; unit_price_cny: string; unit_cost_usd: string; received: boolean; ordered_at: string; received_at: string; notes: string }
const emptyForm: Form = { customer_name: "", purchase_content: "", bd: "", channel: "", quantity: "1", unit_price_usd: "", unit_price_cny: "", unit_cost_usd: "", received: false, ordered_at: "", received_at: "", notes: "" }
const fieldClass = "h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
const money = (value: number | null | undefined, symbol: string) => value == null ? "—" : `${symbol}${Number(value).toFixed(2)}`
const amount = (unit: number | null | undefined, quantity: number) => unit == null ? null : Number(unit) * quantity
const date = (value: string | null) => value ? value.replace("T", " ").slice(0, 16) : "—"
const inputDate = (value: string | null) => value ? value.slice(0, 16).replace(" ", "T") : ""
const apiDate = (value: string) => value ? `${value.replace("T", " ")}:00` : null

export default function BusinessRecordsPage() {
  const [records, setRecords] = useState<BusinessRecord[]>([])
  const [query, setQuery] = useState("")
  const [bd, setBd] = useState("")
  const [received, setReceived] = useState("")
  const [month, setMonth] = useState("")
  const [page, setPage] = useState(0)
  const [total, setTotal] = useState(0)
  const [pages, setPages] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState("")
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<string | null>(null)
  const [form, setForm] = useState<Form>(emptyForm)
  const [saving, setSaving] = useState(false)
  const [revision, setRevision] = useState(0)

  const load = useCallback(async (signal: { active: boolean }) => {
    setLoading(true); setError("")
    try {
      const result = await adminBusinessApi.list({ page, size: 20, query, bd, received: received === "" ? undefined : received === "true", month })
      if (signal.active) { setRecords(result.content); setTotal(result.total_elements); setPages(result.total_pages) }
    } catch (e) { if (signal.active) setError(e instanceof Error ? e.message : "加载失败，请检查后端服务") }
    finally { if (signal.active) setLoading(false) }
  }, [page, query, bd, received, month])
  useEffect(() => { const signal = { active: true }; const timer = setTimeout(() => load(signal), 250); return () => { signal.active = false; clearTimeout(timer) } }, [load, revision])
  const set = (key: keyof Form, value: string | boolean) => setForm(prev => ({ ...prev, [key]: value }))
  const create = () => { setEditing(null); setForm(emptyForm); setOpen(true) }
  const edit = (record: BusinessRecord) => {
    setEditing(record.id)
    setForm({ customer_name: record.customer_name, purchase_content: record.purchase_content, bd: record.bd ?? "", channel: record.channel ?? "", quantity: String(record.quantity), unit_price_usd: record.unit_price_usd == null ? "" : String(record.unit_price_usd), unit_price_cny: record.unit_price_cny == null ? "" : String(record.unit_price_cny), unit_cost_usd: record.unit_cost_usd == null ? "" : String(record.unit_cost_usd), received: record.received, ordered_at: inputDate(record.ordered_at), received_at: inputDate(record.received_at), notes: record.notes ?? "" })
    setOpen(true)
  }
  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const numberOrNull = (value: string) => value.trim() === "" ? null : Number(value)
    const data: BusinessRecordInput = { ...form, customer_name: form.customer_name.trim(), purchase_content: form.purchase_content.trim(), quantity: Number(form.quantity), unit_price_usd: numberOrNull(form.unit_price_usd), unit_price_cny: numberOrNull(form.unit_price_cny), unit_cost_usd: numberOrNull(form.unit_cost_usd), ordered_at: apiDate(form.ordered_at), received_at: apiDate(form.received_at) }
    if (!data.customer_name || !data.purchase_content || !Number.isInteger(data.quantity) || data.quantity < 1) { toast.error("请填写客户、购买内容及有效数量"); return }
    setSaving(true)
    try { if (editing) await adminBusinessApi.update(editing, data); else await adminBusinessApi.create(data); toast.success(editing ? "业务记录已更新" : "业务记录已添加"); setOpen(false); setPage(0); setRevision(v => v + 1) }
    catch (e) { toast.error(e instanceof Error ? e.message : "保存失败，请重试") }
    finally { setSaving(false) }
  }
  const fields: { key: keyof Form; label: string; type?: string; required?: boolean; maxLength?: number }[] = [
    { key: "customer_name", label: "客户名称", required: true, maxLength: 120 }, { key: "purchase_content", label: "购买内容", required: true, maxLength: 255 },
    { key: "bd", label: "BD / 对接人", maxLength: 120 }, { key: "channel", label: "渠道", maxLength: 120 },
    { key: "quantity", label: "销售数量", type: "number", required: true }, { key: "unit_price_usd", label: "销售单价（USD）", type: "number" },
    { key: "unit_price_cny", label: "销售单价（CNY）", type: "number" }, { key: "unit_cost_usd", label: "成本单价（USD）", type: "number" },
    { key: "ordered_at", label: "下单时间", type: "datetime-local" }, { key: "received_at", label: "回款时间", type: "datetime-local" },
  ]
  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4"><div><h1 className="text-2xl font-bold">业务管理</h1><p className="mt-1 text-sm text-muted-foreground">记录客户业务、销售金额和回款情况</p></div><button onClick={create} className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-semibold text-primary-foreground"><Plus className="h-4 w-4" />新增记录</button></div>
      <div className="flex flex-wrap gap-3 rounded-xl border border-border bg-card p-4">
        <div className="relative min-w-[200px] flex-1"><Search className="absolute left-3 top-3 h-4 w-4 text-muted-foreground" /><input aria-label="搜索业务" placeholder="搜索客户、购买内容或渠道" className={`${fieldClass} pl-9`} value={query} onChange={e => { setQuery(e.target.value); setPage(0) }} /></div>
        <input aria-label="筛选BD" placeholder="BD / 对接人" className={`${fieldClass} max-w-[170px]`} value={bd} onChange={e => { setBd(e.target.value); setPage(0) }} />
        <input aria-label="按下单月份筛选" type="month" className={`${fieldClass} w-auto`} value={month} onChange={e => { setMonth(e.target.value); setPage(0) }} />
        <select aria-label="筛选到账状态" className={`${fieldClass} w-auto`} value={received} onChange={e => { setReceived(e.target.value); setPage(0) }}><option value="">全部到账状态</option><option value="true">已到账</option><option value="false">未到账</option></select>
      </div>
      {error ? <div role="alert" className="rounded-xl border border-destructive/30 bg-destructive/5 p-5 text-sm text-destructive">加载失败：{error} <button className="ml-2 underline" onClick={() => setRevision(v => v + 1)}>重试</button></div> : <div className="overflow-hidden rounded-xl border border-border bg-card"><div className="overflow-x-auto"><table className="w-full whitespace-nowrap text-left text-sm"><thead className="border-b border-border bg-muted/40 text-xs text-muted-foreground"><tr>{["到账", "BD", "客户", "购买内容", "渠道", "数量", "单价 USD", "收入 USD", "单价 CNY", "收入 CNY", "成本单价 USD", "成本合计 USD", "毛利 USD", "下单时间", "回款时间", "备注", "操作"].map(h => <th className="px-4 py-3 font-medium" key={h}>{h}</th>)}</tr></thead><tbody className="divide-y divide-border">{records.map(r => { const revenue = amount(r.unit_price_usd,r.quantity); const cost = amount(r.unit_cost_usd,r.quantity); return <tr key={r.id} className="hover:bg-muted/30"><td className="px-4 py-3"><span className={r.received ? "text-emerald-600" : "text-muted-foreground"}>{r.received ? "已到账" : "未到账"}</span></td><td className="px-4 py-3">{r.bd || "—"}</td><td className="px-4 py-3">{r.customer_name}</td><td className="max-w-64 truncate px-4 py-3" title={r.purchase_content}>{r.purchase_content}</td><td className="px-4 py-3">{r.channel || "—"}</td><td className="px-4 py-3">{r.quantity}</td><td className="px-4 py-3">{money(r.unit_price_usd,"$")}</td><td className="px-4 py-3">{money(revenue,"$")}</td><td className="px-4 py-3">{money(r.unit_price_cny,"¥")}</td><td className="px-4 py-3">{money(amount(r.unit_price_cny,r.quantity),"¥")}</td><td className="px-4 py-3">{money(r.unit_cost_usd,"$")}</td><td className="px-4 py-3">{money(cost,"$")}</td><td className="px-4 py-3">{money(revenue == null || cost == null ? null : revenue-cost,"$")}</td><td className="px-4 py-3">{date(r.ordered_at)}</td><td className="px-4 py-3">{date(r.received_at)}</td><td className="max-w-48 truncate px-4 py-3" title={r.notes ?? ""}>{r.notes || "—"}</td><td className="px-4 py-3"><button onClick={() => edit(r)} className="inline-flex items-center gap-1 text-primary hover:underline" aria-label={`编辑 ${r.customer_name} 的记录`}><Pencil className="h-3.5 w-3.5" />编辑</button></td></tr> })}</tbody></table></div>{loading ? <p className="p-8 text-center text-sm text-muted-foreground">加载中…</p> : records.length === 0 ? <p className="p-12 text-center text-sm text-muted-foreground">暂无符合条件的业务记录，点击“新增记录”开始录入。</p> : null}<div className="flex items-center justify-between border-t border-border px-4 py-3 text-sm text-muted-foreground"><span>共 {total} 条 · 第 {page + 1} / {Math.max(pages, 1)} 页</span><div className="flex gap-2"><button aria-label="上一页" disabled={page === 0 || loading} onClick={() => setPage(p => p - 1)} className="rounded-md border p-1 disabled:opacity-40"><ChevronLeft className="h-4 w-4" /></button><button aria-label="下一页" disabled={page + 1 >= pages || loading} onClick={() => setPage(p => p + 1)} className="rounded-md border p-1 disabled:opacity-40"><ChevronRight className="h-4 w-4" /></button></div></div></div>}
      <Modal open={open} onClose={() => { if (!saving) setOpen(false) }} className="max-w-3xl"><form onSubmit={save}><div className="border-b border-border px-6 py-4"><h2 className="text-lg font-semibold">{editing ? "编辑业务记录" : "新增业务记录"}</h2><p className="mt-1 text-xs text-muted-foreground">金额留空表示尚未记录；USD 和 CNY 单价分别填写，不自动换算。</p></div><div className="max-h-[65vh] overflow-y-auto px-6 py-5"><div className="grid gap-4 sm:grid-cols-2">{fields.map(f => <label key={f.key} className="space-y-1.5 text-sm font-medium"><span>{f.label}{f.required && " *"}</span><input className={fieldClass} type={f.type || "text"} required={f.required} maxLength={f.maxLength} min={f.type === "number" ? (f.key === "quantity" ? "1" : "0") : undefined} max={f.key === "quantity" ? "1000000" : undefined} step={f.type === "number" ? (f.key === "quantity" ? "1" : "0.01") : undefined} value={String(form[f.key])} onChange={e => set(f.key,e.target.value)} /></label>)}<label className="flex items-center gap-2 text-sm font-medium"><input type="checkbox" checked={form.received} onChange={e => set("received",e.target.checked)} />已到账</label><label className="space-y-1.5 text-sm font-medium sm:col-span-2"><span>备注</span><textarea className="min-h-20 w-full rounded-lg border border-input bg-background p-3 text-sm" maxLength={2000} value={form.notes} onChange={e => set("notes",e.target.value)} /></label></div></div><div className="flex justify-end gap-3 border-t border-border px-6 py-4"><button type="button" disabled={saving} onClick={() => setOpen(false)} className="rounded-lg border border-input px-4 py-2 text-sm">取消</button><button disabled={saving} className="rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-primary-foreground disabled:opacity-50">{saving ? "保存中…" : "保存"}</button></div></form></Modal>
    </div>
  )
}
