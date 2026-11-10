package com.tripbus.flashoffers.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/** US-167: regla de oferta automática. companyId solo lo usa un ADMIN (el operador usa la de su token). */
public record AutoOfferRuleRequest(
        UUID companyId,
        UUID routeId,
        @NotNull(message = "Las horas antes de la salida son obligatorias")
        @Min(value = 1, message = "Mínimo 1 hora") @Max(value = 168, message = "Máximo 168 horas")
        Integer horasAntes,
        @NotNull(message = "La ocupación máxima es obligatoria")
        @DecimalMin(value = "0", inclusive = false, message = "La ocupación máxima debe ser mayor que 0")
        @DecimalMax(value = "100", message = "La ocupación máxima es un porcentaje (máximo 100)")
        BigDecimal ocupacionMaxima,
        @NotNull(message = "El descuento es obligatorio") @Positive(message = "El descuento debe ser positivo")
        BigDecimal descuento,
        @NotNull(message = "El cupo máximo es obligatorio") @Positive(message = "El cupo máximo debe ser positivo")
        Integer cupoMaximo,
        Boolean activa) {
}
