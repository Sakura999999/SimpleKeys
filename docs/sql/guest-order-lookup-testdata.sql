-- 访客订单找回 / 手工验收测试数据
--
-- 用途：在开发库里造出四种归属组合，覆盖「只能找回访客订单」的全部边界。
-- 特性：可重复执行（先删后插，固定 UUID 便于脚本断言）。
--
-- 用法（项目根目录）：
--   psql -h 127.0.0.1 -U postgres -d simplecard -f docs/sql/guest-order-lookup-testdata.sql
--
-- 造出的订单：
--   1111…  buyer@test.local  访客   原会话 orig-device-token  → 换设备找回的目标
--   2222…  123@qq.com        访客   ——                        → 与注册订单同邮箱，验证只返回访客单
--   3333…  other@test.local  访客   other-device-token        → 越权测试对象
--   4444…  123@qq.com        注册   qq-user-token             → 凭证永远打不开
--
-- 前置条件：products 至少一条、users 至少一个 USER 角色账号。

BEGIN;

DELETE FROM card_keys   WHERE id::text LIKE 'c000%';
DELETE FROM order_items WHERE id::text LIKE 'a000%';
DELETE FROM orders      WHERE id::text LIKE '1111%' OR id::text LIKE '2222%'
                           OR id::text LIKE '3333%' OR id::text LIKE '4444%';

-- 注意：is_risk_flagged 在实体里是 primitive boolean，列却可空，
-- 漏填会让 Hibernate 抛 "Null value was assigned to a property of primitive type"（表现为接口 500）。
WITH p AS (SELECT id FROM products ORDER BY id LIMIT 1),
     u AS (SELECT id FROM users WHERE role = 'USER' ORDER BY created_at LIMIT 1)
INSERT INTO orders (id, created_at, updated_at, order_type, status, email, user_id,
                    session_token, total_amount, actual_amount, points_deducted,
                    is_risk_flagged, paid_at, delivered_at, expires_at,
                    payment_method, payment_currency)
SELECT * FROM (VALUES
  ('11111111-1111-4111-8111-111111111111'::uuid, now() - interval '2 hour', now(), 'DIRECT', 'DELIVERED',
   'buyer@test.local', NULL::uuid, 'orig-device-token', 50.00, 50.00, 0, false,
   now() - interval '2 hour', now() - interval '2 hour', now() + interval '1 day', 'epay', 'CNY'),
  ('22222222-2222-4222-8222-222222222222'::uuid, now() - interval '3 hour', now(), 'DIRECT', 'DELIVERED',
   '123@qq.com', NULL::uuid, 'qq-guest-token', 50.00, 50.00, 0, false,
   now() - interval '3 hour', now() - interval '3 hour', now() + interval '1 day', 'epay', 'CNY'),
  ('33333333-3333-4333-8333-333333333333'::uuid, now() - interval '4 hour', now(), 'DIRECT', 'DELIVERED',
   'other@test.local', NULL::uuid, 'other-device-token', 50.00, 50.00, 0, false,
   now() - interval '4 hour', now() - interval '4 hour', now() + interval '1 day', 'epay', 'CNY'),
  ('44444444-4444-4444-8444-444444444444'::uuid, now() - interval '5 hour', now(), 'DIRECT', 'DELIVERED',
   '123@qq.com', (SELECT id FROM u), 'qq-user-token', 50.00, 50.00, 0, false,
   now() - interval '5 hour', now() - interval '5 hour', now() + interval '1 day', 'epay', 'CNY')
) AS v;

INSERT INTO order_items (id, created_at, updated_at, order_id, product_id, product_title,
                         quantity, subtotal, unit_price)
SELECT ('a0000000-0000-4000-8000-00000000000' || n)::uuid, now(), now(),
       ('11111111-1111-4111-8111-11111111111' || n)::uuid,
       (SELECT id FROM products ORDER BY id LIMIT 1), '测试商品', 1, 50.00, 50.00
FROM (SELECT '1' AS n UNION SELECT '2' UNION SELECT '3' UNION SELECT '4') t;

INSERT INTO card_keys (id, created_at, updated_at, content, order_id, order_item_id,
                       product_id, status, sold_at)
SELECT ('c0000000-0000-4000-8000-00000000000' || n)::uuid, now(), now(),
       'TEST-CARD-KEY-ORDER-' || n,
       ('11111111-1111-4111-8111-11111111111' || n)::uuid,
       ('a0000000-0000-4000-8000-00000000000' || n)::uuid,
       (SELECT id FROM products ORDER BY id LIMIT 1), 'SOLD', now()
FROM (SELECT '1' AS n UNION SELECT '2' UNION SELECT '3' UNION SELECT '4') t;

COMMIT;

SELECT id, email, user_id IS NOT NULL AS registered, status, session_token
FROM orders
WHERE id::text LIKE '1111%' OR id::text LIKE '2222%'
   OR id::text LIKE '3333%' OR id::text LIKE '4444%'
ORDER BY id;
