package com.tripbus.flashoffers.service;

import com.tripbus.flashoffers.common.error.TooManyRequestsException;
import com.tripbus.flashoffers.common.events.EventPublisher;
import com.tripbus.flashoffers.common.redis.LeaderLock;
import com.tripbus.flashoffers.config.WaitingRoomProperties;
import com.tripbus.flashoffers.domain.Offer;
import com.tripbus.flashoffers.domain.OfferStatus;
import com.tripbus.flashoffers.repository.OfferRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-162 y US-168: ramas con Redis caído, scheduler de lotes y propiedades por defecto. */
class WaitingRoomUnitTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final OfferRepository offers = mock(OfferRepository.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final WaitingRoomProperties props = new WaitingRoomProperties(null, 0, Duration.ZERO, Duration.ofMinutes(-1));
    private final WaitingRoomService service = new WaitingRoomService(redis, offers, events, props, clock);

    private Offer ofertaConSala(OfferStatus estado) {
        Offer offer = new Offer(UUID.randomUUID(), new BigDecimal("50"), 2, NOW.plusSeconds(60), NOW.plusSeconds(3600),
                UUID.randomUUID(), NOW);
        offer.asignarAsientos(List.of("1", "2"));
        offer.programar();
        offer.origen(true, null, null);
        if (estado == OfferStatus.ACTIVA) {
            offer.activar(NOW);
        } else if (estado == OfferStatus.CERRADA) {
            offer.cerrar("EXPIRADA");
        }
        when(offers.findById(offer.getId())).thenReturn(Optional.of(offer));
        return offer;
    }

    @Test
    void lasPropiedadesTienenValoresPorDefecto() {
        assertThat(props.batchInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(props.batchSize()).isEqualTo(50);
        assertThat(props.admissionTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.opensBefore()).isEqualTo(Duration.ofMinutes(10));
        WaitingRoomProperties custom = new WaitingRoomProperties(Duration.ofSeconds(3), 7, Duration.ofMinutes(1),
                Duration.ofMinutes(2));
        assertThat(custom.batchSize()).isEqualTo(7);
        assertThat(custom.batchInterval()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void conRedisCaidoLaSalaRespondeNoDisponible() {
        Offer offer = ofertaConSala(OfferStatus.PROGRAMADA);
        when(redis.hasKey(anyString())).thenThrow(new DataAccessResourceFailureException("caído"));
        when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("caído"));
        UUID user = UUID.randomUUID();
        assertThatThrownBy(() -> service.hasAdmission(offer.getId(), user)).extracting("code")
                .isEqualTo("QUEUE_UNAVAILABLE");
        assertThatThrownBy(() -> service.join(offer.getId(), user)).extracting("code").isEqualTo("QUEUE_UNAVAILABLE");
    }

    @Test
    void unaOfertaCerradaNoAdmiteEntradasNiLotes() {
        Offer cerrada = ofertaConSala(OfferStatus.CERRADA);
        UUID user = UUID.randomUUID();
        assertThatThrownBy(() -> service.join(cerrada.getId(), user)).extracting("code").isEqualTo("QUEUE_CLOSED");
        assertThat(service.processBatch(cerrada.getId()).admitidos()).isEmpty();
        assertThat(service.processBatch(UUID.randomUUID()).admitidos()).isEmpty();
    }

    @Test
    void cerrarConRedisCaidoNoLanza() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new DataAccessResourceFailureException("caído"));
        service.close(UUID.randomUUID());
        verify(values).setIfAbsent(anyString(), eq("1"), any(Duration.class));
    }

    @Test
    void elSchedulerAdmiteLotesCierraLasAgotadasYSigueSiUnaFalla() {
        WaitingRoomService room = mock(WaitingRoomService.class);
        LeaderLock lock = mock(LeaderLock.class);
        UUID activa = UUID.randomUUID();
        UUID falla = UUID.randomUUID();
        UUID agotada = UUID.randomUUID();
        when(offers.findIdsWithWaitingRoomByEstado(OfferStatus.ACTIVA)).thenReturn(List.of(falla, activa));
        when(offers.findIdsWithWaitingRoomByEstado(OfferStatus.AGOTADA)).thenReturn(List.of(agotada));
        doThrow(new IllegalStateException("redis")).when(room).processBatch(falla);
        WaitingRoomScheduler scheduler = new WaitingRoomScheduler(offers, room, lock, props);
        scheduler.runNow();
        verify(room).processBatch(activa);
        verify(room).close(agotada);
        scheduler.tick();
        verify(lock).runIfLeader(eq("flash-offers-waiting-room"), eq(Duration.ofSeconds(8)), any());
    }

    @Test
    void elLimiteDeIntentosNoBloqueaSiRedisCae() {
        when(redis.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new DataAccessResourceFailureException("caído"));
        ReservationRateLimiter limiter = new ReservationRateLimiter(redis, clock, 1);
        limiter.check(UUID.randomUUID());
        assertThat(ReservationRateLimiter.key(UUID.fromString("00000000-0000-0000-0000-000000000001"), 5))
                .isEqualTo("ratelimit:offer-reservations:00000000-0000-0000-0000-000000000001:5");
    }

    @Test
    void retryAfterVaHastaElSiguienteMinuto() {
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(2L);
        ReservationRateLimiter limiter = new ReservationRateLimiter(redis,
                Clock.fixed(Instant.parse("2026-11-05T12:00:45Z"), ZoneOffset.UTC), 1);
        UUID user = UUID.randomUUID();
        assertThatThrownBy(() -> limiter.check(user)).isInstanceOf(TooManyRequestsException.class)
                .extracting("retryAfterSeconds").isEqualTo(15L);
    }
}
