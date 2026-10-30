package com.tripbus.flashoffers.web.dto;

import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record UpdateOfferRequest(@Positive BigDecimal precioOferta, @Positive Integer cupo,
        @Positive Integer maxPorUsuario, Instant inicio, Instant fin,
        List<String> seatNumbers, Boolean permiteReprogramar) { }
