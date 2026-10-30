package com.tripbus.flashoffers.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OfferResponse(
        UUID offerId,
        UUID itineraryId,
        BigDecimal precioOferta,
        int cupoMax,
        int maxPorUsuario,
        int cupoRestante,
        Instant inicio,
        Instant fin,
        String estado,
        long segundosRestantes,
        List<String> asientos,
        String motivoCierre,
        boolean permiteReprogramar) {
    public OfferResponse(UUID offerId, UUID itineraryId, BigDecimal precioOferta, int cupoMax, int maxPorUsuario,
                         int cupoRestante, Instant inicio, Instant fin, String estado, long segundosRestantes,
                         List<String> asientos, String motivoCierre) {
        this(offerId,itineraryId,precioOferta,cupoMax,maxPorUsuario,cupoRestante,inicio,fin,estado,segundosRestantes,
                asientos,motivoCierre,true);
    }
}
