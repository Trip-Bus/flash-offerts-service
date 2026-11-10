package com.tripbus.flashoffers.service;

import com.tripbus.flashoffers.common.events.EventPublisher;
import com.tripbus.flashoffers.common.redis.LeaderLock;
import com.tripbus.flashoffers.domain.OfferStatus;
import com.tripbus.flashoffers.events.OfferEvents;
import com.tripbus.flashoffers.repository.OfferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuotaReconcilerTest {

    private final OfferRepository offerRepository = mock(OfferRepository.class);
    private final OfferService offerService = mock(OfferService.class);
    private final QuotaCounter quota = mock(QuotaCounter.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final LeaderLock leaderLock = mock(LeaderLock.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-12T15:00:00Z"), ZoneOffset.UTC);
    private final UUID offerId = UUID.randomUUID();
    private final List<String> libres = List.of("3", "4");

    private QuotaReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new QuotaReconciler(offerRepository, offerService, quota, events, leaderLock, clock, 60_000);
        when(offerRepository.findIdsByEstado(OfferStatus.ACTIVA)).thenReturn(List.of(offerId));
        when(offerService.expectedQuotaIfActive(offerId)).thenReturn(Optional.of(new OfferService.ExpectedQuota(2, libres)));
    }

    @Test
    void elTickCorreConElLockDeLiderYUnaVentanaMenorQueElIntervalo() {
        reconciler.tick();
        verify(leaderLock).runIfLeader(eq("flash-offers-quota-reconciler"), eq(Duration.ofSeconds(45)), any());
    }

    @Test
    void unDesajusteSoloSeCorrigeSiSeRepiteEnLaSiguienteRevision() {
        when(quota.remaining(offerId)).thenReturn(1);
        when(quota.lastMismatch(offerId)).thenReturn(null);

        reconciler.runNow();

        verify(quota).rememberMismatch(offerId, "2:1", Duration.ofMinutes(3));
        verify(quota, never()).correct(any(), anyInt(), anyInt(), any());

        when(quota.lastMismatch(offerId)).thenReturn("2:1");
        when(quota.correct(offerId, 1, 2, libres)).thenReturn(true);

        reconciler.runNow();

        verify(quota).forgetMismatch(offerId);
        verify(quota).correct(offerId, 1, 2, libres);
        verify(events).publishSafely(eq(OfferEvents.OFFER_QUOTA_UPDATED), argThat(p ->
                p instanceof Map<?, ?> m && offerId.equals(m.get("offerId")) && Integer.valueOf(2).equals(m.get("cupoRestante"))));
    }

    @Test
    void siElDesajusteCambioEntreRevisionesVuelveAEsperar() {
        when(quota.remaining(offerId)).thenReturn(0);
        when(quota.lastMismatch(offerId)).thenReturn("2:1");

        reconciler.runNow();

        verify(quota).rememberMismatch(offerId, "2:0", Duration.ofMinutes(3));
        verify(quota, never()).correct(any(), anyInt(), anyInt(), any());
    }

    @Test
    void siElContadorCambioJustoAntesDeCorregirNoSePublicaNada() {
        when(quota.remaining(offerId)).thenReturn(1);
        when(quota.lastMismatch(offerId)).thenReturn("2:1");
        when(quota.correct(offerId, 1, 2, libres)).thenReturn(false);

        reconciler.runNow();

        verify(events, never()).publishSafely(anyString(), any());
    }

    @Test
    void unContadorCorrectoBorraElDesajusteAnterior() {
        when(quota.remaining(offerId)).thenReturn(2);
        when(quota.lastMismatch(offerId)).thenReturn("2:1");

        reconciler.runNow();

        verify(quota).forgetMismatch(offerId);
        verify(quota, never()).rememberMismatch(any(), anyString(), any());
    }

    @Test
    void unContadorCorrectoSinDesajusteAnteriorNoEscribeEnRedis() {
        when(quota.remaining(offerId)).thenReturn(2);

        reconciler.runNow();

        verify(quota, never()).forgetMismatch(any());
        verify(quota, never()).rememberMismatch(any(), anyString(), any());
    }

    @Test
    void sinContadorOSiYaNoEstaActivaNoSeToca() {
        when(quota.remaining(offerId)).thenReturn(null);
        reconciler.runNow();
        verify(offerService, never()).expectedQuotaIfActive(any());

        when(quota.remaining(offerId)).thenReturn(5);
        when(offerService.expectedQuotaIfActive(offerId)).thenReturn(Optional.empty());
        reconciler.runNow();
        verify(quota, never()).lastMismatch(any());
    }

    @Test
    void unaOfertaQueFallaNoDetieneLaRevisionDeLasDemas() {
        UUID otra = UUID.randomUUID();
        when(offerRepository.findIdsByEstado(OfferStatus.ACTIVA)).thenReturn(List.of(otra, offerId));
        when(quota.remaining(otra)).thenThrow(new IllegalStateException("redis caído"));
        when(quota.remaining(offerId)).thenReturn(1);

        reconciler.runNow();

        verify(quota).rememberMismatch(offerId, "2:1", Duration.ofMinutes(3));
    }
}
