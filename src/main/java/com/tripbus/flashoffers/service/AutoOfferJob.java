package com.tripbus.flashoffers.service;

import com.tripbus.flashoffers.client.CatalogClient;
import com.tripbus.flashoffers.client.SeatInventoryClient;
import com.tripbus.flashoffers.common.error.ApiException;
import com.tripbus.flashoffers.common.redis.LeaderLock;
import com.tripbus.flashoffers.domain.AutoOfferRule;
import com.tripbus.flashoffers.domain.OfferSource;
import com.tripbus.flashoffers.domain.OfferStatus;
import com.tripbus.flashoffers.repository.AutoOfferRuleRepository;
import com.tripbus.flashoffers.repository.OfferRepository;
import com.tripbus.flashoffers.web.dto.CreateOfferRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * US-167: cada {@code tripbus.jobs.auto-offers-ms} (15 min), en un solo nodo, aplica las reglas activas: con
 * catalog ({@code GET /internal/itineraries}) busca los viajes que salen dentro de {@code horasAntes}; con
 * seat-inventory ({@code GET /internal/occupancy}) los que tienen ocupación por debajo de {@code ocupacionMaxima}; y a
 * los que no tienen oferta les crea una (origen AUTOMATICA) con el descuento sobre los asientos libres, hasta
 * {@code cupoMaximo}, con el mismo flujo y las validaciones de crear. Una sola oferta automática por viaje.
 * La oferta empieza en un minuto y termina {@link #CLOSE_BEFORE_DEPARTURE} antes de la salida.
 */
@Component
public class AutoOfferJob {

    private static final Logger log = LoggerFactory.getLogger(AutoOfferJob.class);
    static final Duration START_DELAY = Duration.ofMinutes(1);
    static final Duration CLOSE_BEFORE_DEPARTURE = Duration.ofMinutes(30);

    private final AutoOfferRuleRepository rules;
    private final OfferRepository offers;
    private final OfferService offerService;
    private final CatalogClient catalog;
    private final SeatInventoryClient seatInventory;
    private final LeaderLock leaderLock;
    private final Clock clock;
    private final Duration window;

    @SuppressWarnings("java:S107") // colaboradores del job; agruparlos solo escondería las dependencias
    public AutoOfferJob(AutoOfferRuleRepository rules, OfferRepository offers, OfferService offerService,
                        CatalogClient catalog, SeatInventoryClient seatInventory, LeaderLock leaderLock, Clock clock,
                        @Value("${tripbus.jobs.auto-offers-ms:900000}") long intervalMs) {
        this.rules = rules;
        this.offers = offers;
        this.offerService = offerService;
        this.catalog = catalog;
        this.seatInventory = seatInventory;
        this.leaderLock = leaderLock;
        this.clock = clock;
        this.window = Duration.ofMillis(intervalMs).multipliedBy(4).dividedBy(5);
    }

    @Scheduled(fixedDelayString = "${tripbus.jobs.auto-offers-ms:900000}",
            initialDelayString = "${tripbus.jobs.auto-offers-ms:900000}")
    public void tick() {
        leaderLock.runIfLeader("flash-offers-auto-offers", window, this::runNow);
    }

    /** Aplica todas las reglas activas; devuelve cuántas ofertas creó. */
    public int runNow() {
        int created = 0;
        for (AutoOfferRule rule : rules.findByActivaTrueOrderByCreadoEnAsc()) {
            try {
                created += apply(rule);
            } catch (RuntimeException e) {
                log.warn("No se pudo aplicar la regla automática {}: {}", rule.getId(), e.getMessage());
            }
        }
        return created;
    }

    int apply(AutoOfferRule rule) {
        Instant now = clock.instant();
        List<CatalogClient.Trip> trips = catalog.trips(rule.getCompanyId(), rule.getRouteId(), now,
                now.plus(Duration.ofHours(rule.getHorasAntes())));
        int created = 0;
        for (CatalogClient.Trip trip : trips) {
            if (GeneratedOffers.available(trip, now) && tryCreate(rule, trip, now)) {
                created++;
            }
        }
        return created;
    }

    private boolean tryCreate(AutoOfferRule rule, CatalogClient.Trip trip, Instant now) {
        UUID itineraryId = trip.itineraryId();
        if (offers.existsByItineraryIdAndOrigenCreacion(itineraryId, OfferSource.AUTOMATICA)
                || offers.existsByItineraryIdAndEstadoNot(itineraryId, OfferStatus.CERRADA)) {
            return false;
        }
        Instant inicio = now.plus(START_DELAY);
        Instant fin = trip.fechaSalida().minus(CLOSE_BEFORE_DEPARTURE);
        if (Duration.between(inicio, fin).toMinutes() < 5) {
            return false;
        }
        SeatInventoryClient.Occupancy occupancy = seatInventory.occupancy(itineraryId);
        if (occupancy == null || occupancy.porcentaje() >= rule.getOcupacionMaxima().doubleValue()) {
            return false;
        }
        List<String> seats = GeneratedOffers.seats(occupancy, rule.getCupoMaximo());
        if (seats.isEmpty()) {
            return false;
        }
        CreateOfferRequest request = new CreateOfferRequest(itineraryId, seats,
                GeneratedOffers.price(trip.precioBase(), rule.getDescuento()), seats.size(), inicio, fin);
        try {
            var offer = offerService.createGenerated(request, rule.getCompanyId(), OfferSource.AUTOMATICA, null);
            log.info("Oferta automática {} creada para el viaje {} (ocupación {} %, regla {})", offer.offerId(),
                    itineraryId, Math.round(occupancy.porcentaje()), rule.getId());
            return true;
        } catch (ApiException e) {
            log.info("Regla {}: no se creó la oferta del viaje {}: {} ({})", rule.getId(), itineraryId,
                    e.getMessage(), e.getCode());
            return false;
        }
    }
}
