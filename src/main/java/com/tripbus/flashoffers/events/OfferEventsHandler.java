package com.tripbus.flashoffers.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripbus.flashoffers.common.events.DomainEventHandler;
import com.tripbus.flashoffers.common.events.EventEnvelope;
import com.tripbus.flashoffers.service.OfferService;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/** SeatSold / SeatReleased / HoldExpired de retenciones de oferta, e ItineraryCancelled. */
@Component
public class OfferEventsHandler implements DomainEventHandler {

    private final OfferService offerService;

    public OfferEventsHandler(OfferService offerService) {
        this.offerService = offerService;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(OfferEvents.SEAT_SOLD, OfferEvents.SEAT_RELEASED, OfferEvents.HOLD_EXPIRED,
                OfferEvents.ITINERARY_CANCELLED, OfferEvents.ITINERARY_RESCHEDULED);
    }

    @Override
    public void handle(EventEnvelope event) {
        JsonNode p = event.payload();
        switch (event.type()) {
            case OfferEvents.SEAT_SOLD -> {
                if (p.hasNonNull("offerId") && p.hasNonNull("holdId")) {
                    offerService.onSeatSold(UUID.fromString(p.get("holdId").asText()));
                }
            }
            case OfferEvents.SEAT_RELEASED, OfferEvents.HOLD_EXPIRED -> {
                if (p.hasNonNull("offerId") && p.hasNonNull("holdId")) {
                    offerService.onHoldReleased(UUID.fromString(p.get("holdId").asText()));
                }
            }
            case OfferEvents.ITINERARY_CANCELLED ->
                    offerService.onItineraryCancelled(UUID.fromString(p.path("itineraryId").asText()));
            case OfferEvents.ITINERARY_RESCHEDULED -> offerService.onItineraryRescheduled(
                    UUID.fromString(p.path("itineraryId").asText()), java.time.Instant.parse(p.path("fechaSalida").asText()));
            default -> {
            }
        }
    }
}
