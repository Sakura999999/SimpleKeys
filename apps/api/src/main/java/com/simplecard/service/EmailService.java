package com.simplecard.service;

import java.util.UUID;

public interface EmailService {

    void sendDeliveryEmail(UUID orderId);
}
