import { getProducts, getCategories, getSiteConfig } from "@/services/api-server"
import { HomeContent } from "./home-content"
import type { Metadata } from "next"

export async function generateMetadata(): Promise<Metadata> {
  try {
    const config = await getSiteConfig()
    return {
      title: config.site_name || "简易发卡",
      description: config.site_description || config.site_slogan || "",
      alternates: { canonical: "/" },
      openGraph: {
        title: config.site_name || "简易发卡",
        description: config.site_description || config.site_slogan || "",
        url: "/",
        ...(config.logo_url ? { images: [{ url: config.logo_url }] } : {}),
      },
    }
  } catch {
    return { title: "简易发卡" }
  }
}

export default async function HomePage() {
  const [productsData, categories, config] = await Promise.all([
    getProducts({ page: 1, page_size: 100 }).catch(() => null),
    getCategories().catch(() => []),
    getSiteConfig().catch(() => null),
  ])

  if (!productsData || !config) {
    return (
      <section role="alert" className="rounded-2xl border border-border bg-card px-6 py-14 text-center">
        <h1 className="text-xl font-semibold">暂时无法加载网站内容</h1>
        <p className="mt-3 text-sm text-muted-foreground">当前无法连接服务，请稍后重试。</p>
        <a href="/" className="mt-6 inline-flex rounded-full bg-primary px-5 py-2.5 text-sm font-semibold text-primary-foreground">重新加载</a>
      </section>
    )
  }

  const jsonLd = {
    "@context": "https://schema.org",
    "@type": "WebSite",
    name: config?.site_name || "简易发卡",
    description: config?.site_description || config?.site_slogan || "",
  }

  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <HomeContent
        products={productsData.list}
        categories={categories}
        siteSlogan={config?.site_slogan || ""}
        siteDescription={config?.site_description || ""}
      />
    </>
  )
}
