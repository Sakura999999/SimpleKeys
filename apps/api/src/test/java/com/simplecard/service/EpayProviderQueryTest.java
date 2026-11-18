package com.simplecard.service;
import com.simplecard.service.impl.EpayServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.junit.jupiter.api.Assertions.*;
class EpayProviderQueryTest {
 @Test void providerUsesActualLookupTypeForMerchantOrder(){
  RestTemplate rest=new RestTemplate();var server=MockRestServiceServer.bindTo(rest).build();
  server.expect(requestTo("https://www.ezfpy.cn/api/findorder")).andExpect(method(HttpMethod.POST))
   .andExpect(content().string("order_no=order-id&type=2"))
   .andRespond(withSuccess("{\"code\":200,\"data\":{\"status\":1,\"money\":\"0.01\",\"trade_no\":\"trade\",\"out_trade_no\":\"order-id\"}}",MediaType.APPLICATION_JSON));
  var service=new EpayServiceImpl(rest,new ObjectMapper());
  var result=service.queryOrder(new EpayService.ChannelConfig("pid","secret","https://www.ezfpy.cn/","notify","return"),"order-id");
  assertNotNull(result);assertEquals("trade",result.tradeNo());assertEquals("1",result.tradeStatus());server.verify();
 }
 @Test void providerRejectionDoesNotMeanPaid(){
  RestTemplate rest=new RestTemplate();var server=MockRestServiceServer.bindTo(rest).build();
  server.expect(requestTo("https://www.ezfpy.cn/api/findorder")).andRespond(withSuccess("{\"code\":201,\"msg\":\"invalid order\"}",MediaType.APPLICATION_JSON));
  var result=new EpayServiceImpl(rest,new ObjectMapper()).queryOrder(new EpayService.ChannelConfig("pid","secret","https://www.ezfpy.cn/","notify","return"),"order-id");
  assertNull(result);server.verify();
 }
}
