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
}
