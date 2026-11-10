package com.tripbus.flashoffers.service;

import com.tripbus.flashoffers.common.error.ApiException;
import com.tripbus.flashoffers.common.events.EventPublisher;
import com.tripbus.flashoffers.config.WaitingRoomProperties;
import com.tripbus.flashoffers.domain.Offer;
import com.tripbus.flashoffers.domain.OfferStatus;
import com.tripbus.flashoffers.events.OfferEvents;
import com.tripbus.flashoffers.repository.OfferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * US-162: sala de espera virtual, toda en Redis (las llaves de una oferta comparten el hash tag {@code {offerId}}).
 *
 * <ul>
 *   <li>Desde {@code opens-before} (10 min) antes del inicio, cada pasajero entra una sola vez ({@code entered}, set).</li>
 *   <li>{@code queue} (sorted set): antes del sorteo el puntaje es solo el orden de llegada y no se muestra. Al
 *   iniciar la oferta el primer lote sortea (Fisher-Yates atómico en Lua con semilla de {@link SecureRandom}) el
 *   orden de todos los que ya estaban: puntajes 1..N. Quien entra después recibe N + su número de llegada: va al
 *   final, por orden de llegada.</li>
 *   <li>Cada lote saca (ZPOPMIN) hasta {@code batch-size} personas y les da un token de admisión
 *   ({@code admission:{userId}}) que vence a los {@code admission-ttl} (5 min).</li>
 *   <li>La sala se cierra cuando la oferta se agota o termina: nadie más entra ni se admite.</li>
 * </ul>
 * Las llaves vencen un día después del fin de la oferta.
 */
@Service
public class WaitingRoomService {

    private static final Logger log = LoggerFactory.getLogger(WaitingRoomService.class);

    /** Estado de un pasajero en la sala. */
    public enum QueueState {
        /** No entró a la sala. */
        NO_INSCRITO,
        /** Está en la sala antes del inicio: su turno se sortea al iniciar la oferta. */
        EN_SALA,
        /** Está en la fila (después del sorteo) esperando su lote. */
        EN_FILA,
        /** Tiene un token de admisión vigente: puede reservar. */
        ADMITIDO,
        /** Fue admitido pero su token venció. */
        TURNO_VENCIDO,
        /** La sala se cerró (oferta agotada o terminada). */
        CERRADA
    }

    public record QueueStatus(UUID offerId, QueueState estado, Long posicion, long enFila, Instant admisionExpiraEn) {
    }

    public record JoinResult(QueueStatus status, boolean created) {
    }

    /** Resultado de un lote: quiénes se admitieron, cuántos siguen en fila y cuántos se han admitido en total. */
    public record Batch(UUID offerId, List<UUID> admitidos, long enFila, long totalAdmitidos) {
    }

    // KEYS: queue, entered, seq, drawn, closed. ARGV: userId, ttl de las llaves (s).
    // -2 = sala cerrada; -1 = ya había entrado; 1 = entró.
    private static final RedisScript<Long> JOIN = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[5]) == 1 then
              return -2
            end
            if redis.call('SADD', KEYS[2], ARGV[1]) == 0 then
              return -1
            end
            local seq = redis.call('INCR', KEYS[3])
            local drawn = redis.call('GET', KEYS[4])
            local score = seq
            if drawn then
              score = tonumber(drawn) + seq
            end
            redis.call('ZADD', KEYS[1], score, ARGV[1])
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            redis.call('EXPIRE', KEYS[2], ARGV[2])
            redis.call('EXPIRE', KEYS[3], ARGV[2])
            return 1
            """, Long.class);

    // KEYS: queue, drawn. ARGV: semilla, ttl (s). Sortea una sola vez el orden de quienes ya estaban en la sala.
    private static final RedisScript<Long> DRAW = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[2]) == 1 then
              return -1
            end
            local members = redis.call('ZRANGE', KEYS[1], 0, -1)
            math.randomseed(tonumber(ARGV[1]))
            local n = #members
            for i = n, 2, -1 do
              local j = math.random(i)
              members[i], members[j] = members[j], members[i]
            end
            for i = 1, n do
              redis.call('ZADD', KEYS[1], i, members[i])
            end
            redis.call('SET', KEYS[2], n, 'EX', ARGV[2])
            return n
            """, Long.class);

    // KEYS: queue, admitted, admitted-count, closed. ARGV: tamaño del lote, prefijo del token, ttl token, ttl llaves.
    @SuppressWarnings("unchecked")
    private static final Class<List<String>> STRING_LIST = (Class<List<String>>) (Class<?>) List.class;

    private static final RedisScript<List<String>> ADMIT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[4]) == 1 then
              return {}
            end
            local popped = redis.call('ZPOPMIN', KEYS[1], ARGV[1])
            local users = {}
            for i = 1, #popped, 2 do
              local user = popped[i]
              redis.call('SET', ARGV[2] .. user, '1', 'EX', ARGV[3])
              redis.call('SADD', KEYS[2], user)
              users[#users + 1] = user
            end
            if #users > 0 then
              redis.call('INCRBY', KEYS[3], #users)
              redis.call('EXPIRE', KEYS[2], ARGV[4])
              redis.call('EXPIRE', KEYS[3], ARGV[4])
            end
            return users
            """, STRING_LIST);

    private final StringRedisTemplate redis;
    private final OfferRepository offers;
    private final EventPublisher events;
    private final WaitingRoomProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public WaitingRoomService(StringRedisTemplate redis, OfferRepository offers, EventPublisher events,
                              WaitingRoomProperties properties, Clock clock) {
        this.redis = redis;
        this.offers = offers;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ llaves

    private static String prefix(UUID offerId) {
        return "offer:{" + offerId + "}:";
    }

    static String queueKey(UUID offerId) {
        return prefix(offerId) + "queue";
    }

    private static String enteredKey(UUID offerId) {
        return prefix(offerId) + "queue:entered";
    }

    private static String seqKey(UUID offerId) {
        return prefix(offerId) + "queue:seq";
    }

    private static String drawnKey(UUID offerId) {
        return prefix(offerId) + "queue:drawn";
    }

    private static String closedKey(UUID offerId) {
        return prefix(offerId) + "queue:closed";
    }

    private static String admittedKey(UUID offerId) {
        return prefix(offerId) + "queue:admitted";
    }

    private static String admittedCountKey(UUID offerId) {
        return prefix(offerId) + "queue:admitted-count";
    }

    private static String tokenPrefix(UUID offerId) {
        return prefix(offerId) + "admission:";
    }

    static String tokenKey(UUID offerId, UUID userId) {
        return tokenPrefix(offerId) + userId;
    }

    /** Las llaves de la sala viven hasta un día después del fin de la oferta. */
    private long keysTtlSeconds(Offer offer) {
        Duration left = Duration.between(clock.instant(), offer.getFin());
        return Math.max(0, left.toSeconds()) + Duration.ofDays(1).toSeconds();
    }

    // ------------------------------------------------------------------ pasajero

    /** POST /offers/{id}/queue: entra a la sala (una vez por usuario). */
    public JoinResult join(UUID offerId, UUID userId) {
        Offer offer = visibleOffer(offerId);
        if (!offer.isSalaEsperaHabilitada()) {
            throw ApiException.conflict("QUEUE_NOT_ENABLED", "Esta oferta no tiene sala de espera");
        }
        Instant now = clock.instant();
        if (closedOffer(offer, now)) {
            throw ApiException.conflict("QUEUE_CLOSED", "La sala de espera de esta oferta está cerrada");
        }
        Instant opensAt = offer.getInicio().minus(properties.opensBefore());
        if (now.isBefore(opensAt)) {
            throw new ApiException(HttpStatus.CONFLICT, "QUEUE_NOT_OPEN",
                    "La sala de espera abre " + properties.opensBefore().toMinutes() + " minutos antes del inicio",
                    Map.of("abreEn", opensAt.toString()));
        }
        Long result = redisCall(() -> redis.execute(JOIN, List.of(queueKey(offerId), enteredKey(offerId),
                seqKey(offerId), drawnKey(offerId), closedKey(offerId)), userId.toString(),
                String.valueOf(keysTtlSeconds(offer))));
        if (result != null && result == -2) {
            throw ApiException.conflict("QUEUE_CLOSED", "La sala de espera de esta oferta está cerrada");
        }
        return new JoinResult(status(offerId, userId), result != null && result == 1);
    }

    /** GET /offers/{id}/queue/me: estado y posición del pasajero. */
    public QueueStatus status(UUID offerId, UUID userId) {
        visibleOffer(offerId);
        return redisCall(() -> {
            String user = userId.toString();
            long enFila = zero(redis.opsForZSet().zCard(queueKey(offerId)));
            Long ttlMs = redis.getExpire(tokenKey(offerId, userId), TimeUnit.MILLISECONDS);
            if (ttlMs != null && ttlMs > 0) {
                return new QueueStatus(offerId, QueueState.ADMITIDO, null, enFila, clock.instant().plusMillis(ttlMs));
            }
            if (Boolean.TRUE.equals(redis.opsForSet().isMember(admittedKey(offerId), user))) {
                return new QueueStatus(offerId, QueueState.TURNO_VENCIDO, null, enFila, null);
            }
            if (Boolean.TRUE.equals(redis.hasKey(closedKey(offerId)))) {
                return new QueueStatus(offerId, QueueState.CERRADA, null, 0, null);
            }
            Long rank = redis.opsForZSet().rank(queueKey(offerId), user);
            if (rank == null) {
                return new QueueStatus(offerId, QueueState.NO_INSCRITO, null, enFila, null);
            }
            if (!Boolean.TRUE.equals(redis.hasKey(drawnKey(offerId)))) {
                // Antes del sorteo no hay posición: el orden de llegada no da ventaja.
                return new QueueStatus(offerId, QueueState.EN_SALA, null, enFila, null);
            }
            return new QueueStatus(offerId, QueueState.EN_FILA, rank + 1, enFila, null);
        });
    }

    /** Reservar con sala de espera exige un token de admisión vigente. */
    public boolean hasAdmission(UUID offerId, UUID userId) {
        return Boolean.TRUE.equals(redisCall(() -> redis.hasKey(tokenKey(offerId, userId))));
    }

    // ------------------------------------------------------------------ lotes y cierre

    /**
     * Un lote de la oferta (la llama {@link WaitingRoomScheduler} en un solo nodo): si la oferta ya inició y aún no
     * se sorteó, sortea; luego admite hasta {@code batch-size} personas y publica OfferQueueAdmitted por cada una y
     * OfferQueueUpdated con el estado de la fila.
     */
    public Batch processBatch(UUID offerId) {
        Offer offer = offers.findById(offerId).orElse(null);
        if (offer == null || !offer.isSalaEsperaHabilitada() || offer.getEstado() != OfferStatus.ACTIVA) {
            return new Batch(offerId, List.of(), 0, 0);
        }
        String ttl = String.valueOf(keysTtlSeconds(offer));
        Long drawn = redis.execute(DRAW, List.of(queueKey(offerId), drawnKey(offerId)),
                String.valueOf(random.nextInt(Integer.MAX_VALUE)), ttl);
        if (drawn != null && drawn >= 0) {
            log.info("Sala de espera de la oferta {}: sorteado el orden de {} personas", offerId, drawn);
        }
        List<String> popped = redis.execute(ADMIT, List.of(queueKey(offerId), admittedKey(offerId),
                        admittedCountKey(offerId), closedKey(offerId)), String.valueOf(properties.batchSize()),
                tokenPrefix(offerId), String.valueOf(properties.admissionTtl().toSeconds()), ttl);
        Instant expiraEn = clock.instant().plus(properties.admissionTtl());
        List<UUID> admitted = new ArrayList<>();
        for (String user : popped != null ? popped : List.<String>of()) {
            UUID userId = UUID.fromString(user);
            admitted.add(userId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", userId);
            payload.put("offerId", offerId);
            payload.put("expiraEn", expiraEn);
            events.publishSafely(OfferEvents.OFFER_QUEUE_ADMITTED, payload);
        }
        long enFila = zero(redis.opsForZSet().zCard(queueKey(offerId)));
        long total = parse(redis.opsForValue().get(admittedCountKey(offerId)));
        publishUpdated(offerId, enFila, total);
        return new Batch(offerId, admitted, enFila, total);
    }

    /** Cierra la sala (oferta agotada o terminada): nadie más entra ni se admite. Idempotente y sin lanzar. */
    public void close(UUID offerId) {
        try {
            Boolean first = redis.opsForValue().setIfAbsent(closedKey(offerId), "1", Duration.ofDays(1));
            redis.delete(queueKey(offerId));
            if (Boolean.TRUE.equals(first)) {
                publishUpdated(offerId, 0, parse(redis.opsForValue().get(admittedCountKey(offerId))));
                log.info("Sala de espera de la oferta {} cerrada", offerId);
            }
        } catch (DataAccessException e) {
            log.warn("No se pudo cerrar la sala de espera de la oferta {}: {}", offerId, e.getMessage());
        }
    }

    private void publishUpdated(UUID offerId, long enFila, long admitidos) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("offerId", offerId);
        payload.put("enFila", enFila);
        payload.put("admitidos", admitidos);
        events.publishSafely(OfferEvents.OFFER_QUEUE_UPDATED, payload);
    }

    // ------------------------------------------------------------------ utilidades

    private Offer visibleOffer(UUID offerId) {
        return offers.findById(offerId).filter(o -> !o.borrador())
                .orElseThrow(() -> ApiException.notFound("OFFER_NOT_FOUND", "La oferta no existe"));
    }

    private static boolean closedOffer(Offer offer, Instant now) {
        return offer.getEstado() == OfferStatus.CERRADA || offer.getEstado() == OfferStatus.AGOTADA
                || !now.isBefore(offer.getFin());
    }

    private static <T> T redisCall(Supplier<T> call) {
        try {
            return call.get();
        } catch (DataAccessException e) {
            throw ApiException.unavailable("QUEUE_UNAVAILABLE", "La sala de espera no está disponible");
        }
    }

    private static long zero(Long value) {
        return value != null ? value : 0;
    }

    private static long parse(String value) {
        return value != null ? Long.parseLong(value) : 0;
    }
}
