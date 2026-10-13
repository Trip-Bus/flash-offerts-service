package com.tripbus.flashoffers.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * US-058: contador atómico de cupo en Redis ({@code offer:{id}:quota}).
 * La reserva es un script Lua (atómico en Redis): DECR del cupo; si queda < 0 se revierte
 * con INCR y se rechaza; si no, se toma un asiento de la lista de la oferta. Así nunca se
 * vende más que el cupo, ni con miles de compras por segundo.
 */
@Component
public class QuotaCounter {

    private static final RedisScript<String> RESERVE = new DefaultRedisScript<>("""
            local restante = redis.call('DECR', KEYS[1])
            if restante < 0 then
              redis.call('INCR', KEYS[1])
              return nil
            end
            local asiento = redis.call('LPOP', KEYS[2])
            if not asiento then
              redis.call('INCR', KEYS[1])
              return nil
            end
            return asiento
            """, String.class);

    private static final RedisScript<Long> GIVE_BACK = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[3]) == 0 then
              return -1
            end
            redis.call('RPUSH', KEYS[2], ARGV[1])
            return redis.call('INCR', KEYS[1])
            """, Long.class);

    private static final RedisScript<Long> INIT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[3]) == 1 then
              return 0
            end
            redis.call('SET', KEYS[1], ARGV[1])
            redis.call('DEL', KEYS[2])
            for i = 2, #ARGV do
              redis.call('RPUSH', KEYS[2], ARGV[i])
            end
            redis.call('SET', KEYS[3], '1')
            return 1
            """, Long.class);

    // Corrección de la reconciliación: solo si el contador sigue con el valor observado (nadie reservó ni devolvió
    // entretanto); deja el cupo esperado y reconstruye la lista con los asientos libres reales.
    private static final RedisScript<Long> CORRECT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[3]) == 0 then
              return 0
            end
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
              return 0
            end
            redis.call('SET', KEYS[1], ARGV[2])
            redis.call('DEL', KEYS[2])
            for i = 3, #ARGV do
              redis.call('RPUSH', KEYS[2], ARGV[i])
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public QuotaCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String quotaKey(UUID offerId) {
        return "offer:{" + offerId + "}:quota";
    }

    static String seatsKey(UUID offerId) {
        return "offer:{" + offerId + "}:seats";
    }

    static String initKey(UUID offerId) {
        return "offer:{" + offerId + "}:init";
    }

    static String mismatchKey(UUID offerId) {
        return "offer:{" + offerId + "}:mismatch";
    }

    private static List<String> keys(UUID offerId) {
        return List.of(quotaKey(offerId), seatsKey(offerId), initKey(offerId));
    }

    public boolean isInitialized(UUID offerId) {
        return Boolean.TRUE.equals(redis.hasKey(initKey(offerId)));
    }

    /** Inicializa el contador (si no existía) con el cupo restante y los asientos libres reales. */
    public void initialize(UUID offerId, int cupoRestante, Collection<String> asientosLibres) {
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(Math.max(0, cupoRestante)));
        args.addAll(asientosLibres);
        redis.execute(INIT, keys(offerId), args.toArray());
    }

    /** Devuelve el asiento reservado o null si no hay cupo. */
    public String reserve(UUID offerId) {
        return redis.execute(RESERVE, List.of(quotaKey(offerId), seatsKey(offerId)));
    }

    /** Devuelve cupo y asiento (compensación). Devuelve el cupo resultante, o -1 si la oferta ya no tiene contador. */
    public long giveBack(UUID offerId, String seatNumber) {
        Long result = redis.execute(GIVE_BACK, keys(offerId), seatNumber);
        return result != null ? result : -1;
    }

    public Integer remaining(UUID offerId) {
        String value = redis.opsForValue().get(quotaKey(offerId));
        return value != null ? Integer.parseInt(value) : null;
    }

    public void delete(UUID offerId) {
        List<String> all = new ArrayList<>(keys(offerId));
        all.add(mismatchKey(offerId));
        redis.delete(all);
    }

    /**
     * Reconciliación: pone el cupo en {@code nuevo} y la lista de asientos en {@code asientosLibres}, solo si el
     * contador sigue valiendo {@code observado}. Devuelve false si cambió entretanto o la oferta no tiene contador.
     */
    public boolean correct(UUID offerId, int observado, int nuevo, Collection<String> asientosLibres) {
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(observado));
        args.add(String.valueOf(Math.max(0, nuevo)));
        args.addAll(asientosLibres);
        Long result = redis.execute(CORRECT, keys(offerId), args.toArray());
        return result != null && result == 1;
    }

    /** Desajuste visto en la revisión anterior de la reconciliación ("esperado:redis"), o null. */
    public String lastMismatch(UUID offerId) {
        return redis.opsForValue().get(mismatchKey(offerId));
    }

    public void rememberMismatch(UUID offerId, String mismatch, Duration ttl) {
        redis.opsForValue().set(mismatchKey(offerId), mismatch, ttl);
    }

    public void forgetMismatch(UUID offerId) {
        redis.delete(mismatchKey(offerId));
    }
}
