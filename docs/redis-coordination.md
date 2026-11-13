# Redis 分布式协调与付款补偿

## 实现边界

- 使用 Redisson 3.52.0 的 RLock。通过不显式指定 leaseTime 的 tryLock(waitTime, unit) 启用 watchdog 续租。
- 锁顺序为：请求幂等锁（建单时）→订单锁→按商品/规格排序的库存锁→数据库事务。获取不完整则逆序释放，不进入库存事务。
- 初次建单时订单尚未对其他事务可见，使用请求/购物车锁和库存池锁；创建后所有订单修改使用相同订单锁。
- RedisCoordinationAspect 在事务和数据库重试切面之外执行。事务提交/回滚完成后才释放 Redis 锁。嵌套调用检查已持有的锁及库存范围，禁止在未协调的调用者事务里临时获取 Redis 锁。
- 数据库行锁、唯一约束、幂等发货及积分逻辑继续负责数据正确性。Redis 锁过期、断线、进程暂停不代表旧线程会被强行终止。
- 普通查询无需 Redis 写锁；只在需要实际关单时进入协调服务。Redis 故障时，新下单快速失败，关单任务下次继续处理。

## 已核验付款的可靠接收

Webhook 验签、金额/交易核验成功后，先在 PostgreSQL 的 verified_payment_events 持久化事件（交易事件标识唯一），再尝试获取锁处理订单。仅在可靠保存之后返回渠道的接收成功响应。

处理时将订单/卡密修改和事件 processed=true 放在同一个数据库事务。Redis 不可用或锁竞争时保存 retry_count、next_attempt_at、脱敏错误类型；每5秒扫描最多10条到期事件，退避最长5分钟，持续重试并记录警告。已有核验付款待处理的订单不会被超时任务释放库存。

前台付款页面显示“付款已收到，正在确认发货”，并提示不要重复付款。后台订单页显示待处理事件，可立即重试。对应接口为 GET /api/admin/payment-inbox、POST /api/admin/payment-inbox/{id}/retry，均要求管理员身份。

## 配置与启动

生产默认 REDIS_COORDINATION_ENABLED=true。H2/旧数据库单元测试显式关闭 Redis；集成测试显式开启。Redis 故障不会自动关闭协调开关，也不会放开新下单入口。

配置项：REDIS_ADDRESS、REDIS_PASSWORD、REDIS_LOCK_PREFIX、REDIS_LOCK_WAIT_MS（默认200毫秒，整个锁集合的等待预算，不包含Redis网络超时）、REDIS_LOCK_WATCHDOG_MS（默认30000毫秒）。前缀必须在同一部署的所有 API 实例间一致，不同环境使用不同前缀。短事务默认15秒超时，续租失败仍由数据库兜底。

Compose 增加内部 redis 服务与持久卷，启用认证、AOF、noeviction，不暴露 Redis 端口到公网。请在 .env 设置随机 REDIS_PASSWORD；API 等待 Redis 健康后启动。Redis 容量、持久化、可用性和告警仍须按服务器实际条件配置。

本机开发 Redis 名称为 simplecard-redis-dev，仅绑定127.0.0.1:6379。随机密码保存在忽略提交的 .local-tools/redis-dev.conf 和 apps/api/.local-config.properties；Docker Desktop 启动后可执行 docker start simplecard-redis-dev。不要打印或提交这些配置文件。

## 升级

先备份数据库，执行 apps/api/src/main/resources/db/redis-coordination.sql，再部署新 API 与前端。该脚本仅新增事件表和索引，不修改订单或卡密。旧的 inventory-reservation.sql 仍需在尚未迁移的数据库上执行。

开启协调前排空旧 API 实例；多个实例应同时使用统一锁前缀和配置。启动后检查待处理付款列表、Redis可用性、锁等待/失败日志及事件重试次数。部署期间已收到的付款事实始终以数据库记录为准。

## 隔离验证

RedisInventoryIntegrationTest 使用127.0.0.1:56379的 simplecard-redis-integration 容器和127.0.0.1:55439/inventory_test，不连接开发或线上数据库。设置 REDIS_COORDINATION_TESTS=true 后执行 mvn -f apps/api/pom.xml -Dtest=RedisInventoryIntegrationTest test。暂停故障测试会短暂 docker pause 该专用 Redis，随后在 finally 恢复。

测试覆盖：原有预占/付款/退款不变量、付款事件锁竞争后的恢复、Redis暂停后的可靠接收、watchdog续租、过期持有者解锁保护、提交先于解锁、两个独立 JVM 同时付款/关单、持锁进程被强制终止后的租期释放，以及 Redis 锁丢失后的数据库保护。它们验证正确性，不代表已经完成容量压测。
