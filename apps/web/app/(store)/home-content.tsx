"use client"

import { useState, useMemo } from "react"
import {
  Search,
  Zap,
  SquareCheckBig,
  ClipboardList,
  ShieldCheck,
  ArrowDownRight,
  Sparkles,
} from "lucide-react"
import { useLocale, useSearch, useSiteConfig } from "@/lib/context"
import { ProductCard } from "@/components/store/product-card"
import { cn } from "@/lib/utils"
import type { ProductCard as ProductCardType, Category } from "@/types"

interface HomeContentProps {
  products: ProductCardType[]
  categories: Category[]
  siteSlogan: string
  siteDescription: string
}

export function HomeContent({ products, categories, siteSlogan, siteDescription }: HomeContentProps) {
  const { t } = useLocale()
  const { config } = useSiteConfig()
  const { searchQuery, sortBy, inStockOnly, priceMin, priceMax } = useSearch()

  const [selectedCategory, setSelectedCategory] = useState<string | null>(null)

  const trustBadges = [
    { icon: SquareCheckBig, label: t("home.trustAuto"), color: "text-emerald-600 dark:text-emerald-400", bg: "bg-emerald-100 dark:bg-emerald-900/40" },
    { icon: Zap, label: t("home.trustInstant"), color: "text-amber-600 dark:text-amber-400", bg: "bg-amber-100 dark:bg-amber-900/40" },
    { icon: ClipboardList, label: t("home.trustTrack"), color: "text-blue-600 dark:text-blue-400", bg: "bg-blue-100 dark:bg-blue-900/40" },
    { icon: ShieldCheck, label: t("home.trustSecure"), color: "text-teal-600 dark:text-teal-400", bg: "bg-teal-100 dark:bg-teal-900/40" },
  ]

  const filteredProducts = useMemo(() => {
    let result = [...products]

    if (selectedCategory) {
      result = result.filter((p) => p.category_id === selectedCategory)
    }
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase()
      result = result.filter((p) => p.title.toLowerCase().includes(q))
    }
    if (inStockOnly) {
      result = result.filter((p) => p.stock_available > 0)
    }

    const min = Number.parseFloat(priceMin)
    const max = Number.parseFloat(priceMax)
    if (!Number.isNaN(min)) result = result.filter((p) => p.base_price >= min)
    if (!Number.isNaN(max)) result = result.filter((p) => p.base_price <= max)

    switch (sortBy) {
      case "hot":
        result.sort((a, b) => ((b.sales_count ?? 0) + (b.initial_sales ?? 0)) - ((a.sales_count ?? 0) + (a.initial_sales ?? 0)))
        break
      case "price_low":
        result.sort((a, b) => a.base_price - b.base_price)
        break
      case "price_high":
        result.sort((a, b) => b.base_price - a.base_price)
        break
      case "new":
        result.sort(
          (a, b) =>
            new Date(b.created_at ?? 0).getTime() - new Date(a.created_at ?? 0).getTime()
        )
        break
    }

    return result
  }, [products, selectedCategory, searchQuery, sortBy, inStockOnly, priceMin, priceMax])

  return (
    <div className="mx-auto flex w-full max-w-7xl flex-col gap-8 2xl:max-w-[1600px]">
      {/* Hero */}
      <section className="relative isolate overflow-hidden rounded-[1.75rem] border border-border/60 bg-card px-6 py-10 sm:px-10 sm:py-14 lg:px-14 lg:py-16">
        <div className="scheme-blob pointer-events-none absolute -right-20 -top-32 h-80 w-80 rounded-full blur-3xl opacity-70" />
        <div aria-hidden="true" className="absolute inset-y-0 right-0 hidden w-2/5 overflow-hidden lg:block">
          <div className="absolute right-16 top-1/2 h-48 w-48 -translate-y-1/2 rotate-12 rounded-[2.5rem] border border-primary/20 bg-primary/5" />
          <div className="absolute right-28 top-1/2 flex h-48 w-48 -translate-y-1/2 -rotate-12 items-center justify-center rounded-[2.5rem] border border-primary/20 bg-card/70 shadow-2xl backdrop-blur-sm">
            <Sparkles className="h-16 w-16 text-primary/70" strokeWidth={1.25} />
          </div>
        </div>
        <div className="relative max-w-2xl">
          <div className="mb-6 inline-flex items-center gap-2 rounded-full border border-primary/20 bg-primary/5 px-3 py-1.5 text-xs font-semibold tracking-wide text-primary">
            <span className="h-1.5 w-1.5 rounded-full bg-primary" /> {config?.site_name || "SimpleCard"}
          </div>
          <h1 className="text-balance text-4xl font-bold leading-[1.08] tracking-tight sm:text-5xl lg:text-6xl">{siteSlogan}</h1>
          <p className="mt-5 max-w-xl text-base leading-7 text-muted-foreground sm:text-lg">{siteDescription}</p>
          <a href="#products" className="mt-8 inline-flex items-center gap-3 rounded-full bg-primary px-5 py-3 text-sm font-semibold text-primary-foreground transition hover:opacity-90">
            {t("home.allCategories")} <ArrowDownRight className="h-4 w-4" />
          </a>
          {config?.contact_telegram_group && (
            <a href={config.contact_telegram_group} target="_blank" rel="noopener noreferrer" className="ml-3 mt-4 inline-flex items-center gap-2 rounded-full border border-border px-5 py-3 text-sm font-semibold transition hover:bg-muted">
              <img src="/images/telegram.png" alt="" className="h-4 w-4" />{t("home.joinTelegram")}
            </a>
          )}
        </div>
      </section>

      {/* Controls */}
      <div className="flex flex-col gap-5">
        {/* Trust badges */}
        <div className="flex justify-center rounded-2xl border border-border/60 bg-card/60 px-4 py-5 sm:block">
          <div className="grid grid-cols-2 gap-x-10 gap-y-2 sm:flex sm:flex-wrap sm:items-center sm:justify-center sm:gap-x-7">
          {trustBadges.map((badge, i) => (
            <div key={badge.label} className="flex items-center gap-1.5">
              <div
                className={cn(
                  "flex h-6 w-6 shrink-0 items-center justify-center rounded-md",
                  badge.bg
                )}
              >
                <badge.icon className={cn("h-3.5 w-3.5", badge.color)} />
              </div>
              <span className="text-xs font-semibold text-foreground sm:text-sm">
                {badge.label}
              </span>
              {i < trustBadges.length - 1 && (
                <span className="ml-2 hidden text-border sm:inline">|</span>
              )}
            </div>
          ))}
          </div>
        </div>

        {/* Category tabs */}
        <div id="products" className="flex scroll-mt-24 flex-wrap items-center gap-2 pt-2">
          <button
            onClick={() => setSelectedCategory(null)}
            className={cn(
              "rounded-full px-4 py-2 text-sm font-semibold transition-all duration-200",
              selectedCategory === null
                ? "bg-primary text-primary-foreground shadow-sm"
                : "bg-muted text-muted-foreground hover:bg-accent hover:text-accent-foreground"
            )}
          >
            {t("home.allCategories")}
          </button>
          {categories.map((cat) => (
            <button
              key={cat.id}
              onClick={() =>
                setSelectedCategory(cat.id === selectedCategory ? null : cat.id)
              }
              className={cn(
                "rounded-full px-4 py-2 text-sm font-semibold transition-all duration-200",
                selectedCategory === cat.id
                  ? "bg-primary text-primary-foreground shadow-sm"
                  : "bg-muted text-muted-foreground hover:bg-accent hover:text-accent-foreground"
              )}
            >
              {cat.name}
            </button>
          ))}
        </div>
      </div>

      {/* Product Grid */}
      {filteredProducts.length > 0 ? (
        <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4">
          {filteredProducts.map((product) => (
            <ProductCard key={product.id} product={product} />
          ))}
        </div>
      ) : (
        <div className="flex flex-col items-center justify-center rounded-3xl border border-dashed border-border bg-card/50 py-24 text-muted-foreground">
          <Search className="mb-3 h-10 w-10 opacity-20" />
          <p className="text-sm">{t("home.noProducts")}</p>
        </div>
      )}
    </div>
  )
}
