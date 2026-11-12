/** Select translated product content, falling back independently for blank fields. */
export function localizeProduct<T extends {
  title: string; title_en?: string | null
  description?: string; description_en?: string | null
  detail_md?: string; detail_md_en?: string | null
}>(product: T, locale: string): T {
  if (locale !== "en") return product
  return {
    ...product,
    title: product.title_en?.trim() || product.title,
    description: product.description_en?.trim() || product.description,
    detail_md: product.detail_md_en?.trim() || product.detail_md,
  }
}
