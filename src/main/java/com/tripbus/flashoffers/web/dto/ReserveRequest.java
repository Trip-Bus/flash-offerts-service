package com.tripbus.flashoffers.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** docId = número de documento del viajero (idempotencia por offerId + userId + docId). */
public record ReserveRequest(
        @NotBlank(message = "El documento del viajero es obligatorio")
        @Pattern(regexp = "[A-Za-z0-9-]{4,20}", message = "Documento inválido")
        String docId) {
}
