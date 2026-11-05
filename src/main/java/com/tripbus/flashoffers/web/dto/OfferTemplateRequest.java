package com.tripbus.flashoffers.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * US-169: plantilla de oferta recurrente. diasSemana: días ISO de salida del viaje (1 = lunes ... 7 = domingo);
 * horaInicio "HH:mm". companyId solo lo usa un ADMIN.
 */
public record OfferTemplateRequest(
        UUID companyId,
        @NotNull(message = "La ruta es obligatoria") UUID routeId,
        @NotEmpty(message = "Indique al menos un día de la semana")
        List<@NotNull @Min(value = 1, message = "Día inválido (1-7)") @Max(value = 7, message = "Día inválido (1-7)") Integer> diasSemana,
        @NotNull(message = "La hora de inicio es obligatoria") LocalTime horaInicio,
        @NotNull(message = "La duración es obligatoria") @Min(value = 5, message = "La duración mínima es de 5 minutos")
        Integer duracionMinutos,
        @NotNull(message = "El descuento es obligatorio") @Positive(message = "El descuento debe ser positivo")
        BigDecimal descuento,
        @NotNull(message = "El cupo es obligatorio") @Positive(message = "El cupo debe ser positivo") Integer cupo,
        @Positive(message = "El máximo por usuario debe ser positivo") Integer maxPorUsuario,
        Boolean permiteReprogramar,
        Boolean salaEspera,
        @NotNull(message = "Las horas antes de la salida son obligatorias")
        @Min(value = 0, message = "No puede ser negativo") @Max(value = 168, message = "Máximo 168 horas")
        Integer horasAntesSalida,
        Boolean activa) {
}
