package com.tripbus.flashoffers.web.dto;

import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record UpdateOfferRequest(@Positive BigDecimal precioOferta, @Positive Integer cupo,
        @Positive Integer maxPorUsuario, Instant inicio, Instant fin,
        List<String> seatNumbers, Boolean permiteReprogramar, Boolean salaEsperaHabilitada) {
    public UpdateOfferRequest(BigDecimal precioOferta, Integer cupo, Integer maxPorUsuario, Instant inicio,
                              Instant fin, List<String> seatNumbers, Boolean permiteReprogramar) {
        this(precioOferta, cupo, maxPorUsuario, inicio, fin, seatNumbers, permiteReprogramar, null);
    }
}
