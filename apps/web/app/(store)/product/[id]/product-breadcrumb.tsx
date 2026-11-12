"use client"

import Link from "next/link"
import { ArrowLeft } from "lucide-react"
import { useLocale } from "@/lib/context"

export function ProductBreadcrumb({ title, titleEn }: { title: string; titleEn?: string | null }) {
  const { t, locale } = useLocale()
  const displayTitle = locale === "en" ? titleEn?.trim() || title : title

  return (
    <div className="flex items-center gap-2 text-xs text-muted-foreground">
      <Link
        href="/"
        className="inline-flex items-center gap-1 transition-colors hover:text-foreground"
      >
        <ArrowLeft className="h-3.5 w-3.5" />
        {t("common.back")}
      </Link>
      <span>/</span>
      <span className="truncate max-w-[200px] sm:max-w-none">{displayTitle}</span>
    </div>
  )
}
