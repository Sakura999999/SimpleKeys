package com.simplecard.service;

import com.simplecard.constant.ErrorCode;
import com.simplecard.constant.UserRole;
import com.simplecard.context.RequestContext;
import com.simplecard.entity.Order;
import com.simplecard.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 订单访问控制 — 集中定义"谁可以访问哪个订单"，供查询/发货/导出/重新支付共用。
 *
 * <p>授权模型分两层：
 * <ul>
 *   <li><b>注册用户订单</b>（user_id 非空）：必须由该用户本人或管理员访问。
 *       订单号泄露（浏览器历史、分享链接、日志）不足以取得卡密。</li>
 *   <li><b>访客订单</b>（user_id 为空）：以订单号（UUIDv4，122 位随机熵）作为能力凭证。
 *       访客无账号可绑定，且支付网关回跳依赖 ?orderId= 参数，故订单号即凭证。</li>
 * </ul>
 *
 * <p><b>邮箱不是身份凭证。</b>邮箱地址是公开信息且熵极低，任何"仅凭邮箱即可取得订单"
 * 的路径都等于把卡密公开。因此邮箱只能作为<i>过滤条件</i>（见 {@link #ownsOrder}），
 * 不能作为<i>授权依据</i>。跨设备的邮箱查单需要邮箱验证码，尚未实现。
 */
@Component
public class OrderAccessGuard {

    /** 当前请求是否由管理员发起 */
    public boolean isAdmin() {
        return UserRole.ADMIN.name().equals(RequestContext.getRole());
    }

    /**
     * 调用方能否<b>证明</b>订单归属。用于邮箱查询结果过滤——只有能证明归属的订单才返回，
     * 否则邮箱查询会变成订单号枚举入口。
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

    /**
     * 按订单号访问时的授权校验。注册用户的订单必须由本人或管理员访问；
     * 访客订单以订单号作为能力凭证，放行。
     *
     * @throws BusinessException 403，当订单属于其他注册用户时
     */
    public void assertAccess(Order order, String callerSessionToken) {
        if (isAdmin()) return;

        if (order.getUserId() != null) {
            UUID currentUserId = RequestContext.getUserId();
            if (currentUserId == null || !currentUserId.equals(order.getUserId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        "该订单属于注册账号，请登录后查看", HttpStatus.FORBIDDEN);
            }
            return;
        }

        // 访客订单：订单号即凭证。会话匹配时同样放行（此处无需额外判断）。
    }
}
