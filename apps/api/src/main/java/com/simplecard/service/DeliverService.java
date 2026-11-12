package com.simplecard.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface DeliverService {

    /**
     * @param callerSessionToken 调用方请求头 X-Session-Token，用于访客订单归属判定
     */
    List<?> queryOrders(Map<String, Object> request, String callerSessionToken);

    List<?> deliverOrders(Map<String, Object> request, String callerSessionToken);

    String exportCardKeys(UUID orderId, String callerSessionToken);
}
