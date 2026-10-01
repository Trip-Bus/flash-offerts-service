package com.tripbus.flashoffers.service;

import com.tripbus.flashoffers.client.CatalogClient;
import com.tripbus.flashoffers.client.SeatInventoryClient;
import com.tripbus.flashoffers.common.error.ApiException;
import com.tripbus.flashoffers.common.events.EventPublisher;
import com.tripbus.flashoffers.domain.Offer;
import com.tripbus.flashoffers.domain.OfferSale;
import com.tripbus.flashoffers.domain.OfferStatus;
import com.tripbus.flashoffers.domain.SaleStatus;
import com.tripbus.flashoffers.events.OfferEvents;
import com.tripbus.flashoffers.repository.OfferRepository;
import com.tripbus.flashoffers.repository.OfferSaleRepository;
import com.tripbus.flashoffers.web.dto.CreateOfferRequest;
import com.tripbus.flashoffers.web.dto.OfferResponse;
import com.tripbus.flashoffers.web.dto.QuotaResponse;
import com.tripbus.flashoffers.web.dto.ReservationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Ofertas relámpago: CreateOffer, GetOfferStatus, GetRemainingQuota, ReserveOfferSeat,
 * ActivateOffer y CloseOffer del doc.
 */
@Service
public class OfferService {

    private static final Logger log = LoggerFactory.getLogger(OfferService.class);
    private static final Set<SaleStatus> VIVAS = Set.of(SaleStatus.RESERVADA, SaleStatus.VENDIDA);

    private final OfferRepository offerRepository;
    private final OfferSaleRepository saleRepository;
    private final QuotaCounter quota;
    private final SeatInventoryClient seatInventory;
    private final CatalogClient catalog;
    private final EventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OfferService(OfferRepository offerRepository, OfferSaleRepository saleRepository, QuotaCounter quota,
                        SeatInventoryClient seatInventory, CatalogClient catalog, EventPublisher events,
                        TransactionTemplate tx, Clock clock) {
        this.offerRepository = offerRepository;
        this.saleRepository = saleRepository;
        this.quota = quota;
        this.seatInventory = seatInventory;
        this.catalog = catalog;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ CreateOffer (US-054)

    public OfferResponse create(CreateOfferRequest request, UUID userId, boolean isOperator, UUID operatorCompanyId) {
        Instant now = clock.instant();
        List<String> seats = request.seatNumbers().stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (!request.fin().isAfter(request.inicio())) {
            throw ApiException.badRequest("INVALID_OFFER_WINDOW", "La hora de fin debe ser posterior a la de inicio");
        }
        if (request.cupo() > seats.size()) {
            throw ApiException.badRequest("QUOTA_EXCEEDS_SEATS", "El cupo no puede ser mayor que los asientos de la oferta");
        }
        CatalogClient.Itinerary itinerary = catalog.itinerary(request.itineraryId());
        if (!"PROGRAMADO".equals(itinerary.estado())) {
            throw ApiException.conflict("ITINERARY_NOT_AVAILABLE", "El viaje no está disponible");
        }
        if (itinerary.fechaSalida() != null && request.fin().isAfter(itinerary.fechaSalida())) {
            throw ApiException.badRequest("OFFER_ENDS_AFTER_DEPARTURE", "La oferta debe terminar antes de la salida del bus");
        }
        if (request.precioOferta().compareTo(itinerary.precioBase()) >= 0) {
            throw ApiException.badRequest("OFFER_PRICE_NOT_LOWER", "El precio de oferta debe ser menor al precio base");
        }
        // US-005: un operador solo puede crear ofertas para viajes de su propia empresa.
        if (isOperator && !Objects.equals(operatorCompanyId, itinerary.companyId())) {
            throw ApiException.forbidden("NOT_YOUR_COMPANY", "Solo puede crear ofertas para viajes de su empresa");
        }

        Offer draft = new Offer(request.itineraryId(), request.precioOferta(), request.cupo(),
                request.inicio().isBefore(now) ? now : request.inicio(), request.fin(), userId, now);
        tx.executeWithoutResult(s -> offerRepository.save(draft));

        SeatInventoryClient.OfferSeats reserved;
        try {
            reserved = seatInventory.reserveOfferSeats(draft.getId(), draft.getItineraryId(), seats);
        } catch (RuntimeException e) {
            tx.executeWithoutResult(s -> offerRepository.deleteById(draft.getId()));
            throw e;
        }
        if (reserved.reservados().isEmpty()) {
            tx.executeWithoutResult(s -> offerRepository.deleteById(draft.getId()));
            throw ApiException.conflict("NO_SEATS_AVAILABLE", "Ninguno de los asientos indicados está disponible");
        }

        tx.executeWithoutResult(s -> {
            Offer offer = offerRepository.lockById(draft.getId()).orElseThrow();
            offer.asignarAsientos(reserved.reservados());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("offerId", offer.getId());
            payload.put("itineraryId", offer.getItineraryId());
            payload.put("seatNumbers", reserved.reservados());
            payload.put("precioOferta", offer.getPrecioOferta());
            payload.put("cupoMax", offer.getCupoMax());
            payload.put("inicio", offer.getInicio());
            payload.put("fin", offer.getFin());
            events.publishAfterCommit(OfferEvents.OFFER_CREATED, payload);
        });
        if (!draft.getInicio().isAfter(now)) {
            tx.executeWithoutResult(s -> activate(draft.getId()));
        }
        return tx.execute(s -> get(draft.getId()));
    }

    // ------------------------------------------------------------------ Activate / Close

    /** ActivateOffer: inicializa el contador atómico en Redis y anuncia la oferta. */
    @Transactional
    public void activate(UUID offerId) {
        Offer offer = offerRepository.lockById(offerId)
                .orElseThrow(() -> ApiException.notFound("OFFER_NOT_FOUND", "La oferta no existe"));
        if (offer.getEstado() == OfferStatus.CERRADA) {
            throw ApiException.conflict("OFFER_CLOSED", "La oferta ya está cerrada");
        }
        if (offer.getEstado() != OfferStatus.PROGRAMADA) {
            return;
        }
        offer.activar(clock.instant());
        initializeCounter(offer);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("offerId", offer.getId());
        payload.put("itineraryId", offer.getItineraryId());
        payload.put("precioOferta", offer.getPrecioOferta());
        payload.put("fin", offer.getFin());
        payload.put("timestamp", clock.instant());
        events.publishAfterCommit(OfferEvents.OFFER_ACTIVATED, payload);
        events.publishAfterCommit(OfferEvents.OFFER_QUOTA_UPDATED, quotaPayload(offer.getId(), remaining(offer)));
    }

    /** CloseOffer: cierra la oferta; Seat Inventory devuelve a la venta normal los asientos no vendidos. */
    @Transactional
    public void close(UUID offerId, String motivo) {
        Offer offer = offerRepository.lockById(offerId)
                .orElseThrow(() -> ApiException.notFound("OFFER_NOT_FOUND", "La oferta no existe"));
        if (offer.getEstado() == OfferStatus.CERRADA) {
            return;
        }
        offer.cerrar(motivo);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("offerId", offer.getId());
        payload.put("itineraryId", offer.getItineraryId());
        payload.put("motivo", motivo);
        events.publishAfterCommit(OfferEvents.OFFER_CLOSED, payload);
        try {
            quota.delete(offerId);
        } catch (DataAccessException e) {
            log.warn("No se pudo borrar el contador de la oferta {}: {}", offerId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ ReserveOfferSeat (US-058)

    public record ReserveResult(ReservationResponse reservation, boolean created) {
    }

    public ReserveResult reserve(UUID offerId, UUID userId, String docId) {
        Offer offer = offerRepository.findById(offerId)
                .orElseThrow(() -> ApiException.notFound("OFFER_NOT_FOUND", "La oferta no existe"));
        var existing = saleRepository.findByOfferIdAndUserIdAndDocId(offerId, userId, docId);
        if (existing.isPresent() && existing.get().viva()) {
            return new ReserveResult(toReservation(existing.get(), offer), false);
        }
        Instant now = clock.instant();
        if (offer.getEstado() == OfferStatus.AGOTADA) {
            throw ApiException.conflict("OUT_OF_STOCK", "La oferta se agotó");
        }
        if (offer.getEstado() != OfferStatus.ACTIVA || !offer.vigente(now)) {
            throw ApiException.conflict("OFFER_NOT_ACTIVE", "La oferta no está activa");
        }

        String seat;
        try {
            if (!quota.isInitialized(offerId)) {
                tx.executeWithoutResult(s -> initializeCounter(offerRepository.findById(offerId).orElseThrow()));
            }
            seat = quota.reserve(offerId);
        } catch (DataAccessException e) {
            throw ApiException.unavailable("QUOTA_UNAVAILABLE", "El contador de cupo no está disponible");
        }
        if (seat == null) {
            markSoldOut(offerId);
            throw ApiException.conflict("OUT_OF_STOCK", "La oferta se agotó");
        }

        SeatInventoryClient.Hold hold;
        try {
            hold = seatInventory.hold(offer.getItineraryId(), seat, userId, offerId);
        } catch (RuntimeException e) {
            giveBackQuietly(offerId, seat);
            throw e;
        }

        OfferSale sale;
        try {
            sale = tx.execute(s -> {
                var previous = saleRepository.findByOfferIdAndUserIdAndDocId(offerId, userId, docId);
                if (previous.isPresent()) {
                    OfferSale p = previous.get();
                    if (p.viva()) {
                        throw new DataIntegrityViolationException("reserva concurrente de la misma persona");
                    }
                    p.reservarDeNuevo(seat, hold.holdId(), clock.instant());
                    return p;
                }
                return saleRepository.saveAndFlush(new OfferSale(offerId, userId, docId, seat, hold.holdId(),
                        clock.instant()));
            });
        } catch (DataIntegrityViolationException e) {
            // La misma persona reservó en paralelo: se deshace esta segunda reserva.
            releaseHoldQuietly(hold.holdId());
            giveBackQuietly(offerId, seat);
            OfferSale winner = saleRepository.findByOfferIdAndUserIdAndDocId(offerId, userId, docId).orElseThrow();
            return new ReserveResult(toReservation(winner, offer), false);
        }

        Integer remaining = quota.remaining(offerId);
        events.publishSafely(OfferEvents.OFFER_QUOTA_UPDATED, quotaPayload(offerId, remaining != null ? remaining : 0));
        if (remaining != null && remaining <= 0) {
            markSoldOut(offerId);
        }
        return new ReserveResult(toReservation(Objects.requireNonNull(sale), offer), true);
    }

    private void markSoldOut(UUID offerId) {
        tx.executeWithoutResult(s -> offerRepository.lockById(offerId).ifPresent(o -> {
            if (o.getEstado() == OfferStatus.ACTIVA) {
                o.agotar();
                events.publishAfterCommit(OfferEvents.OFFER_SOLD_OUT,
                        Map.of("offerId", offerId, "itineraryId", o.getItineraryId(), "timestamp", clock.instant()));
            }
        }));
    }

    // ------------------------------------------------------------------ eventos de Seat Inventory

    /** Una retención de oferta se liberó o expiró: el cupo y el asiento vuelven a la oferta. */
    public void onHoldReleased(UUID holdId) {
        String[] seatAndOffer = tx.execute(s -> saleRepository.lockByHoldId(holdId)
                .filter(sale -> sale.getEstado() == SaleStatus.RESERVADA)
                .map(sale -> {
                    sale.liberar(clock.instant());
                    return new String[]{sale.getSeatNumber(), sale.getOfferId().toString()};
                })
                .orElse(null));
        if (seatAndOffer == null) {
            return;
        }
        UUID offerId = UUID.fromString(seatAndOffer[1]);
        long remaining = giveBackQuietly(offerId, seatAndOffer[0]);
        if (remaining < 0) {
            return; // la oferta ya cerró
        }
        events.publishSafely(OfferEvents.OFFER_QUOTA_UPDATED, quotaPayload(offerId, (int) remaining));
        tx.executeWithoutResult(s -> offerRepository.lockById(offerId).ifPresent(o -> {
            if (o.getEstado() == OfferStatus.AGOTADA && o.vigente(clock.instant())) {
                o.reabrir();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("offerId", o.getId());
                payload.put("itineraryId", o.getItineraryId());
                payload.put("precioOferta", o.getPrecioOferta());
                payload.put("fin", o.getFin());
                payload.put("timestamp", clock.instant());
                events.publishAfterCommit(OfferEvents.OFFER_ACTIVATED, payload);
            }
        }));
    }

    @Transactional
    public void onSeatSold(UUID holdId) {
        saleRepository.lockByHoldId(holdId)
                .filter(sale -> sale.getEstado() == SaleStatus.RESERVADA)
                .ifPresent(sale -> sale.vender(clock.instant()));
    }

    public void onItineraryCancelled(UUID itineraryId) {
        for (UUID offerId : offerRepository.findIdsByItineraryIdAndEstadoNot(itineraryId, OfferStatus.CERRADA)) {
            close(offerId, "ITINERARIO_CANCELADO");
        }
    }

    // ------------------------------------------------------------------ consultas

    @Transactional(readOnly = true)
    public OfferResponse get(UUID offerId) {
        Offer offer = offerRepository.findById(offerId)
                .orElseThrow(() -> ApiException.notFound("OFFER_NOT_FOUND", "La oferta no existe"));
        return toResponse(offer);
    }

    @Transactional(readOnly = true)
    public List<OfferResponse> list(UUID itineraryId) {
        List<Offer> offers = itineraryId != null
                ? offerRepository.findByItineraryIdOrderByInicioDesc(itineraryId)
                : offerRepository.findByEstadoInOrderByInicioAsc(
                        List.of(OfferStatus.PROGRAMADA, OfferStatus.ACTIVA, OfferStatus.AGOTADA));
        return offers.stream().map(this::toResponse).toList();
    }

    /** GetRemainingQuota + tiempo restante (US-056). */
    @Transactional(readOnly = true)
    public QuotaResponse quota(UUID offerId) {
        Offer offer = offerRepository.findById(offerId)
                .orElseThrow(() -> ApiException.notFound("OFFER_NOT_FOUND", "La oferta no existe"));
        return new QuotaResponse(offer.getId(), offer.getEstado().name(), offer.getCupoMax(), remaining(offer),
                offer.getFin(), secondsLeft(offer));
    }

    @Transactional(readOnly = true)
    public ReservationResponse reservationByHold(UUID offerId, UUID holdId) {
        Offer offer = offerRepository.findById(offerId)
                .orElseThrow(() -> ApiException.notFound("OFFER_NOT_FOUND", "La oferta no existe"));
        OfferSale sale = saleRepository.findByOfferIdAndHoldId(offerId, holdId)
                .filter(OfferSale::viva)
                .orElseThrow(() -> ApiException.notFound("OFFER_RESERVATION_NOT_FOUND",
                        "No hay una reserva de oferta viva para esa retención"));
        return toReservation(sale, offer);
    }

    // ------------------------------------------------------------------ utilidades

    /** Inicializa el contador desde la BD (cupo restante real y asientos libres). Requiere transacción. */
    private void initializeCounter(Offer offer) {
        List<OfferSale> vivas = saleRepository.findByOfferIdAndEstadoIn(offer.getId(), VIVAS);
        Set<String> ocupados = new HashSet<>();
        vivas.forEach(v -> ocupados.add(v.getSeatNumber()));
        List<String> libres = offer.getAsientos().stream().filter(a -> !ocupados.contains(a)).toList();
        int restante = Math.min(offer.getCupoMax() - vivas.size(), libres.size());
        quota.initialize(offer.getId(), restante, libres);
    }

    private int remaining(Offer offer) {
        if (offer.getEstado() == OfferStatus.ACTIVA || offer.getEstado() == OfferStatus.AGOTADA) {
            try {
                Integer value = quota.remaining(offer.getId());
                if (value != null) {
                    return Math.max(0, value);
                }
            } catch (DataAccessException e) {
                log.debug("Redis no disponible para leer el cupo: {}", e.getMessage());
            }
        }
        if (offer.getEstado() == OfferStatus.CERRADA) {
            return 0;
        }
        return (int) Math.max(0, offer.getCupoMax() - saleRepository.countByOfferIdAndEstadoIn(offer.getId(), VIVAS));
    }

    private long secondsLeft(Offer offer) {
        if (offer.getEstado() == OfferStatus.CERRADA) {
            return 0;
        }
        return Math.max(0, Duration.between(clock.instant(), offer.getFin()).toSeconds());
    }

    private long giveBackQuietly(UUID offerId, String seat) {
        try {
            return quota.giveBack(offerId, seat);
        } catch (DataAccessException e) {
            log.error("No se pudo devolver el cupo de la oferta {} (asiento {}): {}", offerId, seat, e.getMessage());
            return -1;
        }
    }

    private void releaseHoldQuietly(UUID holdId) {
        try {
            seatInventory.releaseHold(holdId, "RESERVA_DUPLICADA");
        } catch (RuntimeException e) {
            log.warn("No se pudo liberar la retención {}: {}", holdId, e.getMessage());
        }
    }

    private Map<String, Object> quotaPayload(UUID offerId, int remaining) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("offerId", offerId);
        payload.put("cupoRestante", Math.max(0, remaining));
        payload.put("timestamp", clock.instant());
        return payload;
    }

    private OfferResponse toResponse(Offer offer) {
        return new OfferResponse(offer.getId(), offer.getItineraryId(), offer.getPrecioOferta(), offer.getCupoMax(),
                remaining(offer), offer.getInicio(), offer.getFin(), offer.getEstado().name(), secondsLeft(offer),
                List.copyOf(offer.getAsientos()), offer.getMotivoCierre());
    }

    private static ReservationResponse toReservation(OfferSale sale, Offer offer) {
        return new ReservationResponse(sale.getId(), sale.getOfferId(), offer.getItineraryId(), sale.getHoldId(),
                sale.getUserId(), sale.getSeatNumber(), offer.getPrecioOferta(), sale.getEstado().name());
    }
}
