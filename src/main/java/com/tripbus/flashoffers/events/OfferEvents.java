package com.tripbus.flashoffers.events;

public final class OfferEvents {

    // Publica
    public static final String OFFER_CREATED = "OfferCreated";
    public static final String OFFER_ACTIVATED = "OfferActivated";
    public static final String OFFER_CLOSED = "OfferClosed";
    public static final String OFFER_SOLD_OUT = "OfferSoldOut";
    public static final String OFFER_UPDATED = "OfferUpdated";
    public static final String OFFER_QUOTA_UPDATED = "OfferQuotaUpdated";
    public static final String OFFER_ALERT = "OfferAlert";
    // US-162: sala de espera (los difunde el Realtime Gateway).
    public static final String OFFER_QUEUE_ADMITTED = "OfferQueueAdmitted";
    public static final String OFFER_QUEUE_UPDATED = "OfferQueueUpdated";

    // Consume
    public static final String SEAT_SOLD = "SeatSold";
    public static final String SEAT_RELEASED = "SeatReleased";
    public static final String HOLD_EXPIRED = "HoldExpired";
    public static final String ITINERARY_CANCELLED = "ItineraryCancelled";
    public static final String ITINERARY_RESCHEDULED = "ItineraryRescheduled";

    private OfferEvents() {
    }
}
