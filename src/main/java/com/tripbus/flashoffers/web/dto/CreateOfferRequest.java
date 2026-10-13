package com.tripbus.flashoffers.web.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** US-054: oferta relámpago con cupo y horario. */
public record CreateOfferRequest(
        @NotNull(message = "El itinerario es obligatorio")
        UUID itineraryId,

        @NotEmpty(message = "Debe indicar los asientos de la oferta")
        List<String> seatNumbers,

        @NotNull(message = "El precio de oferta es obligatorio")
        @Positive(message = "El precio de oferta debe ser positivo")
        BigDecimal precioOferta,

        @NotNull(message = "El cupo es obligatorio")
        @Positive(message = "El cupo debe ser positivo")
        Integer cupo,

        @NotNull(message = "La hora de inicio es obligatoria")
        Instant inicio,

        @NotNull(message = "La hora de fin es obligatoria")
        @Future(message = "La hora de fin debe estar en el futuro")
        Instant fin,

        // US-058: opcional; si no viene, 2 (o el cupo, si es menor).
        @Positive(message = "El máximo por usuario debe ser positivo")
        Integer maxPorUsuario) {

    public CreateOfferRequest(UUID itineraryId, List<String> seatNumbers, BigDecimal precioOferta, Integer cupo,
                              Instant inicio, Instant fin) {
        this(itineraryId, seatNumbers, precioOferta, cupo, inicio, fin, null);
    }
}
