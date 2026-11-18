package com.simplecard.service;

import com.simplecard.constant.ErrorCode;
import com.simplecard.constant.UserRole;
import com.simplecard.context.RequestContext;
import com.simplecard.entity.Order;
import com.simplecard.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 订单访问控制 — 集中定义"谁可以访问哪个订单"，供查询/发货/导出/重新支付共用。
 *
 * <p>授权模型分三层：
 * <ul>
 *   <li><b>注册用户订单</b>（user_id 非空）：必须由该用户本人或管理员访问。
 *       订单号泄露（浏览器历史、分享链接、日志）不足以取得卡密。
 *       <b>邮箱验证凭证在此分支完全无效</b>——见 {@link #assertAccess(Order, String, String)}。</li>
 *   <li><b>访客订单</b>（user_id 为空）：以订单号（UUIDv4，122 位随机熵）作为能力凭证。
 *       访客无账号可绑定，且支付网关回跳依赖 ?orderId= 参数，故订单号即凭证。</li>
 *   <li><b>邮箱验证凭证</b>（{@link OrderLookupGrantStore}）：证明"拥有该邮箱"，
 *       只对<i>该邮箱名下的访客订单</i>有效。它不能打开注册账号的订单，也不能打开
 *       其他邮箱的订单。</li>
 * </ul>
 *
 * <p><b>邮箱本身不是身份凭证。</b>邮箱地址是公开信息且熵极低，任何"仅凭邮箱即可取得订单"
 * 的路径都等于把卡密公开。因此邮箱只能作为<i>过滤条件</i>（见 {@link #ownsOrder}），
 * 只有"能读取该邮箱收到的验证码"才构成授权依据。
 *
 * <p><b>两个方法的分工不能混：</b>
 * {@link #assertAccess} 用于查看/领取/导出 —— 访客订单凭订单号即可；
 * {@link #assertSensitiveAccess} 用于提交 TXID / 重新支付 —— 这些动作会产生外部副作用
 * （向支付网关发起请求、留下可被他人认领的链上交易），必须证明"是原下单会话"。
 * <b>邮箱验证凭证不会、也绝不应该放宽 assertSensitiveAccess。</b>
 */
@Component
@RequiredArgsConstructor
public class OrderAccessGuard {

    private final OrderLookupGrantStore grantStore;

    /**
     * 外部副作用动作的归属校验。刻意<b>只接受</b>会话凭证：
     * 邮箱验证凭证不在此列，签名里也不给它入口。
     */
    public void assertSensitiveAccess(Order order, String callerSessionToken) {
        if (!ownsOrder(order, callerSessionToken)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "请使用原下单账号或浏览器操作，旧订单可联系管理员处理", HttpStatus.FORBIDDEN);
        }
    }

    /** 当前请求是否由管理员发起 */
    public boolean isAdmin() {
        return UserRole.ADMIN.name().equals(RequestContext.getRole());
    }

    /**
     * 调用方能否<b>证明</b>订单归属。用于邮箱查询结果过滤——只有能证明归属的订单才返回，
     * 否则邮箱查询会变成订单号枚举入口。
     *
     * <p>注意：本方法<b>不</b>认可邮箱验证凭证。凭证带来的可见范围扩展由
     * {@link DeliverService#queryOrders} 单独处理（用的是"该邮箱的访客订单"查询，
     * 而不是本方法的逐条过滤）。
     *
     * @param callerSessionToken 调用方请求头 X-Session-Token
     */
    public boolean ownsOrder(Order order, String callerSessionToken) {
        if (isAdmin()) return true;

        if (order.getUserId() != null) {
            UUID currentUserId = RequestContext.getUserId();
            return currentUserId != null && currentUserId.equals(order.getUserId());
        }

        // 访客订单：同一浏览器会话视为本人
        String orderToken = order.getSessionToken();
        return orderToken != null && !orderToken.isBlank()
                && orderToken.equals(callerSessionToken);
    }

    /** 不带邮箱验证凭证的访问校验，等价于 {@code assertAccess(order, token, null)} */
    public void assertAccess(Order order, String callerSessionToken) {
        assertAccess(order, callerSessionToken, null);
    }

    /**
     * 按订单号访问时的授权校验。
     *
     * <p>规则按订单归属分两种：
     * <ol>
     *   <li><b>注册订单</b>：只有本人或管理员。携带了邮箱验证凭证也一样拒绝——
     *       这是"注册订单继续要求登录本人账号"这条边界的唯一落点，改动前请先看
     *       {@code EmailLookupIntegrationTest} 里对应的断言。</li>
     *   <li><b>访客订单</b>：订单号即能力凭证（既有模型，本次不改）。
     *       若请求携带了邮箱验证凭证，额外要求订单邮箱与凭证邮箱一致；
     *       否则凭证一旦被跨邮箱复用（例如把别人的订单号塞进请求），
     *       就会绕过"凭证只覆盖单个邮箱"的设计。</li>
     * </ol>
     *
     * @param lookupToken 调用方请求头 X-Order-Lookup-Token，可为空
     * @throws BusinessException 403，当订单归属不允许本次访问时
     */
    public void assertAccess(Order order, String callerSessionToken, String lookupToken) {
        if (isAdmin()) return;

        if (order.getUserId() != null) {
            UUID currentUserId = RequestContext.getUserId();
            if (currentUserId == null || !currentUserId.equals(order.getUserId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        "该订单属于注册账号，请登录后查看", HttpStatus.FORBIDDEN);
            }
            return;
        }

        // 访客订单：订单号即凭证（既有模型）
        Optional<OrderLookupGrantStore.Grant> grant = resolveGrant(lookupToken);
        if (grant.isPresent() && !emailMatches(order, grant.get())) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "该订单不属于已验证的邮箱，请重新验证", HttpStatus.FORBIDDEN);
        }
    }

    /** 解析请求携带的邮箱验证凭证；无效/过期/未携带均返回 empty */
    public Optional<OrderLookupGrantStore.Grant> resolveGrant(String lookupToken) {
        return grantStore.resolve(lookupToken);
    }

    /** 订单邮箱与凭证邮箱是否一致（忽略大小写与首尾空白，统一走 {@link com.simplecard.common.Emails}） */
    public static boolean emailMatches(Order order, OrderLookupGrantStore.Grant grant) {
        if (order == null || grant == null) return false;
        return com.simplecard.common.Emails.sameAddress(order.getEmail(), grant.email());
    }
}
