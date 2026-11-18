package com.simplecard.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface DeliverService {

    /**
     * @param callerSessionToken 调用方请求头 X-Session-Token，用于访客订单归属判定
     * @param lookupToken        调用方请求头 X-Order-Lookup-Token（邮箱验证凭证），可为空。
     *                           为空时行为与未引入该凭证前完全一致；非空时邮箱查询返回
     *                           「该邮箱名下的访客订单」，且请求里的邮箱必须与凭证邮箱一致。
     */
    List<?> queryOrders(Map<String, Object> request, String callerSessionToken, String lookupToken);

    /**
     * @param lookupToken 邮箱验证凭证，可为空。非空时会额外校验订单邮箱与凭证邮箱一致。
     */
    List<?> deliverOrders(Map<String, Object> request, String callerSessionToken, String lookupToken);

    /**
     * @param lookupToken 邮箱验证凭证，可为空。非空时要求订单属于凭证邮箱。
     */
    String exportCardKeys(UUID orderId, String callerSessionToken, String lookupToken);

    /**
     * 未携带邮箱验证凭证时走这里：语义与引入凭证前完全一致。
     * 保留重载是为了让「不需要凭证」的调用方（后台、测试）不必写 {@code null}。
     */
    default List<?> queryOrders(Map<String, Object> request, String callerSessionToken) {
        return queryOrders(request, callerSessionToken, null);
    }

    default List<?> deliverOrders(Map<String, Object> request, String callerSessionToken) {
        return deliverOrders(request, callerSessionToken, null);
    }

    default String exportCardKeys(UUID orderId, String callerSessionToken) {
        return exportCardKeys(orderId, callerSessionToken, null);
    }
}
