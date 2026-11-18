package com.simplecard;
import com.simplecard.constant.*;
import com.simplecard.entity.*;
import com.simplecard.repository.*;
import com.simplecard.service.InventoryLifecycleService;
import com.simplecard.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
@SpringBootTest
@AutoConfigureMockMvc
class OrderCancellationTest {
 @Autowired InventoryLifecycleService inventory;
 @Autowired OrderRepository orders;
 @Autowired OrderItemRepository items;
 @Autowired CardKeyRepository cards;
 @Autowired VerifiedPaymentEventRepository events;
 @Autowired MockMvc mvc;
 Order pending() {
  Order o=new Order(); o.setOrderType(OrderType.DIRECT);o.setActualAmount(BigDecimal.TEN);
  o.setTotalAmount(BigDecimal.TEN);o.setExpiresAt(LocalDateTime.now().plusMinutes(15));
  o.setSessionToken("cancel-test-session");return orders.saveAndFlush(o);
 }
 CardKey reserve(Order o) {
  UUID product=UUID.randomUUID();
  OrderItem i=new OrderItem();i.setOrderId(o.getId());i.setProductId(product);i.setProductTitle("test");
  i.setQuantity(1);i.setUnitPrice(BigDecimal.TEN);i.setSubtotal(BigDecimal.TEN);items.saveAndFlush(i);
  CardKey k=new CardKey();k.setProductId(product);k.setContent("test-only");cards.saveAndFlush(k);
  inventory.reserve(o.getId());return k;
 }
 @Test void cancellingImmediatelyReleasesStockAndIsIdempotent() {
  Order o=pending();CardKey k=reserve(o);
  inventory.cancel(o.getId());inventory.cancel(o.getId());
  assertThat(orders.findById(o.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
  CardKey released=cards.findById(k.getId()).orElseThrow();
  assertThat(released.getStatus()).isEqualTo(CardKeyStatus.AVAILABLE);
  assertThat(released.getOrderId()).isNull();assertThat(released.getOrderItemId()).isNull();
  Order next=pending();OrderItem i=new OrderItem();i.setOrderId(next.getId());i.setProductId(k.getProductId());
  i.setProductTitle("test");i.setQuantity(1);i.setUnitPrice(BigDecimal.TEN);i.setSubtotal(BigDecimal.TEN);items.saveAndFlush(i);
  inventory.reserve(next.getId());assertThat(cards.findById(k.getId()).orElseThrow().getOrderId()).isEqualTo(next.getId());
 }
 @Test void paidOrdersCannotBeCancelled() {
  Order o=pending();CardKey k=reserve(o);inventory.confirmPayment(o.getId(),null);
  assertThatThrownBy(()->inventory.cancel(o.getId())).isInstanceOf(BusinessException.class);
  assertThat(cards.findById(k.getId()).orElseThrow().getStatus()).isEqualTo(CardKeyStatus.SOLD);
 }
 @Test void verifiedPaymentInProgressBlocksCancellation() {
  Order o=pending();CardKey k=reserve(o);VerifiedPaymentEvent e=new VerifiedPaymentEvent();
  e.setEventKey(UUID.randomUUID().toString());e.setChannelCode("epay");e.setOrderId(o.getId());
  e.setNextAttemptAt(LocalDateTime.now());events.saveAndFlush(e);
  assertThatThrownBy(()->inventory.cancel(o.getId())).isInstanceOf(BusinessException.class);
  assertThat(cards.findById(k.getId()).orElseThrow().getStatus()).isEqualTo(CardKeyStatus.LOCKED);
 }
 @Test void paymentAfterCancellationIsPreservedForReviewWithoutTakingStock() {
  Order o=pending();CardKey k=reserve(o);inventory.cancel(o.getId());inventory.confirmPayment(o.getId(),null);
  Order reviewed=orders.findById(o.getId()).orElseThrow();assertThat(reviewed.getStatus()).isEqualTo(OrderStatus.PAID_REVIEW);
  assertThat(reviewed.getPaidAt()).isNotNull();assertThat(reviewed.getPaymentReviewReason()).contains("取消");
  assertThat(cards.findById(k.getId()).orElseThrow().getStatus()).isEqualTo(CardKeyStatus.AVAILABLE);
 }
 @Test void cancellationEndpointRequiresOriginalSession() throws Exception {
  Order o=pending();reserve(o);
  mvc.perform(post("/orders/"+o.getId()+"/cancel").header("X-Session-Token","another-session")).andExpect(status().isForbidden());
  assertThat(orders.findById(o.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
  mvc.perform(post("/orders/"+o.getId()+"/cancel").header("X-Session-Token","cancel-test-session")).andExpect(status().isOk());
  assertThat(orders.findById(o.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
 }
 @Test void cancellationRacingWithPaymentNeverLosesPaymentOrSellsReleasedStock() throws Exception {
  Order o=pending();CardKey k=reserve(o);
  var start=new java.util.concurrent.CountDownLatch(1);
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
  try {
   var cancel=pool.submit(()->{start.await();try { inventory.cancel(o.getId()); } catch(BusinessException paid) { } return null;});
   var payment=pool.submit(()->{start.await();inventory.confirmPayment(o.getId(),null);return null;});
   start.countDown();cancel.get(20,java.util.concurrent.TimeUnit.SECONDS);payment.get(20,java.util.concurrent.TimeUnit.SECONDS);
   Order result=orders.findById(o.getId()).orElseThrow();assertThat(result.getPaidAt()).isNotNull();
   assertThat(result.getStatus()).isIn(OrderStatus.DELIVERED,OrderStatus.PAID_REVIEW);
   assertThat(cards.findById(k.getId()).orElseThrow().getStatus()).isEqualTo(
     result.getStatus()==OrderStatus.DELIVERED?CardKeyStatus.SOLD:CardKeyStatus.AVAILABLE);
  } finally {pool.shutdownNow();}
 }
}
