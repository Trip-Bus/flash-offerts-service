package com.tripbus.flashoffers.service;

import com.tripbus.flashoffers.client.CatalogClient;
import com.tripbus.flashoffers.common.events.EventPublisher;
import com.tripbus.flashoffers.domain.Offer;
import com.tripbus.flashoffers.domain.RouteFollow;
import com.tripbus.flashoffers.repository.RouteFollowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal; import java.time.*; import java.util.*;
import static org.mockito.ArgumentMatchers.*; import static org.mockito.Mockito.*;

class OfferAlertServiceTest {
 @Test void emitsOnlyWhenIdempotencyInsertWins(){RouteFollowRepository follows=mock(RouteFollowRepository.class);JdbcTemplate jdbc=mock(JdbcTemplate.class);EventPublisher events=mock(EventPublisher.class);Clock clock=Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),ZoneOffset.UTC);Offer offer=new Offer(UUID.randomUUID(),new BigDecimal("80"),1,clock.instant(),clock.instant().plusSeconds(60),UUID.randomUUID(),clock.instant());offer.datosViaje(UUID.randomUUID(),"Bogotá","Cali",clock.instant(),new BigDecimal("100"),"Bus",true);when(follows.findByOrigenNormAndDestinoNorm("bogota","cali")).thenReturn(List.of(new RouteFollow(UUID.randomUUID(),"Bogotá","Cali","bogota","cali",clock.instant())));when(jdbc.update(startsWith("insert into offer_alerts_sent"),any(),any(),any(),any())).thenReturn(1);new OfferAlertService(follows,jdbc,events,clock).send(offer);verify(events).publishSafely(eq("OfferAlert"),any());when(jdbc.update(startsWith("insert into offer_alerts_sent"),any(),any(),any(),any())).thenReturn(0);new OfferAlertService(follows,jdbc,events,clock).send(offer);verify(events,times(1)).publishSafely(eq("OfferAlert"),any());}
}
