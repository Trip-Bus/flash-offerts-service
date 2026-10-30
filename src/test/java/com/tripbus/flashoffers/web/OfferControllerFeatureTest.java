package com.tripbus.flashoffers.web;

import com.tripbus.flashoffers.service.OfferService;
import com.tripbus.flashoffers.web.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.tripbus.flashoffers.service.RouteFollowService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OfferControllerFeatureTest {
 private static JwtAuthenticationToken operator(UUID user,UUID company){
  Jwt jwt=Jwt.withTokenValue("t").header("alg","none").subject(user.toString()).claim("companyId",company.toString()).build();
  return new JwtAuthenticationToken(jwt,List.of(new SimpleGrantedAuthority("ROLE_OPERADOR")));
 }
 private static OfferResponse response(UUID id){return new OfferResponse(id,UUID.randomUUID(),new BigDecimal("50"),2,1,2,Instant.now(),Instant.now().plusSeconds(60),"PROGRAMADA",60,List.of("1","2"),null);}
 @Test void showcasePasaFiltrosYPaginaAlServicio(){
  OfferService service=mock(OfferService.class);var result=org.springframework.data.domain.Page.<OfferService.ShowcaseOffer>empty();when(service.showcase("Bogotá","Cali",0,100)).thenReturn(result);
  assertThat(new OfferController(service).showcase("Bogotá","Cali",0,100)).isSameAs(result);
 }
 @Test void actualizaCancelaYDuplicaConIdentidadOperadora(){
  OfferService service=mock(OfferService.class);UUID id=UUID.randomUUID(),user=UUID.randomUUID(),company=UUID.randomUUID();var auth=operator(user,company);OfferResponse expected=response(id);
  var update=new UpdateOfferRequest(new BigDecimal("50"),2,1,Instant.now().plusSeconds(60),Instant.now().plusSeconds(600),List.of("1","2"),true);
  when(service.update(eq(id),eq(update),eq(company),eq(false))).thenReturn(expected);
  when(service.cancelByOperator(id,company,false)).thenReturn(expected);
  var duplicate=new DuplicateOfferRequest(UUID.randomUUID(),update.inicio(),update.fin(),null);when(service.duplicate(eq(id),eq(duplicate),eq(company),eq(false),eq(user))).thenReturn(expected);
  OfferController controller=new OfferController(service);
  assertThat(controller.update(id,update,auth)).isSameAs(expected);
  assertThat(controller.cancel(id,auth)).isSameAs(expected);
  assertThat(controller.duplicate(id,duplicate,auth).getStatusCode().value()).isEqualTo(201);
 }
 @Test void seguimientosDeRutaUsanElUsuarioAutenticado(){
  OfferService service=mock(OfferService.class);RouteFollowService follows=mock(RouteFollowService.class);
  UUID user=UUID.randomUUID(),id=UUID.randomUUID();var auth=operator(user,UUID.randomUUID());
  var request=new RouteFollowRequest("Bogotá","Cali");var followed=new RouteFollowResponse(id,"Bogotá","Cali",Instant.now());
  when(follows.follow(user,request)).thenReturn(followed);when(follows.list(user)).thenReturn(List.of(followed));
  OfferController controller=new OfferController(service,follows);
  var created=controller.follow(request,auth);
  assertThat(created.getStatusCode().value()).isEqualTo(201);
  assertThat(created.getBody()).isSameAs(followed);
  assertThat(controller.follows(auth)).containsExactly(followed);
  assertThat(controller.unfollow(id,auth).getStatusCode().value()).isEqualTo(204);
  verify(follows).delete(user,id);
 }
 @Test void sinServicioDeSeguimientosFallaExplicitamente(){
  var auth=operator(UUID.randomUUID(),UUID.randomUUID());
  assertThatThrownBy(()->new OfferController(mock(OfferService.class)).follows(auth)).isInstanceOf(IllegalStateException.class);
 }
 @Test void metricasDeOfertaUsanLaEmpresaDelOperador(){
  OfferService service=mock(OfferService.class);UUID id=UUID.randomUUID(),company=UUID.randomUUID();
  var metrics=new OfferService.OfferMetrics(2,1,1,0,BigDecimal.ONE,null,new BigDecimal("50"),new BigDecimal("50"),3);
  when(service.metrics(id,company,false)).thenReturn(metrics);
  assertThat(new OfferController(service).metrics(id,operator(UUID.randomUUID(),company))).isSameAs(metrics);
 }
}
