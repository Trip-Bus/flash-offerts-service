package com.tripbus.flashoffers.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripbus.flashoffers.common.events.EventEnvelope;
import com.tripbus.flashoffers.service.OfferService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OfferEventsHandlerTest {
 private final ObjectMapper json=new ObjectMapper();
 private final OfferService service=mock(OfferService.class);
 private final OfferEventsHandler handler=new OfferEventsHandler(service);

 private EventEnvelope event(String type,ObjectNode payload){return new EventEnvelope(UUID.randomUUID().toString(),type,"test",Instant.now(),payload);}

 @Test void atiendeLosEventosDeItinerarioYDeRetenciones(){
  assertThat(handler.eventTypes()).contains(OfferEvents.ITINERARY_RESCHEDULED,OfferEvents.ITINERARY_CANCELLED);
  UUID itinerary=UUID.randomUUID(),hold=UUID.randomUUID(),offer=UUID.randomUUID();
  Instant salida=Instant.parse("2026-12-01T10:00:00Z");
  handler.handle(event(OfferEvents.ITINERARY_RESCHEDULED,json.createObjectNode().put("itineraryId",itinerary.toString()).put("fechaSalida",salida.toString())));
  verify(service).onItineraryRescheduled(itinerary,salida);
  handler.handle(event(OfferEvents.ITINERARY_CANCELLED,json.createObjectNode().put("itineraryId",itinerary.toString())));
  verify(service).onItineraryCancelled(itinerary);
  handler.handle(event(OfferEvents.SEAT_SOLD,json.createObjectNode().put("offerId",offer.toString()).put("holdId",hold.toString())));
  verify(service).onSeatSold(hold);
  handler.handle(event(OfferEvents.HOLD_EXPIRED,json.createObjectNode().put("offerId",offer.toString()).put("holdId",hold.toString())));
  verify(service).onHoldReleased(hold);
 }

 @Test void ignoraRetencionesQueNoSonDeOfertaYTiposDesconocidos(){
  handler.handle(event(OfferEvents.SEAT_SOLD,json.createObjectNode().put("holdId",UUID.randomUUID().toString())));
  handler.handle(event(OfferEvents.SEAT_RELEASED,json.createObjectNode().put("offerId",UUID.randomUUID().toString())));
  handler.handle(event("Otro",json.createObjectNode()));
  verifyNoInteractions(service);
 }
}
