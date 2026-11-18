# 查询性能基线（第四批 · 第 1 条交付物）

> 测量日期：2026-11-16
> 目的：在改动任何查询之前记录当前状态，为第 2、3 条的优化提供对照基准。
> **本文所有数字来自独立基准库的造数测量，不是生产实测。** 结论成立的前提是数据量级接近本文假设，见 §5。

---

## 1. 测量环境

| 项 | 值 |
|---|---|
| 数据库 | PostgreSQL 18（便携版，`S:/simplecard-dev/postgresql18`） |
| 基准库 | `simplecard_bench`（从 `simplecard` 复制结构后造数，**不影响开发库**） |
| 数据量 | 商品 1,000 / 规格 800 / 订单 100,000 / 订单项 200,000 / 卡密 100,000 / 用户 2,002 |
| 时间分布 | 订单与卡密均匀分布在过去 365 天 |
| 订单状态分布 | DELIVERED 75% / PAID_REVIEW 5% / PAID 5% / PENDING 5% / EXPIRED 3% / REFUNDED 7% |
| 表体积 | `orders` 25 MB（3,125 buffers，全部驻留 shared_buffers） |
| 客户端 | JDBC（postgresql 42.7.4），同机 TCP，复用 PreparedStatement |
| 预热 | 每个连接先执行一次 `SELECT 1`，避免连接建立开销混入 |

**为什么用 JDBC 而不是 psql**：psql 每次调用都是新进程、新连接，单条查询往返约 1.5 ms，会把真实成本放大 8 倍以上。JDBC 复用连接后的数字才接近应用实际。

---

## 2. 基线数据

### 2.1 Dashboard 指标查询

| 编号 | 查询 | 现状耗时 | 执行计划 | 判定 |
|---|---|---|---|---|
| B1 | `sumSalesSince(今日)` | 12–16 ms | Seq Scan，扫 100,000 行，`Rows Removed by Filter: 96,519` | 可接受 |
| B2 | `sumSalesSince(本月)` | 24–30 ms | 同上，返回 3,481 行 | 可接受 |
| B3 | `countPaidOrdersSince(本月)` | 13.8 ms | 同上 | 可接受 |
| B4 | `visitStats.findByVisitDate` | 1.1 ms | 主键/日期索引 | 无需处理 |
| B5 | `productRepository.findAll()`（1,000 行） | 2.5 ms | Seq Scan 1,000 行 | 可接受 |

B1/B2/B3 都是**全表扫**，因为 `orders` 上没有任何以 `paid_at` 开头的索引。但扫 10 万行只要 10–30 ms，因为表只有 25 MB 且全在共享缓冲区里。**在当前量级下这不是瓶颈。**

### 2.2 库存预警：N+1

| 编号 | 查询 | 现状 | 优化后对照 |
|---|---|---|---|
| B6 | 逐商品 `countByProductIdAndStatus` × 1,000 次 | **201 ms**，1,000 条 SQL | 单条 GROUP BY **32 ms**，1 条 SQL |

- 单次查询走 `idx_keys_pool_status` 索引（Index Only Scan，`Heap Fetches: 0`），PG 侧仅 0.26 ms，**但 1,000 次往返累积成 201 ms**
- 单条 `SELECT product_id, count(*) ... GROUP BY product_id` 全表扫 9 万行只要 32 ms

**结论：这里该改的不是索引，是查询次数。** 5 倍差距，且 SQL 条数从 1,000 降到 1。

### 2.3 销售趋势：全表加载

| 编号 | 查询 | 现状 | 说明 |
|---|---|---|---|
| B7 | `orderRepository.findAll()` 后内存过滤 | **543–697 ms**（中位约 600 ms） | 未索引状态，取 100,000 行 |
| B8 | 同样查询但限定 30 天（应该加载的量） | **59–65 ms**，仅 7,498 行 | 对照基准 |
| B9 | 加载 100,000 行后的堆占用 | **92 MB**（964 B/行） | `-Xmx1g` 下测得 |

**B7 与 B8 差 9.4 倍，堆占用差 13 倍。** 这是本批最确定的一处浪费：查询区间只有 30 天，却把全部历史订单载入内存再过滤。

按 B9 的比例外推：

| 订单总量 | `findAll()` 堆占用 | 影响 |
|---|---|---|
| 10 万 | ~92 MB | 单次请求可承受 |
| 50 万 | ~460 MB | 与常规请求叠加后接近默认堆上限 |
| 100 万 | ~920 MB | 必然 OOM |

`DashboardServiceImpl:83` 的注释 `Still load filtered orders for trend grouping` 说明这是已知未修项。

### 2.4 后台订单搜索

| 编号 | 查询 | 现状 | 说明 |
|---|---|---|---|
| B10 | `findAdminOrdersByKeyword`（DISTINCT + LEFT JOIN + `str(id)` LIKE） | **156–160 ms** | 返回 1 行 |

执行计划（无额外索引）：

```
Limit → Unique → Gather Merge → Sort(30 列) → Parallel Hash Left Join
  ├─ Parallel Seq Scan on orders     (Filter: status='DELIVERED')
  └─ Parallel Hash → Parallel Seq Scan on order_items (200,000 行)
```

两个成本点：

1. **`DISTINCT o.*` 强制按全部 30 个列排序**（`Sort Key` 里逐列列出 `o.id, o.updated_at, o.actual_amount, ...`），目的是去重，但订单表本来 `id` 唯一，`DISTINCT` 只在 JOIN 放大行数时才必要
2. **`CAST(o.id AS varchar) LIKE '%...%'`** 把 UUID 主键转成字符串再做中缀匹配，主键索引必然失效，且这行条件永远不可能命中有效输入（用户不会输入 UUID 片段搜索）

### 2.5 商品搜索

| 编号 | 查询 | 现状 | 说明 |
|---|---|---|---|
| B11 | `LOWER(title) LIKE '%...%' OR LOWER(title_en) LIKE` | **0.53 ms** | Seq Scan 1,000 行，`Rows Removed: 989` |

**这一项推翻了我此前的判断。** 之前评审建议引入 `pg_trgm` + GIN 索引，但实测 1,000 行全表扫只要 0.53 ms。**在当前商品量级下加索引没有意义**，只会增加写放大和运维负担。

---

## 3. SQL 条数（按代码推导，非实测）

| 接口 | SQL 条数 | 构成 |
|---|---|---|
| `GET /admin/dashboard`（`getStats`） | **5 + N** | 4 条聚合 + 1 条 `visitStats` + 1 条 `products.findAll()` + N 条卡密 count。N=1,000 时约 **1,005 条** |
| `GET /admin/dashboard/sales-trend`（`getSalesTrend`） | 1 | 但该 1 条取 100,000 行 |

1,005 条 SQL 对单次请求的直接耗时约 200 ms（B6），但真正的代价在别处：
- 每条 SQL 都过一遍 Hikari 连接（池上限 20），长事务会挤占下单请求的连接
- 每条都进慢查询日志/APM，1,005 条会把真实慢查询淹没
- 每条都触发一次 Hibernate 查询计划缓存查找（虽有缓存，但参数绑定与结果映射无法省）

---

## 4. 索引对照实验（关键，结论反直觉）

在基线库上临时建立三个候选索引，重测后删除：

| 索引 | 变更前 | 变更后 | 结论 |
|---|---|---|---|
| `orders(paid_at)` | 今日 12–16 ms / 本月 24–30 ms | 今日 **10.9 ms** / 本月 **49.9 ms** | 窄范围略好，**宽范围反而变慢一倍** |
| `orders(created_at DESC)` + `order_items(order_id)` | 订单搜索 **156 ms** | 订单搜索 **280 ms**（EXPLAIN 312 ms） | **变慢 80%** |

为什么加索引会变慢：

- **`orders(paid_at)` 对本月查询**：本月有 3,481 行命中，PG 改用索引扫描后要回表 3,481 次；而 Seq Scan 顺扫 25 MB 只要 24 ms。**顺序扫比随机回表便宜**。
- **订单搜索**：加了 `created_at` 索引后，PG 从 `Hash Join + Seq Scan` 改成 `Index Scan + Nested Loop`，对 7.5 万个 DELIVERED 订单逐行回表、逐行查 `order_items`。而原计划的 Hash Join 一次建表 17 MB 就完成匹配。

**这是本次测量最有价值的一条**：对于这个查询，**加索引不是"没效果"，而是"负效果"**。必须先改写法（去掉 `str(id)` 条件、用 EXISTS 替代 DISTINCT），再讨论索引；顺序颠倒会得到"优化后更慢"的结果。

补充对照实验：把 `DISTINCT + LEFT JOIN` 改为 `EXISTS` 子查询后，执行计划降到 **163 ms**（未加任何索引），已优于原写法 156→312 ms 的区间下限。

---

## 5. 结论

### 该改（按收益排序）

| 优先级 | 项 | 现状 | 目标 | 依据 |
|---|---|---|---|---|
| P1 | `getSalesTrend` 全表加载 | 600 ms + **92 MB 堆** | 单条 SQL 按日期聚合 | B7/B8/B9，堆占用随订单量线性增长，是唯一有 OOM 风险的一项 |
| P1 | 库存预警 N+1 | 1,005 条 SQL / 201 ms | 2 条 SQL / 32 ms | B6 |
| P2 | 后台订单搜索写法 | 156 ms，加索引会到 312 ms | 先改写法至 ~163 ms，再评估索引 | B10 + §4 |
| P2 | SQL 条数可观测性 | 1,005 条/请求 | 降到个位数 | 连接池占用与日志污染 |

### 不该动（推翻此前建议）

| 项 | 此前建议 | 实测 | 修正结论 |
|---|---|---|---|
| 商品搜索加 `pg_trgm` | 建议加 | 0.53 ms / 1,000 行 | **不加**，量级不够 |
| `sumSalesSince` 的 `paid_at` 索引 | 建议加 | 今日 12→10.9 ms，本月 24→49.9 ms | **不加**，收益不稳定且有负效果 |
| `orders` 的 `created_at` 索引 | 建议加（订单列表排序） | 订单搜索 156→280 ms | **不加**，必须先改写查询 |

### 数据量敏感性（重要限定）

本文结论基于 **10 万订单 / 1,000 商品**。各结论的失效点不同：

| 结论 | 失效条件 |
|---|---|
| `sumSalesSince` 可接受 | 订单超过约 100 万时，Seq Scan 从 25 ms 升到约 250 ms，需重新评估 |
| 商品搜索不需要索引 | 商品超过约 5 万时，全表扫从 0.5 ms 升到约 25 ms，届时应加 |
| 订单搜索必须先改写法 | 无条件成立（`str(id)` 条件在任何数据量下都必然全表扫） |
| `getSalesTrend` 必须改 | 无条件成立（堆占用与订单总量成正比） |

**如果生产订单量已经远超 10 万，请以生产量级重跑本文 §6 的命令再定优先级。**

---

## 6. 复现方法

基准库保留在 `simplecard_bench`，可直接复测。

```bash
# 环境
BIN="S:/simplecard-dev/postgresql18/pgsql/bin"
export PGPASSWORD=postgres PGCLIENTENCODING=UTF8

# 重建基准库（如需）
"$BIN/psql" -h 127.0.0.1 -U postgres -d postgres -c "DROP DATABASE IF EXISTS simplecard_bench;"
"$BIN/psql" -h 127.0.0.1 -U postgres -d postgres -c "CREATE DATABASE simplecard_bench;"
"$BIN/pg_restore" -h 127.0.0.1 -U postgres -d simplecard_bench --no-owner /tmp/simplecard.dump
"$BIN/psql" -h 127.0.0.1 -U postgres -d simplecard_bench -f "S:/simplecard-dev/bench/seed.sql"

# 测量
cd S:/simplecard-dev/bench
java -cp "C:/Users/SIMPLE/.m2/repository/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar" Measure.java
java -Xmx1g -cp "C:/Users/SIMPLE/.m2/repository/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar" MeasureMem.java

# 执行计划
"$BIN/psql" -h 127.0.0.1 -U postgres -d simplecard_bench -f "S:/simplecard-dev/bench/explain.sql"
```

脚本位置：
- `S:/simplecard-dev/bench/seed.sql` — 造数（可改行数调节量级）
- `S:/simplecard-dev/bench/Measure.java` — 耗时与 SQL 次数
- `S:/simplecard-dev/bench/MeasureMem.java` — 堆占用
- `S:/simplecard-dev/bench/explain.sql` — 执行计划

---

## 7. 与验收标准的对应

用户第四批验收重点第 1、2 条：

| 验收标准 | 基线证据 | 达标判据 |
|---|---|---|
| 小日期范围查询不再把全部订单加载到 Java 内存 | 现状 100,000 行 / 92 MB；30 天实际只需 7,498 行 / 约 7 MB | 改后 `getSalesTrend` 的堆增长 < 5 MB，且 SQL 为单条聚合 |
| 库存预警查询次数不随商品数量逐个增长 | 现状 1,005 条 SQL / 201 ms | 改后固定 2 条 SQL，与商品数无关 |
