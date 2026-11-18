package com.simplecard.service;
import com.simplecard.entity.*;
import com.simplecard.repository.*;
import com.simplecard.service.impl.*;
import com.simplecard.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
@ExtendWith(MockitoExtension.class)
class PaymentRefreshTest {
 @Mock OrderRepository orders;
 @Mock VerifiedPaymentEventRepository events;
 @Mock PaymentChannelRepository channels;
 @Mock PaymentServiceImpl payments;
 @Mock EpayService epay;
 @Mock PaymentInboxService inbox;
 @InjectMocks OrderServiceImpl service;
 Order order;
 @BeforeEach void setup(){
  order=new Order();order.setId(UUID.randomUUID());order.setPaymentMethod("alipay");
  order.setActualAmount(new BigDecimal("0.01"));order.setEpayTradeNo("original-trade");
  order.setExpiresAt(LocalDateTime.now().plusMinutes(5));
  when(orders.findById(order.getId())).thenReturn(Optional.of(order));
  when(channels.findByChannelCodeAndIsDeleted("alipay",0)).thenReturn(Optional.of(new PaymentChannel()));
 }
 void gateway(EpayService.OrderQueryResult result){when(epay.queryOrder(isNull(),eq(order.getId().toString()))).thenReturn(result);}
 @Test void confirmedPaymentEntersDurableInbox(){
  gateway(new EpayService.OrderQueryResult("1","0.01","original-trade"));service.refreshOrderStatus(order.getId());
  verify(inbox).accept("epay_original-trade","epay",order.getId(),null);
 }
 @Test void unpaidNeverConfirms(){gateway(new EpayService.OrderQueryResult("0","0.01","original-trade"));service.refreshOrderStatus(order.getId());verifyNoInteractions(inbox);}
 @Test void wrongAmountNeverConfirms(){gateway(new EpayService.OrderQueryResult("1","1.00","original-trade"));assertThrows(BusinessException.class,()->service.refreshOrderStatus(order.getId()));verifyNoInteractions(inbox);}
 @Test void wrongTradeNeverConfirms(){gateway(new EpayService.OrderQueryResult("1","0.01","other"));assertThrows(BusinessException.class,()->service.refreshOrderStatus(order.getId()));verifyNoInteractions(inbox);}
 @Test void unavailableQueryIsAnErrorNotFakeSuccess(){gateway(null);assertThrows(BusinessException.class,()->service.refreshOrderStatus(order.getId()));verifyNoInteractions(inbox);}
}
