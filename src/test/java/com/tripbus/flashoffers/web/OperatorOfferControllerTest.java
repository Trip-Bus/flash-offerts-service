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
}
