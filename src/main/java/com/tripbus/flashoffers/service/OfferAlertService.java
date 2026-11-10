package com.tripbus.flashoffers.service;

import com.tripbus.flashoffers.common.events.EventPublisher;
import com.tripbus.flashoffers.domain.Offer;
import com.tripbus.flashoffers.events.OfferEvents;
import com.tripbus.flashoffers.repository.RouteFollowRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.*; import java.time.Clock; import java.util.*; import java.util.concurrent.CompletableFuture;

/** Alertas best-effort: la activación no espera el fan-out y la tabla hace idempotente cada destinatario. */
@Service public class OfferAlertService {
 private static final int BATCH_SIZE=100;
 private final RouteFollowRepository follows; private final JdbcTemplate jdbc; private final EventPublisher events; private final Clock clock;
 public OfferAlertService(RouteFollowRepository follows,JdbcTemplate jdbc,EventPublisher events,Clock clock){this.follows=follows;this.jdbc=jdbc;this.events=events;this.clock=clock;}
 public void alertAfterCommit(Offer offer){if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){CompletableFuture.runAsync(()->send(offer));}});else CompletableFuture.runAsync(()->send(offer));}
 void send(Offer offer){var users=follows.findByOrigenNormAndDestinoNorm(RouteFollowService.normalize(offer.getOrigen()),RouteFollowService.normalize(offer.getDestino())); for(int from=0;from<users.size();from+=BATCH_SIZE){for(var follow:users.subList(from,Math.min(users.size(),from+BATCH_SIZE))){if(jdbc.update("insert into offer_alerts_sent(id,offer_id,user_id,sent_at) values (?,?,?,?) on conflict (offer_id,user_id) do nothing",UUID.randomUUID(),offer.getId(),follow.getUserId(),clock.instant())==1){BigDecimal discount=offer.getPrecioBase()==null||offer.getPrecioBase().signum()==0?BigDecimal.ZERO:offer.getPrecioBase().subtract(offer.getPrecioOferta()).multiply(BigDecimal.valueOf(100)).divide(offer.getPrecioBase(),2,RoundingMode.HALF_UP);events.publishSafely(OfferEvents.OFFER_ALERT,Map.of("userId",follow.getUserId(),"offerId",offer.getId(),"itineraryId",offer.getItineraryId(),"origen",offer.getOrigen(),"destino",offer.getDestino(),"precioOferta",offer.getPrecioOferta(),"descuentoPorcentaje",discount,"fin",offer.getFin()));}}}}
}
