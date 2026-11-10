package com.tripbus.flashoffers.service;

import com.tripbus.flashoffers.client.CatalogClient;
import com.tripbus.flashoffers.client.SeatInventoryClient;
import com.tripbus.flashoffers.common.error.ApiException;
import com.tripbus.flashoffers.common.redis.LeaderLock;
import com.tripbus.flashoffers.domain.AutoOfferRule;
import com.tripbus.flashoffers.domain.OfferSource;
import com.tripbus.flashoffers.domain.OfferStatus;
import com.tripbus.flashoffers.domain.OfferTemplate;
import com.tripbus.flashoffers.repository.AutoOfferRuleRepository;
import com.tripbus.flashoffers.repository.OfferRepository;
import com.tripbus.flashoffers.repository.OfferTemplateRepository;
import com.tripbus.flashoffers.web.dto.OfferResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ramas de los jobs de ofertas automáticas (US-167) y recurrentes (US-169) con colaboradores simulados. */
class GenerationJobsTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final OfferRepository offers = mock(OfferRepository.class);
    private final OfferService offerService = mock(OfferService.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final SeatInventoryClient seats = mock(SeatInventoryClient.class);
    private final LeaderLock lock = mock(LeaderLock.class);
    private final UUID company = UUID.randomUUID();

    private static OfferResponse creada() {
        return new OfferResponse(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE, 1, 1, 1, NOW, NOW, "PROGRAMADA",
                0, List.of("1"), null);
    }

    private CatalogClient.Trip trip(Instant salida) {
        return new CatalogClient.Trip(UUID.randomUUID(), company, null, new BigDecimal("100"), "PROGRAMADO", salida);
    }

    private void libres(CatalogClient.Trip trip, int capacidad, List<String> libres) {
        when(seats.occupancy(trip.itineraryId())).thenReturn(new SeatInventoryClient.Occupancy(trip.itineraryId(),
                capacidad, capacidad - libres.size(), libres));
    }

    // ------------------------------------------------------------------ US-167

    private AutoOfferRule rule() {
        AutoOfferRule rule = new AutoOfferRule(company, NOW);
        rule.configurar(null, 24, new BigDecimal("50"), new BigDecimal("20"), 2, true, NOW);
        return rule;
    }

    private AutoOfferJob autoJob(AutoOfferRuleRepository rules) {
        return new AutoOfferJob(rules, offers, offerService, catalog, seats, lock, clock, 900_000);
    }

    @Test
    void elJobAutomaticoSaltaLosViajesQueNoCalificanYSigueConLosDemas() {
        AutoOfferRuleRepository rules = mock(AutoOfferRuleRepository.class);
        AutoOfferRule rule = rule();
        AutoOfferRule rota = rule();
        when(rules.findByActivaTrueOrderByCreadoEnAsc()).thenReturn(List.of(rota, rule));
        CatalogClient.Trip conOferta = trip(NOW.plusSeconds(7200));
        CatalogClient.Trip muyPronto = trip(NOW.plusSeconds(1800));
        CatalogClient.Trip sinOcupacion = trip(NOW.plusSeconds(7200));
        CatalogClient.Trip sinLibres = trip(NOW.plusSeconds(7200));
        CatalogClient.Trip rechazado = trip(NOW.plusSeconds(7200));
        CatalogClient.Trip bueno = trip(NOW.plusSeconds(7200));
        CatalogClient.Trip yaSalio = trip(NOW.minusSeconds(60));
        when(catalog.trips(eq(company), any(), any(), any()))
                .thenThrow(ApiException.unavailable("CATALOG_UNAVAILABLE", "caído"))
                .thenReturn(List.of(conOferta, muyPronto, sinOcupacion, sinLibres, rechazado, bueno, yaSalio));
        when(offers.existsByItineraryIdAndEstadoNot(conOferta.itineraryId(), OfferStatus.CERRADA)).thenReturn(true);
        libres(sinLibres, 0, List.of());
        libres(rechazado, 4, List.of("1", "2", "3"));
        libres(bueno, 4, List.of("4", "5", "6"));
        when(offerService.createGenerated(any(), eq(company), eq(OfferSource.AUTOMATICA), any()))
                .thenThrow(ApiException.conflict("OFFER_OVERLAP", "cruce"))
                .thenReturn(creada());

        assertThat(autoJob(rules).runNow()).isEqualTo(1);
        verify(seats, never()).occupancy(conOferta.itineraryId());
        verify(seats, never()).occupancy(muyPronto.itineraryId());
        verify(seats, never()).occupancy(yaSalio.itineraryId());
        verify(offerService).createGenerated(org.mockito.ArgumentMatchers.argThat(r ->
                r.itineraryId().equals(bueno.itineraryId()) && r.seatNumbers().equals(List.of("4", "5"))
                        && r.cupo() == 2 && r.precioOferta().compareTo(new BigDecimal("80")) == 0
                        && r.inicio().equals(NOW.plus(AutoOfferJob.START_DELAY))
                        && r.fin().equals(bueno.fechaSalida().minus(AutoOfferJob.CLOSE_BEFORE_DEPARTURE))),
                eq(company), eq(OfferSource.AUTOMATICA), eq(null));
    }

    @Test
    void elTickDelJobAutomaticoCorreConElLock() {
        AutoOfferJob job = autoJob(mock(AutoOfferRuleRepository.class));
        job.tick();
        verify(lock).runIfLeader(eq("flash-offers-auto-offers"), eq(Duration.ofMinutes(12)), any());
    }

    // ------------------------------------------------------------------ US-169

    private OfferTemplate template(int horasAntes) {
        OfferTemplate t = new OfferTemplate(company, NOW);
        t.configurar(UUID.randomUUID(), new OfferTemplate.Horario(List.of(DayOfWeek.values()), LocalTime.of(10, 0),
                60, horasAntes), new OfferTemplate.Condiciones(new BigDecimal("10"), 3, 2, true, false), true, NOW);
        return t;
    }

    private OfferTemplateJob templateJob(OfferTemplateRepository repo) {
        return new OfferTemplateJob(repo, offers, offerService, catalog, seats, lock, clock, ZoneId.of("UTC"));
    }

    @Test
    void elJobDePlantillasSaltaLoQueNoCabeYSigueConLasDemas() {
        OfferTemplateRepository repo = mock(OfferTemplateRepository.class);
        OfferTemplate rota = template(24);
        OfferTemplate t = template(24);
        when(repo.findByActivaTrueOrderByCreadoEnAsc()).thenReturn(List.of(rota, t));
        CatalogClient.Trip yaCreada = trip(Instant.parse("2026-11-08T08:00:00Z"));
        CatalogClient.Trip noCabe = trip(Instant.parse("2026-11-05T20:00:00Z")); // 24 h antes = ayer
        CatalogClient.Trip sinLibres = trip(Instant.parse("2026-11-08T08:00:00Z"));
        CatalogClient.Trip rechazado = trip(Instant.parse("2026-11-09T08:00:00Z"));
        CatalogClient.Trip bueno = trip(Instant.parse("2026-11-10T08:00:00Z"));
        when(catalog.trips(eq(company), any(), any(), any()))
                .thenThrow(ApiException.unavailable("CATALOG_UNAVAILABLE", "caído"))
                .thenReturn(List.of(yaCreada, noCabe, sinLibres, rechazado, bueno));
        when(offers.existsByTemplateIdAndItineraryId(t.getId(), yaCreada.itineraryId())).thenReturn(true);
        libres(sinLibres, 10, List.of());
        libres(rechazado, 10, List.of("1"));
        libres(bueno, 10, List.of("1", "2", "3", "4"));
        when(offerService.createGenerated(any(), eq(company), eq(OfferSource.RECURRENTE), eq(t.getId())))
                .thenThrow(ApiException.conflict("OFFER_OVERLAP", "cruce"))
                .thenReturn(creada());

        assertThat(templateJob(repo).runNow()).isEqualTo(1);
        verify(seats, never()).occupancy(yaCreada.itineraryId());
        verify(seats, never()).occupancy(noCabe.itineraryId());
        verify(offerService).createGenerated(org.mockito.ArgumentMatchers.argThat(r ->
                r.itineraryId().equals(bueno.itineraryId()) && r.cupo() == 3 && r.maxPorUsuario() == 2
                        && r.inicio().equals(Instant.parse("2026-11-09T10:00:00Z"))
                        && r.precioOferta().compareTo(new BigDecimal("90")) == 0),
                eq(company), eq(OfferSource.RECURRENTE), eq(t.getId()));
    }

    @Test
    void laOfertaRecurrenteNoPuedeTerminarDespuesDeLaSalida() {
        OfferTemplateJob job = templateJob(mock(OfferTemplateRepository.class));
        OfferTemplate sinAntelacion = template(0);
        // Sale a las 10:30: la oferta de 10:00 a 11:00 de ese día termina después de la salida.
        Instant salida = Instant.parse("2026-11-07T10:30:00Z");
        assertThat(job.startFor(sinAntelacion, salida)).isEqualTo(Instant.parse("2026-11-07T10:00:00Z"));
        when(catalog.trips(eq(company), any(), any(), any())).thenReturn(List.of(trip(salida)));
        assertThat(job.apply(sinAntelacion)).isZero();
        verify(offerService, never()).createGenerated(any(), any(), any(), any());
    }

    @Test
    void elTickDePlantillasCorreConElLock() {
        templateJob(mock(OfferTemplateRepository.class)).tick();
        verify(lock).runIfLeader(eq("flash-offers-offer-templates"), eq(Duration.ofMinutes(30)), any());
    }

    @Test
    void calculosComunes() {
        assertThat(GeneratedOffers.price(new BigDecimal("95000"), new BigDecimal("33"))).isEqualByComparingTo("63650");
        assertThat(GeneratedOffers.seats(null, 3)).isEmpty();
        assertThat(GeneratedOffers.available(null, NOW)).isFalse();
        assertThat(GeneratedOffers.available(new CatalogClient.Trip(UUID.randomUUID(), company, null, null,
                "PROGRAMADO", NOW.plusSeconds(60)), NOW)).isFalse();
        assertThat(GeneratedOffers.available(new CatalogClient.Trip(UUID.randomUUID(), company, null, BigDecimal.TEN,
                "PROGRAMADO", null), NOW)).isFalse();
    }
}
