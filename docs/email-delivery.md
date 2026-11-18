# 邮件送达方案

面向「要让大量用户都能收到验证码」这个目标。本文说明为什么换个 SMTP 地址不够、生产该怎么选、代码里已经做了哪些保障、上线前必须核对什么。

---

## 一、先分清两件事

| 角色 | 是谁 | 需要做什么 |
|---|---|---|
| **发件人** | 站点自己（你） | 配一次 SMTP 账号，永久生效 |
| **收件人** | 下单的用户 | **什么都不用做** |

「QQ 邮箱要开 IMAP/SMTP 并生成授权码」这件事，是**你**去 QQ 邮箱后台做的，不是用户做。用户的 QQ 邮箱默认就能收信。

所以要解决的问题不是「怎么让用户配置」，而是「你用什么账号、从什么域名发信」。

---

## 二、为什么个人邮箱不能上线

| 问题 | 实际后果 |
|---|---|
| 日发信上限 | QQ/163 个人邮箱每天几百封即拒，几百个用户当天撞墙 |
| 无自有域名 | `From` 是 `xxx@qq.com`，与你站点无关，SPF/DKIM 无法自证身份 |
| 共享 IP 信誉 | 同 IP 上其他人的滥发记录会算到你头上，大批进垃圾箱 |
| 反滥用风控 | 非人工高频发信会被封号，业务当天中断 |
| 退信不可控 | 硬退累积，服务商会连带限制你的发信 |

**换任何一家服务商都一样，瓶颈在「没有自己的发信域名」。** 这是唯一硬门槛。

---

## 三、域名与 DNS（必做）

必须有一个你能改 DNS 的域名。三条记录配一次，永久生效：

```
; SPF —— 授权谁可以代表你的域名发信
@   TXT   "v=spf1 include:<服务商给出的域名> ~all"

; DKIM —— 服务商给的选择器与公钥，签进邮件头
<selector>._domainkey   TXT   "v=DKIM1; k=rsa; p=<公钥>"

; DMARC —— 告诉收件方「SPF/DKIM 失败时怎么处理」，并提供报表地址
_dmarc   TXT   "v=DMARC1; p=none; rua=mailto:dmarc@your-domain.com; adkim=r; aspf=r"
```

**DMARC 的 `p` 要渐进**：先 `p=none` 只观察两周，确认报表里自身流量都通过后再升 `quarantine`，最后视情况升 `reject`。直接上 `reject` 会把还没配好的正常邮件一起打掉。

**建议的地址规划：**

| 用途 | 地址 | 说明 |
|---|---|---|
| 验证码 | `verify@mail.your-domain.com` | 单独子域，信誉独立，出问题不牵连主域 |
| 发货通知 | `noreply@mail.your-domain.com` | 与验证码分离，一类被投诉不拖累另一类 |
| 退信收集 | `bounce@mail.your-domain.com` | 写进 `Return-Path`，与 `From` 分离 |

用子域（`mail.`）而非主域发信，是故意把风险隔离在外：万一发信信誉受损，你主站的域名解析不受影响。

---

## 四、服务商选型：国内海外双通道

你提到用户国内海外都有。**单一通道服务不了两边**：国内服务商发 Gmail 容易进垃圾箱，海外服务商发 QQ 延迟高且常被判可疑。

| 收件域 | 通道 | 候选 |
|---|---|---|
| QQ / 163 / 126 / 新浪 / 搜狐 / 企业邮箱 | 国内 | 腾讯云 SES、阿里云邮件推送 |
| Gmail / Outlook / Yahoo / iCloud / 其余 | 海外 | Amazon SES、Resend、SendGrid |

**国内服务商的额外要求：** 域名需备案，发信模板通常需报备审核（1~3 个工作日）。这一步排在上线计划最前面。

**海外服务商的开通门槛：** Amazon SES 新账号在沙箱里只能发给已验证地址、每日 200 封，需提交申请解除；Resend/SendGrid 开箱即用但需信用卡。

**价格量级**（以官网为准）：SES 约 $0.10/千封；国内两家按量计费，单价在每千封个位数人民币，均有免费额度。

---

## 五、代码里已经做了什么

改造前的实现只有一个 `mailSender.send()`，配了 `spring.mail.*` 就发。问题不在"能不能发"，而在**发了没到、到了不知道为什么没到**。现在补齐五处：

### 1. 按收件域路由（`MailSenderRouter`）

```
buyer@qq.com    → domestic 通道
buyer@gmail.com → overseas 通道
buyer@某公司.cn  → 兜底通道
```

匹配是**后缀匹配**，所以 `vip.qq.com`、`mail.163.com` 都能命中。`domains` 为空的通道自动成为兜底通道，承接域名列表覆盖不到的企业域与新顶级域——这类地址永远列不全，没有兜底就会被静默丢弃。

### 2. 超时（硬要求）

`JavaMailSenderImpl` 默认**没有超时**。SMTP 服务端不响应时连接会无限挂起，而所有发信都跑在 `@Async` 线程里——几个卡死就能占满线程池，连发货通知一起堵住。

现在每个通道都带三个超时：连接 5s、读 10s、写 10s。

### 3. 失败分类（`MailFailureClassifier`）

同一个 Spring `MailSendException` 可能是「连不上」也可能是「收件人不存在」，处理方式完全相反：

| 分类 | 典型返回码 | 重试 | 换通道 | 告警 |
|---|---|---|---|---|
| `RECIPIENT_REJECTED` | 550/551/552/553 | ✗ | ✗ | ✓ |
| `CONTENT_REJECTED` | 554 | ✗ | ✗ | ✓ |
| `AUTH_FAILED` | 530/534/535 | ✗ | ✓ | ✓ |
| `RATE_LIMITED` | 421/450/451/452 | ✓ | ✓ | ✗ |
| `CONNECT_FAILED` | 网络异常 | ✓ | ✓ | ✗ |

**为什么收件人拒绝绝不能重试：** 每重试一次就记一次硬退，硬退率超过服务商阈值（SES 5%，多数 2%~5%）会封掉整个发件域，那时连正常用户都收不到。

### 4. 重试与跨通道降级（`MailDispatcher`）

- 同通道最多 3 次，退避 200ms / 400ms，上限 1s。总窗口约 0.6s，远小于验证码有效期
- 通道级故障（认证/连接/限流）换另一条通道再试一次
- **不会重复投递**：只有发生在 SMTP 服务器接收邮件*之前*的失败才换通道，收件人层面的拒绝不换

### 5. 硬退抑制（`MailDeliveryStats`）

识别到收件人永久拒绝后，该地址 30 天内不再尝试发送。

选 30 天而非永久，是因为用户可能重新启用了邮箱，或当初是服务商误判；永久封禁会让这些人永久失去找回订单的能力，且没有自救途径。

抑制表上限 10 万条，满了拒绝新增并告警——到这个量级说明发信源本身有问题，该修的是源头。

### 6. 线程池隔离（`MailExecutorConfig`）

超时解决了「一个连接永久挂起」，但没解决「一堆连接慢慢挂起」。改造前全站 `@Async` 共用 Spring Boot 默认的 `applicationTaskExecutor`：8 线程、**无界队列**。单封最坏耗时 = 5s 连接 + 10s 读 + 10s 写 ≈ 25s，而验证码接口是可以被刷的 —— 结果是**验证码洪峰把发货通知堵在队尾**，用户付了钱拿不到卡密。

现在两个池各管一类：

| 池 | 线程 | 队列 | 打满时 |
|---|---|---|---|
| `mailVerificationExecutor` | 4 | 500 | 丢弃 + WARN（用户重发即可） |
| `mailNotificationExecutor` | 4 | 2000 | 丢弃 + ERROR（需人工补发） |

两个设计取舍值得记住：

- **有界队列**：无界队列等于把内存当缓冲，洪峰时队列只会越长越长，最后不是 OOM 就是延迟高到验证码已过期。
- **溢出丢弃而非 `CallerRuns`**：发信调用点都在下单 / 支付回调 / 查单线程上，`CallerRuns` 会让业务线程自己去跑 SMTP 握手（最坏 25s），把发信拥塞变成接口超时；丢弃的代价是丢一封信。

被丢弃的量不会静默：计数在 `MailQueueGuard`，通过下面的运维接口可见。

---

## 六、配置示例

**单通道（兼容旧部署，不配 `channels` 即可）：**

```bash
MAIL_ENABLED=true
MAIL_HOST=smtp.qcloudmail.com
MAIL_PORT=465
MAIL_USERNAME=verify@mail.your-domain.com
MAIL_PASSWORD=<授权码>
MAIL_FROM_VERIFY=verify@mail.your-domain.com
MAIL_FROM_ORDER=noreply@mail.your-domain.com
MAIL_FROM_BOUNCE=bounce@mail.your-domain.com
```

**双通道（推荐）：**

```yaml
mail:
  channels:
    domestic:
      host: smtp.qcloudmail.com
      port: 465
      username: verify@mail.your-domain.com
      password: ${MAIL_DOMESTIC_PASSWORD}
      domains: [qq.com, foxmail.com, 163.com, 126.com, sina.com, sohu.com]
    overseas:
      host: email-smtp.us-east-1.amazonaws.com
      port: 587
      ssl: false
      starttls: true
      username: ${MAIL_OVERSEAS_USERNAME}
      password: ${MAIL_OVERSEAS_PASSWORD}
      domains: []          # 空 = 兜底
  default-channel: overseas
```

**本地测试（Mailpit，不需要真实邮箱）：**

```bash
docker run -d --name simplecard-mailpit -p 127.0.0.1:1025:1025 -p 127.0.0.1:8025:8025 axllent/mailpit:v1.21
```

```bash
MAIL_ENABLED=true MAIL_HOST=127.0.0.1 MAIL_PORT=1025 MAIL_SSL=false MAIL_SMTP_AUTH=false
```

Mailpit 是明文端口，必须关掉 SSL；否则只会报 `Could not connect to SMTP host`，完全不提 SSL，很容易误判成防火墙。

---

## 七、上线清单

- [ ] 域名 DNS 的 SPF / DKIM / DMARC 三条记录已生效（`dig TXT` 验证）
- [ ] 国内通道域名已备案，发信模板审核通过
- [ ] 海外通道已解除沙箱（SES）或有可用额度
- [ ] 向 QQ / 163 / Gmail / Outlook 各发一封真实邮件，全部在收件箱（不是垃圾箱）
- [ ] `mail-tester.com` 打分 ≥ 9/10，SPF/DKIM/DMARC 三项全过
- [ ] 拔掉 SMTP 密码模拟认证失败，确认日志出现 `AUTH_FAILED` 告警而不是静默失败
- [ ] 发往不存在的地址，确认只尝试一次且该地址被加入抑制表
- [ ] 灰度：先开给自己，跑一周看失败率再全量

---

## 八、监控

发信结果已接入 micrometer（`MeterRegistry`）：

| 指标 | 标签 | 用途 |
|---|---|---|
| `mail.outcome` | `channel`、`result`、`reason` | 成功率与失败原因分布 |
| `mail.sent` | `channel`、`purpose` | 按通道与用途的发信量 |

**注意：项目里只有 `SimpleMeterRegistry`（内存实现，没有 Prometheus 抓取端点）。** 计数器一直在累加，但没有出口就看不到。所以另开了一个运维接口：

```bash
# 需要 ADMIN 令牌
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8083/api/admin/mail/delivery-stats
```

返回四块内容：

| 字段 | 含义 | 异常时的读法 |
|---|---|---|
| `delivery` | 已发/失败/成功率/失败分类明细/抑制地址数/`alert_required` | 成功率 < 98% 看 `failure_breakdown` |
| `queue` | 因线程池打满被丢弃的封数（按用途分开） | 大于 0 说明池子不够或验证码被刷 |
| `pools` | 每个池的活跃线程、排队数、队列容量 | `queued` 接近容量 = 即将开始丢弃 |
| `channels` | 通道名、host、是否 `configured`、收件域、三个超时值 | `configured=false` = 口令是占位符 |

`MailDeliveryStats#snapshot()` 仍是同一份数据的代码入口，接口只是把它暴露出来。

**该盯的阈值：**

- 成功率 < 98% → 查 `failure_breakdown`
- `RECIPIENT_REJECTED` 占比 > 2% → 检查是不是在给一批无效地址发信
- `alert_required` 在涨 → 认证失败或批量硬退，这是会全线中断的故障
- `queue.notification_rejected` > 0 → 已经丢了发货通知，按日志里的订单线索人工补发

---

## 九、已知限制

1. **退信（bounce）统计只有同步口径。** 现在的 `RECIPIENT_REJECTED` 只覆盖「SMTP 对话阶段就被拒」的情况；真正的异步退信（投递后被退回）需要服务商 webhook 或 IMAP 拉取 DSN，当前未实现。因此**服务商报表里的软/硬退率会高于本系统看到的数字**，上线后仍需定期对照服务商后台。抑制表的 30 天封禁也只对同步拒收生效。

2. **验证码只发给「该邮箱确实有访客订单」的地址。** 这是防枚举设计：否则任何人输入任意邮箱都能探测"这人在这买过东西吗"，或把平台当免费邮件轰炸工具。因此没下过单的邮箱收不到信是预期行为，不是故障。

3. **注册账号的订单不在覆盖范围。** 按既定边界，注册用户必须登录查看；用邮箱查单时只会返回该邮箱下的访客订单，并提示「另有 N 笔请登录查看」。

4. **验证码与凭证存在内存。** 单实例部署下没问题，重启后重收一次即可。**上多实例必须换 Redis**，否则并发唯一性失效。

5. **硬退抑制表也是单机内存。** 多实例部署时每个节点各记一份，重启即清空——同一个失效地址可能被邻居节点再发一次，服务商口径的硬退率会偏高。抑制表本身不难换 Redis，但与上一条一起做更划算：都要动存储，分成两次改是浪费。
