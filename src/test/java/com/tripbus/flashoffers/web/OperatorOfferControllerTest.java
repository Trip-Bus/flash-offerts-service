package com.tripbus.flashoffers.web;

import com.tripbus.flashoffers.service.OfferService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class OperatorOfferControllerTest {
 @Test void listasConEmpresaOperadora(){
  OfferService service=mock(OfferService.class); UUID company=UUID.randomUUID();
  Jwt jwt=Jwt.withTokenValue("t").header("alg","none").subject(UUID.randomUUID().toString()).claim("companyId",company.toString()).build();
  var auth=new JwtAuthenticationToken(jwt,List.of(new SimpleGrantedAuthority("ROLE_OPERADOR")));
  var controller=new OperatorOfferController(service);
  controller.list("ACTIVA",null,null,0,20,auth);
  verify(service).operatorOffers("ACTIVA",null,null,0,20,company,false);
 }
 @Test void adminConsultaGlobal(){
  OfferService service=mock(OfferService.class);
  Jwt jwt=Jwt.withTokenValue("t").header("alg","none").subject(UUID.randomUUID().toString()).build();
  var auth=new JwtAuthenticationToken(jwt,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
  new OperatorOfferController(service).list(null,null,null,-1,1000,auth);
  verify(service).operatorOffers(null,null,null,0,100,null,true);
 }
 @Test void metricasDelOperadorConSuEmpresa(){
  OfferService service=mock(OfferService.class); UUID company=UUID.randomUUID();
  Jwt jwt=Jwt.withTokenValue("t").header("alg","none").subject(UUID.randomUUID().toString()).claim("companyId",company.toString()).build();
  var auth=new JwtAuthenticationToken(jwt,List.of(new SimpleGrantedAuthority("ROLE_OPERADOR")));
  java.time.Instant desde=java.time.Instant.parse("2026-10-01T00:00:00Z"),hasta=desde.plusSeconds(86400);
  var metrics=new OfferService.OperatorMetrics(1,1,java.math.BigDecimal.TEN,java.math.BigDecimal.ONE,java.math.BigDecimal.ONE,null);
  when(service.operatorMetrics(desde,hasta,company,false)).thenReturn(metrics);
  assertThat(new OperatorOfferController(service).metrics(desde,hasta,auth)).isSameAs(metrics);
 }
}
