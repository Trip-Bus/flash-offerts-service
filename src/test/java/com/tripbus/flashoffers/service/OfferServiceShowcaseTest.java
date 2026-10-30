package com.tripbus.flashoffers.service;

import com.tripbus.flashoffers.client.CatalogClient;
import com.tripbus.flashoffers.client.SeatInventoryClient;
import com.tripbus.flashoffers.common.events.EventPublisher;
import com.tripbus.flashoffers.domain.Offer;
import com.tripbus.flashoffers.domain.OfferStatus;
import com.tripbus.flashoffers.domain.SaleStatus;
import com.tripbus.flashoffers.repository.OfferRepository;
import com.tripbus.flashoffers.repository.OfferSaleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

class OfferServiceShowcaseTest {
 @Test void fallsBackToDatabaseWhenRedisQuotaIsUnavailable(){
  Instant now=Instant.parse("2026-10-28T12:00:00Z"); UUID itinerary=UUID.randomUUID();
  Offer offer=new Offer(itinerary,new BigDecimal("50"),5,now.minusSeconds(30),now.plusSeconds(3600),UUID.randomUUID(),now.minusSeconds(60));
  offer.asignarAsientos(List.of("1","2","3","4","5"));offer.programar();offer.activar(now);
  offer.datosViaje(UUID.randomUUID(),"Bogotá","Cali",now.plusSeconds(7200),new BigDecimal("100"),"Bus Co",true);
  OfferRepository offers=mock(OfferRepository.class);when(offers.findByEstadoInOrderByInicioAsc(anyCollection())).thenReturn(List.of(offer));
  OfferSaleRepository sales=mock(OfferSaleRepository.class);when(sales.countByOfferIdAndEstadoIn(eq(offer.getId()),anyCollection())).thenReturn(2L);
  QuotaCounter quota=mock(QuotaCounter.class);when(quota.remaining(offer.getId())).thenThrow(new DataAccessResourceFailureException("redis down"));
  OfferService service=new OfferService(offers,sales,quota,mock(SeatInventoryClient.class),mock(CatalogClient.class),
    mock(EventPublisher.class),mock(TransactionTemplate.class),mock(JdbcTemplate.class),Clock.fixed(now,ZoneOffset.UTC));
  var page=service.showcase("bogota","cali",0,10);
  assertThat(page.getContent()).hasSize(1);
  assertThat(page.getContent().getFirst().cupoRestante()).isEqualTo(3);
  assertThat(page.getContent().getFirst().estado()).isEqualTo(OfferStatus.ACTIVA.name());
  verify(sales).countByOfferIdAndEstadoIn(eq(offer.getId()),eq(Set.of(SaleStatus.RESERVADA,SaleStatus.VENDIDA)));
 }

 private static Offer offer(Instant now,long startOffset,long endOffset,String origen,String destino,BigDecimal base){
  Offer o=new Offer(UUID.randomUUID(),new BigDecimal("50"),2,now.plusSeconds(startOffset),now.plusSeconds(endOffset),UUID.randomUUID(),now.minusSeconds(600));
  o.asignarAsientos(List.of("1","2"));o.programar();
  o.datosViaje(UUID.randomUUID(),origen,destino,now.plusSeconds(400000),base,"Bus Co",true);
  return o;
 }

 @Test void filtraVentanaRutaYPaginaSinFiltros(){
  Instant now=Instant.parse("2026-10-28T12:00:00Z");
  Offer active=offer(now,-60,3600,"Bogotá","Cali",null); active.activar(now);
  Offer expired=offer(now,-7200,-60,"Bogotá","Cali",new BigDecimal("100")); expired.activar(now);
  Offer soon=offer(now,600,4200,null,"Pasto",new BigDecimal("100"));
  Offer tooFar=offer(now,49*3600,50*3600,"Bogotá","Cali",new BigDecimal("100"));
  Offer started=offer(now,-30,3600,"Bogotá","Cali",new BigDecimal("100"));
  OfferRepository offers=mock(OfferRepository.class);when(offers.findByEstadoInOrderByInicioAsc(anyCollection())).thenReturn(List.of(soon,active,expired,tooFar,started));
  OfferSaleRepository sales=mock(OfferSaleRepository.class);
  QuotaCounter quota=mock(QuotaCounter.class);when(quota.remaining(active.getId())).thenReturn(1);
  OfferService service=new OfferService(offers,sales,quota,mock(SeatInventoryClient.class),mock(CatalogClient.class),
    mock(EventPublisher.class),mock(TransactionTemplate.class),mock(JdbcTemplate.class),Clock.fixed(now,ZoneOffset.UTC));

  var all=service.showcase(null,null,0,10);
  assertThat(all.getContent()).extracting(OfferService.ShowcaseOffer::offerId).containsExactly(active.getId(),soon.getId());
  assertThat(all.getContent().get(0).descuentoPorcentaje()).isEqualByComparingTo("0");
  assertThat(all.getContent().get(1).descuentoPorcentaje()).isEqualByComparingTo("50");
  assertThat(service.showcase("bogota",null,0,10).getContent()).extracting(OfferService.ShowcaseOffer::offerId).containsExactly(active.getId());
  assertThat(service.showcase(null,"pasto",0,10).getContent()).extracting(OfferService.ShowcaseOffer::offerId).containsExactly(soon.getId());
  assertThat(service.showcase(null,null,1,1).getContent()).extracting(OfferService.ShowcaseOffer::offerId).containsExactly(soon.getId());
  assertThat(service.showcase(null,null,5,10).getContent()).isEmpty();
 }
}
