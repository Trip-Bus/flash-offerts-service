package com.tripbus.flashoffers.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * US-162: sala de espera virtual.
 *
 * @param batchInterval cada cuánto se admite un lote (por defecto 10 s)
 * @param batchSize     personas admitidas por lote (por defecto 50)
 * @param admissionTtl  vigencia del token de admisión (por defecto 5 min)
 * @param opensBefore   cuánto antes del inicio abre la sala (por defecto 10 min)
 */
@ConfigurationProperties(prefix = "tripbus.offers.queue")
public record WaitingRoomProperties(Duration batchInterval, int batchSize, Duration admissionTtl,
                                    Duration opensBefore) {

    public WaitingRoomProperties {
        batchInterval = positiveOr(batchInterval, Duration.ofSeconds(10));
        batchSize = batchSize > 0 ? batchSize : 50;
        admissionTtl = positiveOr(admissionTtl, Duration.ofMinutes(5));
        opensBefore = positiveOr(opensBefore, Duration.ofMinutes(10));
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
