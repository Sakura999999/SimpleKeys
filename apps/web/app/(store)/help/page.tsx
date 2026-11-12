"use client"

import Link from "next/link"
import { Mail, MessageCircle, Users, ArrowUpRight, CircleHelp } from "lucide-react"
import { useLocale, useSiteConfig } from "@/lib/context"

function telegramLink(value: string): string | null {
  const text = value.trim()
  if (/^@?[a-zA-Z0-9_]{5,32}$/.test(text)) return `https://t.me/${text.replace(/^@/, "")}`
  try {
    const url = new URL(text)
    return url.protocol === "https:" && ["t.me", "telegram.me"].includes(url.hostname) ? url.href : null
  } catch { return null }
}

export default function HelpPage() {
  const { locale } = useLocale()
  const { config, isLoading } = useSiteConfig()
  const zh = locale === "zh"
  const contacts = [
    { title: zh ? "客服邮箱" : "Support email", value: config?.contact_email, icon: Mail, href: config?.contact_email ? `mailto:${config.contact_email}` : null },
    { title: zh ? "Telegram 客服" : "Telegram support", value: config?.contact_telegram, icon: MessageCircle, href: config?.contact_telegram ? telegramLink(config.contact_telegram) : null },
    { title: zh ? "Telegram 群组" : "Telegram community", value: config?.contact_telegram_group, icon: Users, href: config?.contact_telegram_group ? telegramLink(config.contact_telegram_group) : null },
  ].filter(contact => contact.value?.trim())
  const questions = zh ? [
    { title: "如何购买商品？", text: "进入商品详情，确认规格、数量和发货方式后下单。请核对填写的邮箱，并按照订单页面提示完成付款。" },
    { title: "付款后如何获取商品？", text: "在订单页面查看支付状态和发货结果。自动发货与人工发货以商品说明为准，请保存订单号。" },
    { title: "付款后订单状态没有更新怎么办？", text: "先在订单页面刷新状态；如仍有异常，请联系客服并提供订单号、支付时间及付款凭证，便于核查。" },
    { title: "联系客服时需要提供什么？", text: "请说明遇到的问题并提供相关订单号。不要发送账号密码、验证码或支付密钥。" },
  ] : [
    { title: "How do I place an order?", text: "Open a product, review its options, quantity and delivery method, then place an order. Check your email address and follow the payment instructions." },
    { title: "How do I receive my purchase?", text: "Check the order page for payment and delivery updates. Delivery depends on the product description. Keep your order number." },
    { title: "My payment status has not updated. What should I do?", text: "Refresh the order status first. If the issue remains, contact support with your order number, payment time and proof of payment." },
    { title: "What should I include when contacting support?", text: "Describe your issue and include the relevant order number. Never share passwords, verification codes or payment secrets." },
  ]
  return (
    <div className="mx-auto max-w-4xl space-y-8 py-4 sm:py-8">
      <header className="space-y-4"><div className="inline-flex items-center gap-2 rounded-full bg-primary/10 px-3 py-1.5 text-sm text-primary"><CircleHelp className="h-4 w-4" />{zh ? "帮助中心" : "Help center"}</div><h1 className="text-3xl font-bold tracking-tight sm:text-4xl">{zh ? "有什么可以帮你？" : "How can we help?"}</h1><p className="text-muted-foreground">{zh ? "了解购买与订单流程，或联系本站客服。" : "Find answers about purchases and orders, or contact support."}</p><Link href="/order/query" className="inline-flex items-center gap-2 rounded-full bg-primary px-5 py-2.5 text-sm font-semibold text-primary-foreground">{zh ? "查询我的订单" : "Look up an order"}<ArrowUpRight className="h-4 w-4" /></Link></header>
      <section aria-labelledby="contact-heading" className="space-y-4"><h2 id="contact-heading" className="text-xl font-semibold">{zh ? "联系我们" : "Contact us"}</h2>
        {isLoading ? <p className="text-sm text-muted-foreground">{zh ? "正在加载联系方式…" : "Loading contact information…"}</p> : contacts.length ? <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">{contacts.map(({ title, value, icon: Icon, href }) => <div key={title} className="rounded-2xl border border-border bg-card p-5"><Icon className="mb-4 h-5 w-5 text-primary" /><h3 className="font-semibold">{title}</h3>{href ? <a href={href} target={href.startsWith("https:") ? "_blank" : undefined} rel="noopener noreferrer" className="mt-2 block break-all text-sm text-primary hover:underline">{value}</a> : <p className="mt-2 break-all text-sm text-muted-foreground">{value}</p>}</div>)}</div> : <p className="rounded-2xl border border-border bg-card p-5 text-sm text-muted-foreground">{zh ? "本站暂未公布联系方式。你可以先通过订单查询查看订单状态。" : "No contact information is currently available. Please check your order status first."}</p>}
      </section>
      <section aria-labelledby="faq-heading" className="space-y-4"><h2 id="faq-heading" className="text-xl font-semibold">{zh ? "常见问题" : "Frequently asked questions"}</h2><div className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">{questions.map(q => <details key={q.title} className="group px-5 py-4"><summary className="cursor-pointer font-medium focus-visible:outline-primary">{q.title}</summary><p className="mt-3 text-sm leading-7 text-muted-foreground">{q.text}</p></details>)}</div></section>
    </div>
  )
}
