package com.tripbus.flashoffers.web.dto;

import com.tripbus.flashoffers.domain.OfferTemplate;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record OfferTemplateResponse(UUID id, UUID companyId, UUID routeId, List<Integer> diasSemana,
                                    LocalTime horaInicio, int duracionMinutos, BigDecimal descuento, int cupo,
                                    int maxPorUsuario, boolean permiteReprogramar, boolean salaEspera,
                                    int horasAntesSalida, boolean activa, Instant actualizadoEn) {

    public static OfferTemplateResponse from(OfferTemplate t) {
        return new OfferTemplateResponse(t.getId(), t.getCompanyId(), t.getRouteId(),
                t.dias().stream().map(DayOfWeek::getValue).toList(), t.getHoraInicio(), t.getDuracionMinutos(),
                t.getDescuento(), t.getCupo(), t.getMaxPorUsuario(), t.isPermiteReprogramar(), t.isSalaEspera(),
                t.getHorasAntesSalida(), t.isActiva(), t.getActualizadoEn());
    }
}
