package com.tripbus.flashoffers.web.dto;

import com.tripbus.flashoffers.domain.AutoOfferRule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AutoOfferRuleResponse(UUID id, UUID companyId, UUID routeId, int horasAntes, BigDecimal ocupacionMaxima,
                                    BigDecimal descuento, int cupoMaximo, boolean activa, Instant actualizadoEn) {

    public static AutoOfferRuleResponse from(AutoOfferRule r) {
        return new AutoOfferRuleResponse(r.getId(), r.getCompanyId(), r.getRouteId(), r.getHorasAntes(),
                r.getOcupacionMaxima(), r.getDescuento(), r.getCupoMaximo(), r.isActiva(), r.getActualizadoEn());
    }
}
