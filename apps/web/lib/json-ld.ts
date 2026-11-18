/**
 * JSON-LD 安全序列化。
 *
 * <p><b>为什么不能直接 `JSON.stringify` 塞进 &lt;script&gt;：</b>结构化数据必须写在
 * `<script type="application/ld+json">` 里，而这段内容是**原始 HTML**。
 * `JSON.stringify` 只保证输出是合法 JSON，不保证它不会闭合标签 —— 值里出现
 * `</script><script>alert(1)</script>` 时浏览器会当场结束脚本块并执行后面的脚本。
 * 商品标题、站点描述、分类名都可以被写进这些值，于是拼出一条存储型 XSS。
 *
 * <p><b>为什么转义成 \\u003c 而不是 HTML 实体：</b>JSON-LD 的消费方（Google、
 * 各类爬虫）把脚本内容当 JSON 解析，`\\u003c` 是合法的 JSON 字符串转义，解析后
 * 还原成 `<`，语义不变；而 `&lt;` 会被当成字面量，反而污染数据。
 *
 * <p>另外转义 U+2028/U+2029：这两个字符在 JSON 里合法，但在旧版 JS 的行终结符规则里
 * 会截断语句，属于同一类"看着没问题、拼接就出事"的坑。
 */
export function serializeJsonLd(data: unknown): string {
  return JSON.stringify(data)
    .replace(/</g, "\\u003c")
    .replace(/>/g, "\\u003e")
    .replace(/&/g, "\\u0026")
    .replace(/\u2028/g, "\\u2028")
    .replace(/\u2029/g, "\\u2029")
}

/**
 * 直接给 `<script type="application/ld+json" dangerouslySetInnerHTML={...} />` 用的 props。
 *
 * 收敛成一个函数，是为了让"新增一个 JSON-LD"的成本低于"手写 dangerouslySetInnerHTML"，
 * 否则下一个人复制粘贴时又会绕过转义。
 */
export function jsonLdProps(data: unknown): { dangerouslySetInnerHTML: { __html: string } } {
  return { dangerouslySetInnerHTML: { __html: serializeJsonLd(data) } }
}
