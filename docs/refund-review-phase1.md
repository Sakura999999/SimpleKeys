# 第一批：人工退款占位与 TXID 审核一致性

本批只允许 PAID_REVIEW 整单全额退款。不开放 PAID、DELIVERED 退款，不接网关退款 API，不改变积分和邮箱授权。没有自动取消占位接口：结果未知必须保留占位并人工核查，不能通过改库重新发货。

## 管理员流程

1. 在订单详情点击“申请退款并禁止发货”，填写原因。接口 POST /api/admin/orders/{id}/refund-request，body 为 {"reason":"原因"}。
2. 申请成功后才能操作外部渠道退款。订单仍是 PAID_REVIEW，refund_requests.status=PROCESSING，库存预占保留。不要仅凭旧页面判断占位结果；重新读取订单详情确认。
3. 外部成功后点击“确认外部退款成功”，提交唯一外部流水号至原 refund-record 接口。一个事务中确认退款记录、释放 LOCKED 库存、把订单变为 REFUNDED。
4. 外部结果未知时保留 PROCESSING，核实渠道结果，不重复打款。PROCESSING 表示禁止交付，不表示系统已发起渠道退款。

amount/currency 是订单结算金额快照；payment_amount/payment_currency 是支付侧法币快照，USDT 实际转账金额仍需核对原订单 crypto_amount 和链。当前凭证唯一范围为 payment_method + reference；一笔流水不支持登记到同渠道多个订单。不同商户账户/批量退款是后续能力，不能通过虚构流水号绕过。

重复申请返回同一记录。确认成功后同凭证重试幂等，不同凭证拒绝。付款 Inbox 保留验证事实并正常结束处理，但退款占位后不能触发分配/交付。退款占位与发货谁先锁定订单谁先处理；已经完成交付则申请失败，管理员此时不能继续外部退款。

TXID 审核由 TxidReviewService 在同一事务内锁审核记录、锁订单、处理库存，再保存结论。仅 PENDING/EXPIRED 可进行新的付款审批。无订单关联、订单不存在、TXID 冲突、订单已终结或正在退款均报错且保留原审核状态；不会静默 APPROVED。无库存时允许审批成功并进入 PAID_REVIEW。重复审批/拒绝只有一次成功；日志打印 resultingStatus。

## 发布与迁移

本批提供显式 SQL 迁移和检查清单，尚未引入 Flyway，也没有代用户执行生产数据库迁移。

1. 在隔离环境演练；在实际数据库只读核查下面的预检 SQL。
2. 暂停后台退款和审批、支付处理及定时任务，停旧实例，完成数据库备份并确认恢复方式。所有 API 必须一起切换；旧实例不会识别退款占位，不能混跑。
3. 使用 psql -v ON_ERROR_STOP=1 -f apps/api/src/main/resources/db/refund-review-phase1.sql。脚本整体事务执行，锁等待最多5秒，发现重复 TXID、重复历史凭证或缺失退款金额时失败回滚。不要自动清洗或删除资金记录。
4. 核查 refund_requests 和 TXID 唯一索引的实际定义与有效性；IF NOT EXISTS 不能替代结构核查。历史 REFUNDED 回填 SUCCEEDED，但实际退款时间和操作者未知，confirmed_at 保持 NULL；created_at 是迁移登记时间。
5. 部署新版前后端，验证申请、发货拦截、付款重复通知、退款确认和审核竞争，再恢复流量。
6. 已存在 PROCESSING 申请后禁止直接回滚旧版，否则会失去发货拦截。先停止相关入口与 worker，保留新版本保护并人工核查，再制定回滚方案。

预检（只读）：

```sql
SELECT usdt_tx_id,count(*) FROM orders WHERE usdt_tx_id IS NOT NULL GROUP BY 1 HAVING count(*)>1;
SELECT coalesce(payment_method,'legacy_unknown'),btrim(refund_reference),count(*)
FROM orders WHERE status='REFUNDED' AND nullif(btrim(refund_reference),'') IS NOT NULL GROUP BY 1,2 HAVING count(*)>1;
SELECT id FROM orders WHERE status='REFUNDED' AND (actual_amount IS NULL OR actual_amount<=0);
SELECT indexrelid::regclass,indisunique,indisvalid,pg_get_indexdef(indexrelid)
FROM pg_index WHERE indrelid='orders'::regclass;
SELECT conname,pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid IN ('orders'::regclass,'card_keys'::regclass);
```

验收（迁移后）：

```sql
SELECT r.order_id FROM refund_requests r JOIN orders o ON o.id=r.order_id
WHERE (r.status='PROCESSING' AND o.status<>'PAID_REVIEW') OR (r.status='SUCCEEDED' AND o.status<>'REFUNDED');
SELECT r.order_id FROM refund_requests r JOIN card_keys k ON k.order_id=r.order_id
WHERE r.status='PROCESSING' AND k.status='SOLD';
SELECT o.id FROM orders o LEFT JOIN refund_requests r ON r.order_id=o.id
WHERE o.status='REFUNDED' AND r.id IS NULL;
```

## 自动验收

仅使用 disposable inventory_test 数据库（127.0.0.1:55439）与测试 Redis（127.0.0.1:56379），不要指向生产。设置 INVENTORY_PG_TESTS=true、REDIS_COORDINATION_TESTS=true，运行：

```text
mvn -f apps/api/pom.xml -Dtest=RefundReviewPostgresIntegrationTest,RedisRefundReviewIntegrationTest test
```

本机 JDK25 需要额外参数 -Dmaven.compiler.proc=full -Dlombok.version=1.18.42 -Dnet.bytebuddy.experimental=true。测试覆盖占位/交付竞争、重复确认、凭证冲突回滚、付款 Inbox 不丢事实、重复审批、审批/拒绝竞争、异常审核回滚及原有库存规则。数据库迁移另在隔离测试库验证脏数据拒绝、历史回填、可重复执行。

## 本次本地验证（2026-11-16）

- PostgreSQL/Redis 双模式：48 项通过，零失败。
- 常规 Maven 回归：36 项实际执行通过，120 项因环境开关跳过；不把跳过项计为通过。
- 前端 tsc --noEmit --incremental false 通过，git diff --check 通过。
- 隔离迁移库验证：重复 TXID 拒绝、历史退款回填、重复执行不增加记录。未连接生产数据库。
- 本次专用容器 simplecard-refund-phase1-pg、simplecard-refund-phase1-redis 验收后停止，保留供重跑；其他本地服务不受影响。
