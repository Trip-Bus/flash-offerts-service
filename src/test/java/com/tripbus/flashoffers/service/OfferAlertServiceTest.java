package com.tripbus.flashoffers.service;

import com.tripbus.flashoffers.client.CatalogClient;
import com.tripbus.flashoffers.common.events.EventPublisher;
import com.tripbus.flashoffers.domain.Offer;
import com.tripbus.flashoffers.domain.RouteFollow;
import com.tripbus.flashoffers.repository.RouteFollowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal; import java.time.*; import java.util.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*; import static org.mockito.Mockito.*;

class OfferAlertServiceTest {
 @Test void emitsOnlyWhenIdempotencyInsertWins(){RouteFollowRepository follows=mock(RouteFollowRepository.class);JdbcTemplate jdbc=mock(JdbcTemplate.class);EventPublisher events=mock(EventPublisher.class);Clock clock=Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),ZoneOffset.UTC);Offer offer=new Offer(UUID.randomUUID(),new BigDecimal("80"),1,clock.instant(),clock.instant().plusSeconds(60),UUID.randomUUID(),clock.instant());offer.datosViaje(UUID.randomUUID(),"Bogotá","Cali",clock.instant(),new BigDecimal("100"),"Bus",true);when(follows.findByOrigenNormAndDestinoNorm("bogota","cali")).thenReturn(List.of(new RouteFollow(UUID.randomUUID(),"Bogotá","Cali","bogota","cali",clock.instant())));when(jdbc.update(startsWith("insert into offer_alerts_sent"),any(),any(),any(),any())).thenReturn(1);new OfferAlertService(follows,jdbc,events,clock).send(offer);verify(events).publishSafely(eq("OfferAlert"),any());when(jdbc.update(startsWith("insert into offer_alerts_sent"),any(),any(),any(),any())).thenReturn(0);new OfferAlertService(follows,jdbc,events,clock).send(offer);verify(events,times(1)).publishSafely(eq("OfferAlert"),any());}

 private static Offer offer(Clock clock,BigDecimal base){Offer o=new Offer(UUID.randomUUID(),new BigDecimal("80"),1,clock.instant(),clock.instant().plusSeconds(60),UUID.randomUUID(),clock.instant());o.datosViaje(UUID.randomUUID(),"Bogotá","Cali",clock.instant(),base,"Bus",true);return o;}

 @Test void sinPrecioBaseElDescuentoEsCeroYRecorreTodosLosLotes(){
  RouteFollowRepository follows=mock(RouteFollowRepository.class);JdbcTemplate jdbc=mock(JdbcTemplate.class);EventPublisher events=mock(EventPublisher.class);Clock clock=Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),ZoneOffset.UTC);
  List<RouteFollow> many=new ArrayList<>();for(int i=0;i<150;i++)many.add(new RouteFollow(UUID.randomUUID(),"Bogotá","Cali","bogota","cali",clock.instant()));
  when(follows.findByOrigenNormAndDestinoNorm("bogota","cali")).thenReturn(many);
  when(jdbc.update(startsWith("insert into offer_alerts_sent"),any(),any(),any(),any())).thenReturn(1);
  OfferAlertService service=new OfferAlertService(follows,jdbc,events,clock);
  service.send(offer(clock,null));
  service.send(offer(clock,BigDecimal.ZERO));
  org.mockito.ArgumentCaptor<Object> payload=org.mockito.ArgumentCaptor.forClass(Object.class);
  verify(events,times(300)).publishSafely(eq("OfferAlert"),payload.capture());
  assertThat(payload.getAllValues()).allSatisfy(p->assertThat((BigDecimal)((Map<?,?>)p).get("descuentoPorcentaje")).isEqualByComparingTo("0"));
 }

 @Test void fueraDeTransaccionEnviaDeInmediatoYDentroEsperaAlCommit(){
  RouteFollowRepository follows=mock(RouteFollowRepository.class);Clock clock=Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),ZoneOffset.UTC);
  OfferAlertService service=new OfferAlertService(follows,mock(JdbcTemplate.class),mock(EventPublisher.class),clock);
  Offer offer=offer(clock,new BigDecimal("100"));
  service.alertAfterCommit(offer);
  verify(follows,timeout(5000)).findByOrigenNormAndDestinoNorm("bogota","cali");

  TransactionSynchronizationManager.initSynchronization();
  try{
   service.alertAfterCommit(offer);
   verify(follows,times(1)).findByOrigenNormAndDestinoNorm("bogota","cali");
   TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
  }finally{TransactionSynchronizationManager.clearSynchronization();}
  verify(follows,timeout(5000).times(2)).findByOrigenNormAndDestinoNorm("bogota","cali");
 }
}
