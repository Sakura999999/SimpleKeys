# 接口安全与分布式限流

本轮先修权限、参数、回调和审计，再接入可复用的精确滑动窗口日志。保留原有库存事务、付款事件可靠接收、商品缓存保护。没有数据库结构迁移，也没有修改线上数据。

## 访问控制与兼容性

TXID 和重新支付必须先验证归属，再检查业务限额、执行外部调用。注册账号订单需要本人或管理员；访客敏感操作需要原浏览器的 X-Session-Token，新前端在首次请求时生成随机会话并用于下单。旧的无会话访客订单仍可按原 UUID 能力凭证查询、查看状态和领取/导出，但不能仅凭泄漏 UUID 触发 TXID 或重新支付，应由管理员协助。跨浏览器没有原会话时同样适用。

状态与主动刷新接口在扣订单额度前校验订单访问权。访客只读仍保留 UUID 能力凭证模型，知晓 UUID 的人共享该读取能力及其限额；不能把它描述成强账号身份验证。设备指纹是调用者可更换的辅助信号，不作为归属凭证。

登录仍只使用数据库 failed_login_attempts 和 15 分钟锁定，不再建立第二套账号失败计数。登录读取用户时加数据库行锁，避免并发失败丢计数。Java 兜底与种子统一为 10 次，实际以 site_configs 中 login_attempt_limit 为准。

注册的 Turnstile 前后端原本均已接入，本轮修复启用后的注册失败放行：启用但缺 secret 返回 503，注册验证提供方失败时不创建账号；成功验证正常放行。需要配置 TURNSTILE_SECRET_KEY、TURNSTILE_SITE_KEY，并在后台启用 turnstile_enabled。未提供真实密钥时不会擅自开启。登录和下单继续保留原有提供方异常降级策略。

## 参数与敏感数据

- 商品公开列表 page 为 1～10000，page_size 为 1～100，keyword 最多 64 字符。
- 查单最多 50 个订单号、10 个邮箱，合计最多 50 项；领取最多 50 个订单号。拒绝错误类型、空字符串和超长字段，不先查询数据库再截断。
- 邮箱查询在 SQL 内按账号/会话授权，并限制最新 100 条结果；邮箱本身不是凭证。显式订单号结果另受 50 项限制。
- 卡密导出先鉴权、限频，生成后在原 operation_logs 中提交 CARD_KEYS_EXPORT_PREPARED，再允许输出。记录操作者、订单、IP 和卡密数量，不记录卡密、会话、完整请求或签名。PREPARED 表示已准备，不谎称网络传输完成。
- 旧审计表要求 user_id 非空，因此 UUID 全零标识访客，username 为 GUEST，不代表真实账号。审计写入失败会阻止导出。
- 导出响应使用 no-store/private、no-cache 和 no-referrer。畸形 JSON、类型及 UUID 参数返回 400，而不是兜底 500。

所有安全过滤器统一使用解码后的应用路径，不依赖固定 /api 前缀，避免编码拼写或 context-path 变化绕过策略。

## 回调独立处理

/payments/webhook/epay 和 /usdt 不进入消费者的 IP、设备或通用 Redis 限流桶。每渠道使用固定数量的本地状态：默认 100 次/秒、16 个并发；超载返回 503 和 retry，绝不冒充已收款成功，也不使用消费者 429。

原验签、金额校验、幂等、付款事件落库和重试继续生效。成功应答仍由原渠道协议决定，尚未可靠保存的事实不能因为限流改造而返回成功。取消控制器整包回调参数日志，避免记录签名和大体积请求。

用户 TXID 遇到链上提供方 429 返回 429，网络/HTTP 系统异常返回 503，不创建 PENDING_REVIEW。真正发起过的外部尝试仍计入本次限额，避免失败重试风暴；本地限流自身拒绝不会创建审核记录。

## Redis 与算法

三个生产故障域分开：交易协调 redis:6379（256 MB、noeviction）、商品 product-cache:6379（128 MB）、通用 api-limiter:6379（64 MB）。后端配置为不同地址，明确拒绝限流客户端与另外两者使用相同地址字符串。别名指向同一物理服务无法靠字符串检查识别，部署仍需确认真实拓扑。

本机开发分别为 127.0.0.1:6379、6380、6390。新容器 simplecard-api-limiter-dev 仅绑定本机，随机密码保存在忽略提交的 .local-tools/api-limiter-dev.conf 和 apps/api/.local-config.properties，不打印、不提交。

Lua 内只用 Redis TIME，member 使用每请求唯一 UUID。先检查全局，再检查 peer；全局拒绝不能创建新 peer 键。清理阈值与 TTL 随 windowMs 联动，每次成功写入重设 TTL=2×window。不同策略/窗口分开命名，IP、用户、订单均 SHA-256 后作为键后缀，不把会话或设备原文写入键。

全部通用策略共享 peer 注册表，默认最多 5000 个 peer 键（配置硬上限 10000）；拒绝超过预算的新键。最多 32 个 scope，各窗口 1 秒～24 小时，单 scope 全局额度最多 20000，配置全局额度之和最多 50000。注册表保留时间按最长剩余 TTL 设置，不能被短窗口调用缩成 2 秒。达到 Redis maxmemory 的 75% 时不继续写限流日志，转入该操作的故障策略。

额度、窗口、TTL、peer 数、每个 ZSet 成员及对象开销共同决定容量，不能只用“每秒额度×窗口”估算。64 MB 为初始配置，需基于真实请求分布监控和压测。

TXID 和重新支付把短时调用额度与授权后的订单额度放在同一 Lua 中：先全部判断，再全部记账。全局繁忙不会误扣订单额度，订单额度耗尽也不会继续占用全局调用额度。txid_submit_limit_per_order 数据库配置继续有效，范围 1～20，默认 3。

商品详情只复用参数化 Lua 内核，仍连接原商品 Redis，原键布局、1 秒窗口、2 秒 TTL、300/30/100 额度及并发上限保持不变，不额外叠加通用策略。

## 默认策略

下表的“全局”是该 scope 所有 API 实例共享额度；peer 是该维度在同一窗口的额度。不是每个接口都套一套独立全站额度。原有全站及设备额度继续叠加，最终取各层共同结果。

| scope | 窗口 | 全局 / peer | peer 维度 |
|---|---|---|---|
| login | 1 分钟 | 300 / 5 | IP，不是登录账号 |
| register | 1 小时 | 200 / 10 | IP |
| create-order、create-user | 1 秒 | 各 50 / 3 | IP；登录用户额外检查用户 |
| quote、cart | 1 秒 | 各 100 / 10 | IP |
| catalog | 1 秒 | 200 / 20 | IP，商品列表/分类 |
| query | 1 分钟 | 600 / 30 | IP，批量查单/领取 |
| order-edge | 1 秒 | 100 / 20 | IP，状态/刷新/导出/TXID/repay 的粗入口 |
| misc | 1 秒 | 1000 / 50 | IP，其他普通 API，不含回调/商品详情/上传 |
| status | 1 分钟 | 6000 / 60 | 验证访问权后的 orderId |
| refresh | 1 分钟 | 600 / 10 | 验证访问权后的 orderId |
| export | 1 分钟 | 100 / 5 | 验证访问权后的 orderId |
| txid-upstream | 1 秒 | 5 / 2 | IP，和下一行原子组合 |
| txid-order | 24 小时 | 5000 / 默认 3 | 验证敏感归属后的 orderId |
| repay-upstream | 1 秒 | 20 / 2 | IP，和下一行原子组合 |
| repay-order | 30 秒 | 600 / 1 | 验证敏感归属后的 orderId |

所有 HTTP scope 默认每实例 8 个并发，可在策略中配置 1～64。瞬时窗口内的突发仍可能出现，因此并发保护单独保留；不能把滑动窗口描述成平滑队列。

普通接口在限流 Redis 不可用或容量不足时使用有界、nanoTime 计时的本地窗口，使用原全局额度四分之一（至少 1）作为单实例全局预算，peer 额度不放大。该故障预算随实例数增加，不能当作精确分布式额度。TXID/repay 采用 fail-closed，返回 503 且不触发外部调用或清除付款资料。商品限流保留原故障策略，支付回调只依赖其独立本地保护。

API_RATE_LIMIT_SHADOW=true 时观察频次拒绝，不阻断该频次策略；原有保护及 HTTP 并发上限仍执行。shadow 不等于已启用 TXID 5/s 硬保护。默认 false，启用真实执行。配置窗口变化可能产生一组新计数键，必须统一所有实例配置；不提供未验证的任意热更新接口。

## 配置、代理与观测

.env.example 列出 API_RATE_LIMIT_ENABLED、API_RATE_LIMIT_SHADOW、API_LIMIT_REDIS_ADDRESS/PASSWORD、API_LIMIT_PREFIX、API_LIMIT_MAX_PEERS、API_LIMIT_MEMORY_STOP_RATIO、WEBHOOK_GUARD_RPS/CONCURRENCY。策略可通过 api-rate-limit.policies.<scope>.window-ms/global/peer/concurrency 配置，启动时执行总预算校验。

新增独立 Redis 密码后排空旧 API，前后端一起升级并重启；旧前端不会为直接下单建立新会话，旧后端也不共享新策略。已有数据库配置优先，Java 缺省与种子统一为全站 25/s、设备开关启用、下单 15/h、查询 50/h、登录 10/h、注册 10/h、失败锁定阈值 10。设备计数器明确为本地固定窗口，保留单机上限并改用 nanoTime；订单 TXID 额度移到鉴权之后执行。

server.forward-headers-strategy=none，避免容器先改写来源后再次信任转发头。只由 ClientIpResolver 处理受信代理；配置必须是准确数字 IP，不接受主机名、CIDR 或整个内网默认放行。Nginx 必须覆盖 X-Real-IP/正确维护 X-Forwarded-For。Docker 环境先用 docker network inspect 确认实际网关，再配置 RATE_LIMIT_TRUSTED_PROXIES；示例的 loopback 不是生产网关答案。

管理员 GET /api/admin/risk/network-info 可比较 direct_peer、resolved_client、trusted_proxies；GET /api/admin/risk/rate-limits 查看生效策略、shadow、当前实例计数及分 scope 指标。Micrometer 的 simplecard.api.admissions 仅使用固定 scope/outcome 标签，含 allowed/rejected/degraded/shadow_rejected/concurrency_rejected/peer_budget/memory_budget，不以 IP、订单或用户作高基数标签。默认 SimpleMeterRegistry，可替换为外部采集 registry；不公开无鉴权的 /metrics。

生产实例数、生效 site_configs 和真实代理链仍需在目标部署核验，不能由 Compose 中只有一个服务名推断。当前配置无需扩容也能提供 P0 修复和统一额度语义，不承诺生产吞吐量。

## 复验

新测试 ApiRateLimitIntegrationTest 需 API_LIMIT_TESTS=true，专用 Redis 为 127.0.0.1:56382（simplecard-api-limit-test），会暂停/恢复该容器。ApiSafetyIntegrationTest 默认用 H2；PRODUCT_GUARD_POSTGRES_TESTS=true 时改用固定测试库 55440/product_guard_test，并重建测试表，不连接开发库。

在商品防护文档的全部隔离测试服务之外，额外启动：

```powershell
docker run -d --name simplecard-api-limit-test -p 127.0.0.1:56382:6379 redis:7.4-alpine redis-server --appendonly yes --maxmemory 64mb --maxmemory-policy noeviction
$env:API_LIMIT_TESTS='true'
$env:PRODUCT_GUARD_TESTS='true'
$env:PRODUCT_GUARD_POSTGRES_TESTS='true'
$env:INVENTORY_PG_TESTS='true'
$env:REDIS_COORDINATION_TESTS='true'
mvn -f apps/api/pom.xml '-Dspring.mail.host=localhost' test
```

覆盖双客户端共享精确额度、唯一 member、长窗口不被 2 秒 TTL 截断、全局拒绝不创建 peer、跨 scope 键预算、shadow、故障回退、组合配额不部分记账、并发许可，以及权限先于限额/外部调用、链上 429/超时不写待审、注册验证成功及失败、批量边界、并发登录不丢计数、脱敏导出审计、真实 IP 与编码路径匹配。

2026-11-13 本轮验证：所有集成开关开启，全套 108 项通过，0 失败、0 错误、0 跳过。其中新增 8 项独立限流 Redis 测试、5 项数据库安全测试、12 项边界/过滤器测试；原有 83 项商品缓存、库存、支付协调及业务测试全部通过。数据库安全和商品/库存场景使用真实隔离 PostgreSQL；额外验证了真实 JWT/Security 链在流量 Filter 前提供用户身份并在结束后清理上下文。前端 tsc --noEmit、Compose 配置和 git diff --check 通过。未运行线上流量或生产容量压测。
