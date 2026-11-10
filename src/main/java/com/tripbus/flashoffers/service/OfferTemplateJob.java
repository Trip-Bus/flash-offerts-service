package com.tripbus.flashoffers.service;

import com.tripbus.flashoffers.client.CatalogClient;
import com.tripbus.flashoffers.client.SeatInventoryClient;
import com.tripbus.flashoffers.common.error.ApiException;
import com.tripbus.flashoffers.common.redis.LeaderLock;
import com.tripbus.flashoffers.domain.OfferSource;
import com.tripbus.flashoffers.domain.OfferTemplate;
import com.tripbus.flashoffers.repository.OfferRepository;
import com.tripbus.flashoffers.repository.OfferTemplateRepository;
import com.tripbus.flashoffers.web.dto.CreateOfferRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * US-169: una vez al día ({@code tripbus.jobs.offer-templates-cron}), en un solo nodo, crea con cada plantilla activa
 * las ofertas de los próximos 7 días para los viajes de su ruta que salen en sus días de la semana (zona de la
 * plataforma), con el mismo flujo y las validaciones de crear (origen RECURRENTE). La oferta empieza a la
 * {@code horaInicio} del día que queda {@code horasAntesSalida} horas antes de la salida y dura
 * {@code duracionMinutos}. Es idempotente: no repite la oferta de una misma plantilla y viaje (también lo garantiza un
 * índice único).
 */
@Component
public class OfferTemplateJob {

    private static final Logger log = LoggerFactory.getLogger(OfferTemplateJob.class);
    static final Duration HORIZON = Duration.ofDays(7);

    private final OfferTemplateRepository templates;
    private final OfferRepository offers;
    private final OfferService offerService;
    private final CatalogClient catalog;
    private final SeatInventoryClient seatInventory;
    private final LeaderLock leaderLock;
    private final Clock clock;
    private final ZoneId zone;

    @SuppressWarnings("java:S107") // colaboradores del job; agruparlos solo escondería las dependencias
    public OfferTemplateJob(OfferTemplateRepository templates, OfferRepository offers, OfferService offerService,
                            CatalogClient catalog, SeatInventoryClient seatInventory, LeaderLock leaderLock,
                            Clock clock, @Value("${tripbus.offers.zone:America/Bogota}") ZoneId zone) {
        this.templates = templates;
        this.offers = offers;
        this.offerService = offerService;
        this.catalog = catalog;
        this.seatInventory = seatInventory;
        this.leaderLock = leaderLock;
        this.clock = clock;
        this.zone = zone;
    }

    @Scheduled(cron = "${tripbus.jobs.offer-templates-cron:0 0 3 * * *}", zone = "${tripbus.offers.zone:America/Bogota}")
    public void tick() {
        leaderLock.runIfLeader("flash-offers-offer-templates", Duration.ofMinutes(30), this::runNow);
    }

    /** Aplica todas las plantillas activas; devuelve cuántas ofertas creó. */
    public int runNow() {
        int created = 0;
        for (OfferTemplate template : templates.findByActivaTrueOrderByCreadoEnAsc()) {
            try {
                created += apply(template);
            } catch (RuntimeException e) {
                log.warn("No se pudo aplicar la plantilla {}: {}", template.getId(), e.getMessage());
            }
        }
        return created;
    }

    int apply(OfferTemplate template) {
        Instant now = clock.instant();
        Set<DayOfWeek> dias = template.dias();
        int created = 0;
        for (CatalogClient.Trip trip : catalog.trips(template.getCompanyId(), template.getRouteId(), now,
                now.plus(HORIZON))) {
            if (GeneratedOffers.available(trip, now)
                    && dias.contains(trip.fechaSalida().atZone(zone).getDayOfWeek())
                    && tryCreate(template, trip, now)) {
                created++;
            }
        }
        return created;
    }

    /** Inicio de la oferta del viaje: la hora de inicio del día que queda horasAntesSalida antes de la salida. */
    Instant startFor(OfferTemplate template, Instant salida) {
        LocalDate day = LocalDate.ofInstant(salida.minus(Duration.ofHours(template.getHorasAntesSalida())), zone);
        return day.atTime(template.getHoraInicio()).atZone(zone).toInstant();
    }

    private boolean tryCreate(OfferTemplate template, CatalogClient.Trip trip, Instant now) {
        if (offers.existsByTemplateIdAndItineraryId(template.getId(), trip.itineraryId())) {
            return false;
        }
        Instant inicio = startFor(template, trip.fechaSalida());
        Instant fin = inicio.plus(Duration.ofMinutes(template.getDuracionMinutos()));
        if (inicio.isBefore(now) || fin.isAfter(trip.fechaSalida())) {
            log.debug("Plantilla {}: la ventana {} - {} no cabe para el viaje {}", template.getId(), inicio, fin,
                    trip.itineraryId());
            return false;
        }
        List<String> seats = GeneratedOffers.seats(seatInventory.occupancy(trip.itineraryId()), template.getCupo());
        if (seats.isEmpty()) {
            return false;
        }
        CreateOfferRequest request = new CreateOfferRequest(trip.itineraryId(), seats,
                GeneratedOffers.price(trip.precioBase(), template.getDescuento()), seats.size(), inicio, fin,
                Math.min(template.getMaxPorUsuario(), seats.size()), template.isPermiteReprogramar(),
                template.isSalaEspera());
        try {
            var offer = offerService.createGenerated(request, template.getCompanyId(), OfferSource.RECURRENTE,
                    template.getId());
            log.info("Oferta recurrente {} creada para el viaje {} (plantilla {})", offer.offerId(), trip.itineraryId(),
                    template.getId());
            return true;
        } catch (ApiException e) {
            log.info("Plantilla {}: no se creó la oferta del viaje {}: {} ({})", template.getId(), trip.itineraryId(),
                    e.getMessage(), e.getCode());
            return false;
        }
    }
}
