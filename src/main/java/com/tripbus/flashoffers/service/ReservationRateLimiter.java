package com.tripbus.flashoffers.service;

import com.tripbus.flashoffers.common.error.TooManyRequestsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * US-168: máximo {@code tripbus.offers.reservation-attempts-per-minute} (10) intentos por usuario en
 * POST /offers/{id}/reservations, en todas las ofertas y todos los nodos: contador por usuario y minuto en Redis
 * (INCR + EXPIRE atómicos). Al pasarse responde 429 con Retry-After hasta el siguiente minuto. Si Redis no responde
 * no se bloquea la reserva (la protege igual el contador de cupo).
 */
@Component
public class ReservationRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ReservationRateLimiter.class);

    private static final RedisScript<Long> HIT = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return n
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final int limit;

    public ReservationRateLimiter(StringRedisTemplate redis, Clock clock,
                                  @Value("${tripbus.offers.reservation-attempts-per-minute:10}") int limit) {
        this.redis = redis;
        this.clock = clock;
        this.limit = limit;
    }

    static String key(UUID userId, long minute) {
        return "ratelimit:offer-reservations:" + userId + ":" + minute;
    }

    /** Cuenta un intento del usuario; lanza 429 si superó el máximo del minuto en curso. */
    public void check(UUID userId) {
        long nowSeconds = clock.millis() / 1000;
        long minute = nowSeconds / 60;
        Long attempts;
        try {
            attempts = redis.execute(HIT, List.of(key(userId, minute)), "120");
        } catch (DataAccessException e) {
            log.warn("Redis no disponible para el límite de intentos: {}", e.getMessage());
            return;
        }
        if (attempts != null && attempts > limit) {
            long retryAfter = 60 - (nowSeconds % 60);
            throw new TooManyRequestsException("TOO_MANY_ATTEMPTS",
                    "Demasiados intentos de reserva: máximo " + limit + " por minuto", retryAfter);
        }
    }
}
