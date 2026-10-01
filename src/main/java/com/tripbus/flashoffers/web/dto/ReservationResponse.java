package com.tripbus.flashoffers.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ReservationResponse(
        UUID reservationId,
        UUID offerId,
        UUID itineraryId,
        UUID holdId,
        UUID userId,
        String seatNumber,
        BigDecimal precioOferta,
        String estado) {
}
