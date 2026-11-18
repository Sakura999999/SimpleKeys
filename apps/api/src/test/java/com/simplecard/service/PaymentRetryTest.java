package com.simplecard.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplecard.entity.*;
import com.simplecard.repository.*;
import com.simplecard.service.impl.PaymentServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
class PaymentRetryTest {
 @Mock PaymentChannelRepository channels;
 @Mock OrderRepository orders;
 @Mock InventoryLifecycleService inventory;
 @Mock OrderItemRepository items;
 @Mock EpayService epay;
 @Mock BepusdtService usdt;
 @Mock ObjectMapper mapper;
 @Mock OrderAccessGuard access;
 @Mock ApiTrafficLimiter limiter;
 @InjectMocks PaymentServiceImpl service;
 @Test void retryReusesOriginalPaymentWithoutDuplicateSubmission() {
  UUID id=UUID.randomUUID(); Order order=new Order(); order.setId(id);
  order.setPaymentMethod("alipay"); order.setActualAmount(new BigDecimal("0.01"));
  order.setExpiresAt(LocalDateTime.now().plusMinutes(10));
  order.setUpdatedAt(LocalDateTime.now().minusMinutes(1));
  order.setQrcodeUrl("https://pay.example.test/original"); order.setEpayTradeNo("original");
  when(orders.findById(id)).thenReturn(Optional.of(order));
  when(channels.findByChannelCodeAndIsDeleted("alipay",0)).thenReturn(Optional.of(new PaymentChannel()));
  var result=service.repay(id,"pc","session");
  assertEquals("https://pay.example.test/original",result.get("qrcode_url"));
  assertEquals("original",order.getEpayTradeNo());
  verify(access).assertSensitiveAccess(order,"session");
  verify(inventory,never()).clearPaymentDetails(id);
  verifyNoInteractions(epay,usdt);
 }
}
