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
 @Mock PaymentAttemptRepository attempts;
 @Mock PaymentAttemptService attemptService;
 @InjectMocks PaymentReconciliationService service;
 Order order;
 @BeforeEach void setup(){
  order=new Order();order.setId(UUID.randomUUID());order.setPaymentMethod("alipay");
  order.setActualAmount(new BigDecimal("0.01"));order.setEpayTradeNo("original-trade");
  order.setExpiresAt(LocalDateTime.now().plusMinutes(5));
  PaymentChannel channel = new PaymentChannel();
  when(channels.findByChannelCodeAndIsDeleted("alipay",0)).thenReturn(Optional.of(channel));
  when(payments.buildChannelConfig(channel)).thenReturn(new EpayService.ChannelConfig(
    "merchant","test-only","https://gateway.invalid","notify","return"));
 }
 void gateway(EpayService.OrderQueryResult result){when(epay.queryOrder(any(),eq(order.getId().toString()))).thenReturn(result);}
 @Test void confirmedPaymentEntersDurableInbox(){
  gateway(new EpayService.OrderQueryResult("1","0.01","original-trade"));service.refresh(order);
  verify(inbox).accept("epay_original-trade","epay",order.getId(),null);
 }
 @Test void unpaidNeverConfirms(){gateway(new EpayService.OrderQueryResult("0","0.01","original-trade"));service.refresh(order);verifyNoInteractions(inbox);}
 @Test void wrongAmountNeverConfirms(){gateway(new EpayService.OrderQueryResult("1","1.00","original-trade"));assertThrows(BusinessException.class,()->service.refresh(order));verifyNoInteractions(inbox);}
 @Test void wrongTradeNeverConfirms(){gateway(new EpayService.OrderQueryResult("1","0.01","other"));assertThrows(BusinessException.class,()->service.refresh(order));verifyNoInteractions(inbox);}
 @Test void unavailableQueryIsAnErrorNotFakeSuccess(){gateway(null);assertThrows(BusinessException.class,()->service.refresh(order));verifyNoInteractions(inbox);}

 @Test void schedulerRestoresPaidExpiredOrderWithoutWebhookAndFlagsReview() {
  order.setExpiresAt(LocalDateTime.now().minusMinutes(1));
  PaymentAttempt attempt = dueAttempt();
  gateway(new EpayService.OrderQueryResult("1","0.01","original-trade"));

  service.checkDuePayments();

  verify(inbox).accept(eq("epay_original-trade"), eq("epay"), eq(order.getId()), isNull(), contains("过期"));
  verify(attempts).completeCheck(eq(attempt.getId()), any(), isNull(), eq(PaymentAttempt.State.VERIFIED), eq(0), eq("VERIFIED"));
 }

 @Test void paidAmountMismatchOpensAlertWithoutConfirmingTheOrder() {
  PaymentAttempt attempt = dueAttempt();
  gateway(new EpayService.OrderQueryResult("1", "1.00", "original-trade"));

  service.checkDuePayments();

  verifyNoInteractions(inbox);
  verify(attempts).completeCheck(eq(attempt.getId()), any(), any(), eq(PaymentAttempt.State.READY), eq(1),
          eq(PaymentAttempt.PAID_UNVERIFIED));
 }

 @Test void temporaryGatewayFailureKeepsAnExistingPaidDiscrepancyVisible() {
  PaymentAttempt attempt = dueAttempt();
  attempt.setLastCheckResult(PaymentAttempt.PAID_UNVERIFIED);
  gateway(null);

  service.checkDuePayments();

  verifyNoInteractions(inbox);
  verify(attempts).completeCheck(eq(attempt.getId()), any(), any(), eq(PaymentAttempt.State.READY), eq(1),
          eq(PaymentAttempt.PAID_UNVERIFIED));
 }

 private PaymentAttempt dueAttempt() {
  PaymentAttempt attempt = new PaymentAttempt();
  attempt.setId(UUID.randomUUID());
  attempt.setOrderId(order.getId());
  attempt.setState(PaymentAttempt.State.READY);
  attempt.setGatewayTradeId("original-trade");
  attempt.setAmount(order.getActualAmount());
  attempt.setExpiresAt(order.getExpiresAt());
  attempt.setProvider("epay");
  attempt.setGatewayUrl("https://gateway.invalid");
  attempt.setMerchantId("merchant");
  when(attempts.findDue(any(), any())).thenReturn(List.of(attempt.getId()));
  when(attempts.claim(eq(attempt.getId()), any(), any())).thenReturn(1);
  when(attempts.findById(attempt.getId())).thenReturn(Optional.of(attempt));
  when(orders.findById(order.getId())).thenReturn(Optional.of(order));
  org.springframework.test.util.ReflectionTestUtils.setField(service, "enabled", true);
  return attempt;
 }
}
