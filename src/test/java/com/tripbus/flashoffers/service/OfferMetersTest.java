package com.tripbus.flashoffers.service;

import com.tripbus.flashoffers.domain.OfferSource;
import com.tripbus.flashoffers.domain.OfferStatus;
import com.tripbus.flashoffers.repository.OfferRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OfferMetersTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OfferRepository offers = mock(OfferRepository.class);
    private final QuotaCounter quota = mock(QuotaCounter.class);
    private final OfferMeters meters = new OfferMeters(registry, offers, quota);

    private double reservas(String resultado) {
        return registry.get(OfferMeters.RESERVATIONS).tag("resultado", resultado).counter().count();
    }

    @Test
    void lasSeriesExistenEnCeroDesdeElArranque() {
        for (OfferMeters.ReservationResult r : OfferMeters.ReservationResult.values()) {
            assertThat(reservas(r.tag())).isZero();
        }
        assertThat(registry.get(OfferMeters.ACTIVATED).tag("origen", "AUTOMATICA").counter().count()).isZero();
    }

    @Test
    void cuentaCadaResultadoYCadaActivacionPorOrigen() {
        meters.reservation(OfferMeters.ReservationResult.OK);
        meters.reservation(OfferMeters.ReservationResult.OK);
        meters.reservation(OfferMeters.ReservationResult.SIN_TURNO);
        meters.activated(OfferSource.AUTOMATICA);
        meters.activated(null);
        assertThat(reservas("ok")).isEqualTo(2);
        assertThat(reservas("sin_turno")).isEqualTo(1);
        assertThat(reservas("agotada")).isZero();
        assertThat(registry.get(OfferMeters.ACTIVATED).tag("origen", "AUTOMATICA").counter().count()).isEqualTo(1);
        assertThat(registry.get(OfferMeters.ACTIVATED).tag("origen", "MANUAL").counter().count()).isEqualTo(1);
    }

    @Test
    void elGaugeTieneUnaSeriePorOfertaActivaYQuitaLasQueYaNoLoSon() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID sinContador = UUID.randomUUID();
        when(offers.findIdsByEstado(OfferStatus.ACTIVA)).thenReturn(List.of(a, b, sinContador));
        when(quota.remaining(a)).thenReturn(7);
        when(quota.remaining(b)).thenReturn(-1);
        when(quota.remaining(sinContador)).thenReturn(null);
        meters.refreshRemaining();
        assertThat(registry.get(OfferMeters.REMAINING).tag("offerId", a.toString()).gauge().value()).isEqualTo(7);
        assertThat(registry.get(OfferMeters.REMAINING).tag("offerId", b.toString()).gauge().value()).isZero();
        assertThat(registry.find(OfferMeters.REMAINING).tag("offerId", sinContador.toString()).gauge()).isNull();

        when(offers.findIdsByEstado(OfferStatus.ACTIVA)).thenReturn(List.of(b));
        meters.refreshRemaining();
        assertThat(registry.find(OfferMeters.REMAINING).tag("offerId", a.toString()).gauge()).isNull();
    }

    @Test
    void siRedisFallaElGaugeConservaLaUltimaLectura() {
        UUID a = UUID.randomUUID();
        when(offers.findIdsByEstado(OfferStatus.ACTIVA)).thenReturn(List.of(a));
        when(quota.remaining(a)).thenReturn(3).thenThrow(new DataAccessResourceFailureException("caído"));
        meters.refreshRemaining();
        meters.refreshRemaining();
        assertThat(registry.get(OfferMeters.REMAINING).tag("offerId", a.toString()).gauge().value()).isEqualTo(3);
    }
}
