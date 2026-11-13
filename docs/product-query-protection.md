# 商品查询缓存与穿透、击穿、雪崩防护

范围为公开 `GET /api/products/{UUID}`。仅标题、封面、简介、正文及对应翻译进入展示缓存；价格、规格、批发价、库存、销量和上下架状态仍从数据库读取。不缓存整份商品详情，也不改变下单、支付、发货和订单查询的业务判断。布隆过滤器不提供鉴权或防超卖能力。

## 展示缓存与击穿保护

每次请求先捕获缓存上下文，再经现有防护读取不含大段正文的商品投影并检查软删除和上下架；数据库并发许可一直覆盖后续规格、统计、展示缓存重建和响应组装。缓存命中不能跳过可见性校验，基础价、规格价和批发价始终取当前数据库查询结果。公开规格及其库存只包含可见规格；管理端仍可查看隐藏规格。

规格列表复用一次查询，全部规格及默认库存池按 spec_id/status 一次聚合，销量查询一次。暖缓存多规格详情为 5 条固定 SQL（商品投影、规格、库存、销量、批发规则），不随规格数量线性增加；冷缓存多一次正文查询。命中缓存仍占现有 100 次/秒详情回源预算，并非零查库。预算统计的是详情请求，不是 SQL 条数。列表只复用统计优化，本次不新增列表、搜索或前端页面缓存，前端 no-store 保持不变。

缓存未命中时，同一实例按商品 ID 和上下文合并重复请求；跨实例使用显式商品 Redis 客户端上的 Redisson 锁，获得锁后再次检查缓存。获取、释放都在同一请求线程，使用 watchdog。等待预算默认 100ms，可配置到最多 200ms，不包括 Redis 网络超时。仍无结果则返回 503，不能让所有等待失败的请求自行回源。Redis 连接故障或无法创建锁时，可以在已经取得的原有回源预算内直接读取正文，仍受总速率及 8 个并发许可约束。

不使用逻辑过期返回旧值、不新增后台刷新线程池、不预热。单个正常重建任务查询正文；同商品其他请求读取其结果或有限等待，不创建无界队列。已有其他字段的实时 SQL 不在这个合并范围内。

## 更新竞争与雪崩保护

展示缓存上下文包含 Redis 运行实例和现有发布 token 派生的全局更新代次。登记商品修改时在原 Lua 操作内切换代次；读、回填均原子检查代次、索引就绪与商品 dirty 状态。dirty 商品直接受限查询数据库，不写展示缓存。事务结束后要求现有维护器尽快重建，正常约下一次 10 秒检查恢复缓存资格。进程崩溃遗留标记继续由数据库发布屏障恢复。

这里采用保守的全局失效：一次商品修改会使之前代次的展示记录不可再命中，适用于当前低频后台编辑场景；未引入第二套每商品计数器。健康布隆过滤器正常换代不改变展示代次，不会按小时让全部展示缓存同时失效。Redis 重启必须重新确认索引就绪，旧实例的展示数据不会直接使用。外部直接修改数据库时必须清理展示缓存及索引后重建，只重建布隆不能刷新正文。

每条展示记录随机有效 300～420 秒，过期直接视作未命中，不返回陈旧内容。实现使用一个全局 Hash 与一个过期时间 ZSet；Redis TIME 判断逐条过期，写入和已有后台维护器删除到期记录，整个集合空闲 840 秒后物理删除。这样可以原子执行跨实例容量配额，不为随机请求创建任意缓存键。

全实例最多 2000 条，单条 JSON 最多 16 KiB，两个集合的 MEMORY USAGE 加新增记录保守开销受 32 MiB 默认预算约束。Redis 总 used_memory 接近 maxmemory 的 80% 时拒绝新增展示写入；70% 起按分钟告警。容量拒绝、超大正文或缓存回填失败仍正常返回本次数据库结果，不能因此让正常读取失败。配额不是为其他元数据预留内存的绝对保证，仍需监控布隆双版本、限流、发布标记、Redis 持久化及进程内存。

大量不同商品一起失效时，所有重建沿用同一个回源速率和 Semaphore，不额外开后台或旁路配额。价格和库存的交易判断完全不依赖展示缓存。

## 内存不足与脚本故障

OOM/容量错误与连接故障分开处理，容量错误不触发全读写共用的连接熔断。索引状态、dirty、空值和展示命中走 no-writes 只读脚本，使可用读取在 noeviction 写满时继续工作；删除到期展示记录使用只释放内存的 allow-oom 脚本。需要 Redis 7.4（与 Compose 固定版本一致）。

发布脚本先校验待清理计数，再切换；不假设 Lua 运行出错会回滚已经执行的命令。失去发布响应后的临时版本清理原子检查 active 指针，避免给已经生效的版本误设临时 TTL。回归覆盖 OOM 下发布后的指针与有效位图关系，以及有故障时的保守读取。

## 请求链路与预算

早期 HTTP 过滤器先做限流和 UUID 格式检查，再经过空值缓存、布隆判断，必要时受限查询数据库。数据库仍检查商品是否删除或下架。布隆判断“可能存在”不能直接当作商品存在。

默认预算为所有实例共享的商品详情入口 300 次/秒、单 IP 30 次/秒、数据库回源 100 次/秒，使用 Redis ZSet + Lua 原子滑动窗口。IP 复用 ClientIpResolver，仅信任 `rate-limit.trusted-proxies` 中的代理；代理必须覆盖或清理客户端伪造的转发头。原有通用限流仍生效，最终有效阈值受各层共同限制。

每实例最多 32 个商品详情请求进入防护，最多 8 个详情查询同时访问数据库，包括组装详情时的库存查询。Redis 故障时，每实例入口和数据库各使用 20 次/秒本地预算；故障预算随实例数增加，不能当成全局 20 次/秒。入口超限返回 429，回源或并发预算不足返回 503，不伪装成商品不存在。网络带宽和连接数仍需 Nginx/CDN/WAF 保护。

空值缓存随机有效 30～60 秒，以有数量上限的 ZSet 保存，默认最多 5000 个 ID，集合空闲 120 秒后删除。布隆直接拒绝的随机 ID 不创建空值记录。空值缓存按过滤器版本隔离。

## 索引与发布一致性

使用 Redisson 3.52.0 的 RBloomFilter，不依赖 RedisBloom 模块。容量取配置下限与（数据库商品数 + 待补齐记录数）三倍中的较大值，目标误判率默认 0.1%。全部商品 ID 可入索引，包括下架和软删除商品；公开可见性仍由数据库判断。

商品新建、修改、上下架、删除事务先进入发布屏障，提交前登记 Redis 同步标记。带标记的 ID 绕过布隆和空值缓存，受限回源。标记按事务 token 计数，并发修改不会互相清除标记。空值回填检查标记及版本，防止旧请求覆盖刚发布商品的可见性。Redis 无法登记时，商品修改事务回滚并返回繁忙。

生产 PostgreSQL 使用事务级 advisory lock，固定键为 `725614829031`，无需新增数据库表或迁移存量数据。屏障只协调低频商品写事务，以及重建扫描前的短暂同步。重建先等待已有发布事务结束，再在屏障内恢复进程崩溃遗留标记，之后释放屏障进行扫描。即使 Redis 重启且最近标记未持久化，也不会漏掉仍在提交的旧事务。公开查询及整个扫描过程不持有此锁。H2 仅使用单进程测试屏障，生产实现限定 PostgreSQL。

重建使用新版本键、每批 500 个 UUID 的游标查询、同步标记补齐和原子切换。分布式锁避免重复扫描，构建 token 防止旧构建者覆盖新版本。发布脚本同时保留新版本、切换指针、清理已覆盖标记，并给旧版本设置一小时保留期。临时版本两小时后回收。失败不会先删除有效索引。

启动后尝试构建，每 10 秒检查恢复需求，正常完整重建间隔一小时。Redis 重启后，即使 AOF 恢复旧指针，也会因运行实例 run_id 改变而停止严格拦截，受限回源直到重建完成。位图丢失同样不能据此拒绝商品。Redis 使用 noeviction，避免只淘汰部分索引键。

重建运行在独立后台线程，同一实例不积压重复任务，避免扫描阻塞支付事件重试及超时关单所使用的共享调度器。

## 部署

商品缓存使用独立 Redis，不能指向交易锁 Redis；不同逻辑数据库或 key 前缀不能隔离资源。Compose 新增 `product-cache` 服务，无公网端口，启用认证、AOF、128 MB maxmemory、noeviction 和独立数据卷。在 `.env` 设置独立的 `PRODUCT_CACHE_REDIS_PASSWORD`。API 读取 `PRODUCT_CACHE_REDIS_ADDRESS`，Compose 内为 `redis://product-cache:6379`，本机默认端口为 6380。

`.env.example` 列出开关、前缀、预计容量、误判率、各层速率、数据库并发、空值数量和重建间隔。展示缓存配置为 PRODUCT_DISPLAY_CACHE_ENABLED、PRODUCT_DISPLAY_MAX_ENTRY_BYTES、PRODUCT_DISPLAY_MAX_ENTRIES、PRODUCT_DISPLAY_MAX_BYTES、PRODUCT_DISPLAY_MEMORY_STOP_RATIO、PRODUCT_DISPLAY_WAIT_MS。所有实例应统一配置、数据库及缓存 Redis，不同环境隔离前缀。Redis 账号须允许相关数据结构、Lua、INFO server/memory 及 MEMORY USAGE。

排空旧 API 实例后统一部署新版本；旧版本即使登记过防穿透标记，也不会切换展示缓存代次，不能与开启新展示缓存的版本混跑。若滚动升级，先在全部实例关闭展示缓存和防护，升级所有写入端后清理本功能命名空间，再统一开启和重建。绕过应用直接导入或修改数据库时，也必须先暂停防护、清理缓存并重建，外部写入不会自动维护标记。

无需数据库迁移。生效需要启动独立缓存服务并重启 API；代码修改和测试不代表已经部署线上。

本机开发容器为 `simplecard-product-cache-dev`，仅绑定 `127.0.0.1:6380`。随机密码保存在忽略提交的 `.local-tools/product-cache-dev.conf` 和 `apps/api/.local-config.properties`，不要打印或提交这些文件。Docker 启动后可执行 `docker start simplecard-product-cache-dev`。

## 观测与验证

每分钟记录各实例累计 bloomRejected、negativeHits、databaseQueries、degraded、rateRejected，故障日志每 30 秒最多一条。预期的商品不存在和限流错误不逐请求写警告。监控缓存内存、回源次数、503、重建耗时和成功情况。默认阈值是初始保护预算，需根据部署机器及业务压测调整。

`ProductProtectionIntegrationTest` 和 `ProductCacheIntegrationTest` 仅在 `PRODUCT_GUARD_TESTS=true` 时运行。商品 Redis 为 56380，订单 Redis 为 56381，确为两个进程；前者的容器会被暂停或重启，并进行 128 MB 填满演练。默认数据库为独立 H2。另设 `PRODUCT_GUARD_POSTGRES_TESTS=true` 时使用专用 PostgreSQL `127.0.0.1:55440/product_guard_test`，容器名 `simplecard-product-postgres-test`，测试账号为 `guard_test/guard_test_only`。Hibernate 会重建该测试库，禁止将测试端口转发到真实数据库。`ProductGuardFilterTest` 可在普通测试中运行。

覆盖随机 ID、误判、空值数量上限、晚回填、上下架、事务回滚、标记恢复、多批 UUID 分页、并发上限、共享滑动窗口、重建失败、扫描期间发布，以及真实 Redis 暂停、重启和丢标记时的在途事务。上述验证不代表已完成生产容量压测。

2026-11-13 最新验证：全套 83 项通过，0 失败、0 错误、0 跳过。包含新增 16 项展示缓存测试、17 项防穿透测试、12 项 PostgreSQL 库存测试、19 项 Redis 交易协调测试及其余常规测试；新增两套商品防护测试均使用真实 PostgreSQL 和双 Redis。另一次 H2/双 Redis 常规运行通过 52 项。已验证 20 个规格的暖详情为 5 条 SQL、冷请求合并、锁等待超时、集中失效、下架与软删、价格即时变化、旧任务晚回填、128 MB 写满后的只读防护和发布状态、默认 300/30/100 预算以及交易 Redis 故障域隔离。Compose 校验通过；没有进行线上部署或生产容量压测。

可用以下 PowerShell 命令准备专用容器并复验（不要替换成真实服务地址）：

```powershell
docker run -d --name simplecard-product-guard-test -p 127.0.0.1:56380:6379 redis:7.4-alpine redis-server --appendonly yes --maxmemory 128mb --maxmemory-policy noeviction
docker run -d --name simplecard-product-order-redis-test -p 127.0.0.1:56381:6379 redis:7.4-alpine redis-server --appendonly yes --maxmemory 64mb --maxmemory-policy noeviction
docker run -d --name simplecard-product-postgres-test -p 127.0.0.1:55440:5432 -e POSTGRES_DB=product_guard_test -e POSTGRES_USER=guard_test -e POSTGRES_PASSWORD=guard_test_only postgres:18-alpine
$env:PRODUCT_GUARD_TESTS='true'
$env:PRODUCT_GUARD_POSTGRES_TESTS='true'
mvn -f apps/api/pom.xml '-Dtest=ProductProtectionIntegrationTest,ProductCacheIntegrationTest' test
$env:PRODUCT_GUARD_POSTGRES_TESTS='false'
mvn -f apps/api/pom.xml '-Dspring.mail.host=localhost' test
```

首次启动 PostgreSQL 后等待容器就绪再运行测试。测试结束可删除上述三个专用容器及其测试卷，保留开发 Redis。原有交易可靠性套件另需 inventory_test 专用 PostgreSQL 55439 和 Redis 56379，并显式设置 INVENTORY_PG_TESTS=true、REDIS_COORDINATION_TESTS=true，详见对应文档。

全套复验额外准备以下两个专用容器，待就绪后显式开启所有开关：

```powershell
docker run -d --name simplecard-inventory-cache-test -p 127.0.0.1:55439:5432 -e POSTGRES_DB=inventory_test -e POSTGRES_USER=inventory_test -e POSTGRES_PASSWORD=inventory_test postgres:18-alpine
docker run -d --name simplecard-redis-integration -p 127.0.0.1:56379:6379 redis:7.4-alpine redis-server --maxmemory 64mb --maxmemory-policy noeviction
$env:PRODUCT_GUARD_TESTS='true'
$env:PRODUCT_GUARD_POSTGRES_TESTS='true'
$env:INVENTORY_PG_TESTS='true'
$env:REDIS_COORDINATION_TESTS='true'
mvn -f apps/api/pom.xml '-Dspring.mail.host=localhost' test
```
