package com.tripbus.flashoffers.web.dto;

import java.time.Instant;
import java.util.UUID;

/** US-056: tiempo restante y cupo de la oferta. */
public record QuotaResponse(UUID offerId, String estado, int cupoMax, int cupoRestante, Instant fin,
                            long segundosRestantes) {
}
