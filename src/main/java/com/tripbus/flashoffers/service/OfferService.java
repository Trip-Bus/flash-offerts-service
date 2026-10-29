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
import com.tripbus.flashoffers.web.dto.UpdateOfferRequest;
import com.tripbus.flashoffers.web.dto.DuplicateOfferRequest;
import com.tripbus.flashoffers.web.dto.OfferResponse;
import com.tripbus.flashoffers.web.dto.QuotaResponse;
import com.tripbus.flashoffers.web.dto.ReservationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.text.Normalizer;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

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
    private final OfferAlertService alerts;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    @Value("${tripbus.offers.min-discount:5}") private BigDecimal minDiscount = BigDecimal.valueOf(5);
    @Value("${tripbus.offers.max-discount:70}") private BigDecimal maxDiscount = BigDecimal.valueOf(70);

    @Autowired
    public OfferService(OfferRepository offerRepository, OfferSaleRepository saleRepository, QuotaCounter quota,
                        SeatInventoryClient seatInventory, CatalogClient catalog, EventPublisher events,
                        TransactionTemplate tx, JdbcTemplate jdbc, Clock clock, OfferAlertService alerts) {
        this.offerRepository = offerRepository;
        this.saleRepository = saleRepository;
        this.quota = quota;
        this.seatInventory = seatInventory;
        this.catalog = catalog;
        this.events = events;
        this.tx = tx;
        this.jdbc = jdbc;
        this.clock = clock;
        this.alerts = alerts;
    }
    /** Conserva compatibilidad para consumidores que construyen el servicio sin el fan-out opcional. */
    public OfferService(OfferRepository offerRepository, OfferSaleRepository saleRepository, QuotaCounter quota,
                        SeatInventoryClient seatInventory, CatalogClient catalog, EventPublisher events,
                        TransactionTemplate tx, JdbcTemplate jdbc, Clock clock) {
        this(offerRepository,saleRepository,quota,seatInventory,catalog,events,tx,jdbc,clock,null);
    }

    // ------------------------------------------------------------------ CreateOffer (US-054)

    public OfferResponse create(CreateOfferRequest request, UUID userId, boolean isOperator, UUID operatorCompanyId) {
        return create(request,userId,isOperator,operatorCompanyId,false,false);
    }

    private OfferResponse create(CreateOfferRequest request, UUID userId, boolean isOperator, UUID operatorCompanyId,
                                 boolean requireFullQuota, boolean allowAutomaticSeats) {
        Instant now = clock.instant();
        List<String> seats = request.seatNumbers().stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (!request.fin().isAfter(request.inicio())) {
            throw ApiException.badRequest("INVALID_OFFER_WINDOW", "La hora de fin debe ser posterior a la de inicio");
        }
        if (request.inicio().isBefore(now) || Duration.between(request.inicio(), request.fin()).toMinutes() < 5) {
            throw ApiException.badRequest("INVALID_OFFER_WINDOW", "El inicio no puede estar en el pasado y la duración mínima es de 5 minutos");
        }
        if (request.cupo() > seats.size() && !(requireFullQuota && seats.isEmpty())) {
            throw ApiException.badRequest("QUOTA_EXCEEDS_SEATS", "El cupo no puede ser mayor que los asientos de la oferta");
        }
        int maxPorUsuario = request.maxPorUsuario() != null
                ? request.maxPorUsuario()
                : Math.min(Offer.DEFAULT_MAX_POR_USUARIO, request.cupo());
        if (maxPorUsuario > request.cupo()) {
            throw ApiException.badRequest("MAX_PER_USER_EXCEEDS_QUOTA",
                    "El máximo por usuario no puede ser mayor que el cupo de la oferta");
        }
        CatalogClient.Itinerary itinerary = catalog.itinerary(request.itineraryId());
        if (!"PROGRAMADO".equals(itinerary.estado())) {
            throw ApiException.conflict("ITINERARY_NOT_AVAILABLE", "El viaje no está disponible");
        }
        if (itinerary.fechaSalida() != null && request.fin().isAfter(itinerary.fechaSalida())) {
            throw ApiException.badRequest("OFFER_ENDS_AFTER_DEPARTURE", "La oferta debe terminar antes de la salida del bus");
        }
        BigDecimal discount = itinerary.precioBase().subtract(request.precioOferta()).multiply(BigDecimal.valueOf(100))
                .divide(itinerary.precioBase(), 2, java.math.RoundingMode.HALF_UP);
        if (discount.compareTo(minDiscount) < 0 || discount.compareTo(maxDiscount) > 0) {
            throw ApiException.badRequest("INVALID_DISCOUNT", "El descuento debe estar entre " + minDiscount + "% y " + maxDiscount + "%");
        }
        // US-005: un operador solo puede crear ofertas para viajes de su propia empresa.
        if (isOperator && !Objects.equals(operatorCompanyId, itinerary.companyId())) {
            throw ApiException.forbidden("NOT_YOUR_COMPANY", "Solo puede crear ofertas para viajes de su empresa");
        }
        Offer draft = new Offer(request.itineraryId(), request.precioOferta(), request.cupo(), maxPorUsuario,
                request.inicio(), request.fin(), userId, now);
        draft.datosViaje(itinerary.companyId(), itinerary.origen(), itinerary.destino(), itinerary.fechaSalida(),
                itinerary.precioBase(), itinerary.empresaNombre(), request.permiteReprogramar()==null || request.permiteReprogramar());
        // Se guarda como BORRADOR: el scheduler no la activa (ni se lista) hasta tener sus asientos reservados.
        tx.executeWithoutResult(s -> {
            jdbc.query("select pg_advisory_xact_lock(hashtext(?))", rs -> { }, "offer-itinerary:"+request.itineraryId());
            if(!offerRepository.findOverlaps(request.itineraryId(),request.inicio(),request.fin(),OfferStatus.CERRADA).isEmpty())
                throw ApiException.conflict("OFFER_OVERLAP", "Ya existe una oferta en una ventana que se cruza con este viaje");
            offerRepository.save(draft);
        });

        SeatInventoryClient.OfferSeats reserved;
        try {
            reserved = seatInventory.reserveOfferSeats(draft.getId(), draft.getItineraryId(), seats);
        } catch (RuntimeException e) {
            if(requireFullQuota && allowAutomaticSeats && !seats.isEmpty()) {
                try { seatInventory.releaseOfferSeats(draft.getId()); reserved=seatInventory.reserveOfferSeats(draft.getId(),draft.getItineraryId(),List.of()); }
                catch(RuntimeException fallbackFailure){discardDraft(draft.getId());throw e;}
            } else {
            // Puede ser un timeout con la reserva ya hecha en Seat Inventory: se liberan por si acaso.
            discardDraft(draft.getId());
            throw e;
            }
        }
        if(requireFullQuota && allowAutomaticSeats && (reserved==null || reserved.reservados()==null || reserved.reservados().size()<request.cupo())) {
            try { seatInventory.releaseOfferSeats(draft.getId()); reserved=seatInventory.reserveOfferSeats(draft.getId(),draft.getItineraryId(),List.of()); }
            catch(RuntimeException e){discardDraft(draft.getId());throw e;}
            if(reserved==null || reserved.reservados()==null || reserved.reservados().size()<request.cupo()){
                discardDraft(draft.getId());throw ApiException.conflict("NO_SEATS_AVAILABLE","No hay suficientes asientos disponibles para duplicar la oferta");
            }
        }
        if(requireFullQuota && (reserved==null || reserved.reservados()==null || reserved.reservados().size()<request.cupo())) {
            discardDraft(draft.getId());throw ApiException.conflict("NO_SEATS_AVAILABLE","No hay suficientes asientos disponibles para duplicar la oferta");
        }
        if (reserved == null || reserved.reservados() == null || reserved.reservados().isEmpty()) {
            discardDraft(draft.getId());
            throw ApiException.conflict("NO_SEATS_AVAILABLE", "Ninguno de los asientos indicados está disponible");
        }
        List<String> reservedSeats=reserved.reservados();

        try {
            tx.executeWithoutResult(s -> scheduleDraft(draft.getId(), reservedSeats));
        } catch (RuntimeException e) {
            discardDraft(draft.getId());
            throw e;
        }
        if (!draft.getInicio().isAfter(now)) {
            tx.executeWithoutResult(s -> activate(draft.getId()));
        }
        return tx.execute(s -> get(draft.getId()));
    }

    /** Borrador -> PROGRAMADA con sus asientos, en la misma transacción que publica OfferCreated. */
    private void scheduleDraft(UUID offerId, List<String> reservados) {
        Offer offer = offerRepository.lockById(offerId)
                .filter(Offer::borrador)
                .orElseThrow(() -> ApiException.conflict("OFFER_DRAFT_EXPIRED",
                        "El borrador de la oferta expiró antes de reservar sus asientos"));
        offer.asignarAsientos(reservados);
        offer.programar();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("offerId", offer.getId());
        payload.put("itineraryId", offer.getItineraryId());
        payload.put("seatNumbers", reservados);
        payload.put("precioOferta", offer.getPrecioOferta());
        payload.put("cupoMax", offer.getCupoMax());
        payload.put("inicio", offer.getInicio());
        payload.put("fin", offer.getFin());
        payload.put("origen", offer.getOrigen()); payload.put("destino", offer.getDestino());
        events.publishAfterCommit(OfferEvents.OFFER_CREATED, payload);
    }

    /**
     * Compensación de create(): libera en Seat Inventory los asientos que pudieran haber quedado EN_OFERTA y
     * borra el borrador. Nunca lanza, para no ocultar el error original; lo que falle lo recoge
     * {@link #purgeStaleDraft(UUID)}.
     */
    private void discardDraft(UUID offerId) {
        try {
            seatInventory.releaseOfferSeats(offerId);
        } catch (RuntimeException e) {
            log.warn("No se pudieron liberar los asientos del borrador {}: {}", offerId, e.getMessage());
        }
        try {
            tx.executeWithoutResult(s -> offerRepository.lockById(offerId)
                    .filter(Offer::borrador)
                    .ifPresent(offerRepository::delete));
        } catch (RuntimeException e) {
            log.warn("No se pudo borrar el borrador {}: {}", offerId, e.getMessage());
        }
    }

    /**
     * Limpieza de un borrador abandonado (caída a mitad de create o compensación fallida): libera sus asientos y lo
     * borra. Si Seat Inventory no responde se conserva el borrador para reintentarlo en la siguiente pasada.
     */
    public void purgeStaleDraft(UUID offerId) {
        seatInventory.releaseOfferSeats(offerId);
        tx.executeWithoutResult(s -> offerRepository.lockById(offerId)
                .filter(Offer::borrador)
                .ifPresent(offerRepository::delete));
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
        payload.put("origen", offer.getOrigen()); payload.put("destino", offer.getDestino());
        payload.put("timestamp", clock.instant());
        events.publishAfterCommit(OfferEvents.OFFER_ACTIVATED, payload);
        if (alerts != null) alerts.alertAfterCommit(offer);
        events.publishAfterCommit(OfferEvents.OFFER_QUOTA_UPDATED, quotaPayload(offer.getId(), remaining(offer)));
    }

    /** CloseOffer: cierra la oferta; Seat Inventory devuelve a la venta normal los asientos no vendidos. */
    @Transactional
    public void close(UUID offerId, String motivo) {
        Offer offer = offerRepository.lockById(offerId)
                .orElseThrow(() -> ApiException.notFound("OFFER_NOT_FOUND", "La oferta no existe"));
        // Un borrador no se anuncia: si queda abandonado lo borra la limpieza del scheduler.
        if (offer.getEstado() == OfferStatus.CERRADA || offer.borrador()) {
            return;
        }
        offer.cerrar(motivo);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("offerId", offer.getId());
        payload.put("itineraryId", offer.getItineraryId());
        payload.put("motivo", motivo);
        payload.put("origen", offer.getOrigen()); payload.put("destino", offer.getDestino());
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
        Offer offer = findVisible(offerId);
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

        try {
            if (!quota.isInitialized(offerId)) {
                tx.executeWithoutResult(s -> initializeCounter(offerRepository.findById(offerId).orElseThrow()));
            }
        } catch (DataAccessException e) {
            throw ApiException.unavailable("QUOTA_UNAVAILABLE", "El contador de cupo no está disponible");
        }

        Attempt attempt = new Attempt();
        OfferSale sale;
        try {
            sale = tx.execute(s -> reserveLocked(offer, userId, docId, attempt));
        } catch (DataIntegrityViolationException e) {
            // Choque con otra venta viva al guardar: se deshace esta reserva y se devuelve la existente.
            undo(offerId, attempt, "RESERVA_DUPLICADA");
            OfferSale winner = saleRepository.findByOfferIdAndUserIdAndDocId(offerId, userId, docId).orElseThrow();
            return new ReserveResult(toReservation(winner, offer), false);
        } catch (RuntimeException e) {
            // Incluye un fallo del commit: el cupo y la retención ya tomados se devuelven.
            undo(offerId, attempt, "RESERVA_FALLIDA");
            throw e;
        }
        if (attempt.existing != null) {
            return new ReserveResult(toReservation(attempt.existing, offer), false);
        }
        if (attempt.soldOut) {
            markSoldOut(offerId);
            throw ApiException.conflict("OUT_OF_STOCK", "La oferta se agotó");
        }

        Integer remaining = quota.remaining(offerId);
        events.publishSafely(OfferEvents.OFFER_QUOTA_UPDATED, quotaPayload(offerId, remaining != null ? remaining : 0));
        if (remaining != null && remaining <= 0) {
            markSoldOut(offerId);
        }
        return new ReserveResult(toReservation(Objects.requireNonNull(sale), offer), true);
    }

    /** Lo que la transacción de reserva tomó fuera de la BD (para deshacerlo si falla) y cómo terminó. */
    private static final class Attempt {
        private String seat;
        private SeatInventoryClient.Hold hold;
        private OfferSale existing;
        private boolean soldOut;
    }

    /**
     * Núcleo de la reserva, en una transacción. Un lock asesor de PostgreSQL por (oferta, usuario), que se suelta
     * solo al terminar la transacción, serializa las reservas de la misma persona: el conteo de sus ventas vivas
     * y la venta nueva no se intercalan con otra reserva suya, y el límite se valida antes de tocar el cupo de Redis.
     */
    private OfferSale reserveLocked(Offer offer, UUID userId, String docId, Attempt attempt) {
        UUID offerId = offer.getId();
        jdbc.query("select pg_advisory_xact_lock(hashtext(?))", rs -> { }, "offer-user:" + offerId + ":" + userId);
        var previous = saleRepository.findByOfferIdAndUserIdAndDocId(offerId, userId, docId);
        if (previous.isPresent() && previous.get().viva()) {
            attempt.existing = previous.get();
            return null;
        }
        if (saleRepository.countByOfferIdAndUserIdAndEstadoIn(offerId, userId, VIVAS) >= offer.getMaxPorUsuario()) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_LIMIT_REACHED",
                    "Ya tiene el máximo de cupos permitido por persona en esta oferta",
                    Map.of("maxPorUsuario", offer.getMaxPorUsuario()));
        }
        try {
            attempt.seat = quota.reserve(offerId);
        } catch (DataAccessException e) {
            throw ApiException.unavailable("QUOTA_UNAVAILABLE", "El contador de cupo no está disponible");
        }
        if (attempt.seat == null) {
            attempt.soldOut = true;
            return null;
        }
        attempt.hold = seatInventory.hold(offer.getItineraryId(), attempt.seat, userId, offerId);
        if (previous.isPresent()) {
            OfferSale p = previous.get();
            p.reservarDeNuevo(attempt.seat, attempt.hold.holdId(), clock.instant());
            return p;
        }
        return saleRepository.saveAndFlush(new OfferSale(offerId, userId, docId, attempt.seat, attempt.hold.holdId(),
                clock.instant()));
    }

    private void undo(UUID offerId, Attempt attempt, String motivo) {
        if (attempt.hold != null) {
            releaseHoldQuietly(attempt.hold.holdId(), motivo);
        }
        if (attempt.seat != null) {
            giveBackQuietly(offerId, attempt.seat);
        }
    }

    private void markSoldOut(UUID offerId) {
        tx.executeWithoutResult(s -> offerRepository.lockById(offerId).ifPresent(o -> {
            if (o.getEstado() == OfferStatus.ACTIVA) {
                o.agotar(clock.instant());
                events.publishAfterCommit(OfferEvents.OFFER_SOLD_OUT,
                        Map.of("offerId", offerId, "itineraryId", o.getItineraryId(), "origen",Objects.toString(o.getOrigen(),""),"destino",Objects.toString(o.getDestino(),""), "timestamp", clock.instant()));
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
                payload.put("origen", o.getOrigen()); payload.put("destino", o.getDestino());
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

    @Transactional
    public void onItineraryRescheduled(UUID itineraryId, Instant newDeparture) {
        offerRepository.findByItineraryIdAndEstadoNotOrderByInicioDesc(itineraryId,OfferStatus.CERRADA).forEach(o->{
            o.reprogramarViaje(newDeparture);
            Map<String,Object> p=new LinkedHashMap<>();p.put("offerId",o.getId());p.put("itineraryId",itineraryId);p.put("origen",o.getOrigen());p.put("destino",o.getDestino());p.put("fechaSalida",newDeparture);p.put("fin",o.getFin());p.put("timestamp",clock.instant());events.publishAfterCommit(OfferEvents.OFFER_UPDATED,p);
        });
    }

    @Transactional
    public void backfillItineraryDetails() {
        Set<UUID> ids=new HashSet<>(); offerRepository.findByCompanyIdIsNull().forEach(o->ids.add(o.getItineraryId()));
        for(UUID id:ids) { try { CatalogClient.Itinerary i=catalog.itinerary(id); offerRepository.findByItineraryIdAndEstadoNotOrderByInicioDesc(id,OfferStatus.BORRADOR).forEach(o->o.datosViaje(i.companyId(),i.origen(),i.destino(),i.fechaSalida(),i.precioBase(),i.empresaNombre(),o.isPermiteReprogramar())); }
            catch(RuntimeException e){log.warn("No se pudo completar datos del itinerario {} desde catalog; se reintentará en el próximo arranque",id);} }
    }

    @Transactional
    public OfferResponse cancelByOperator(UUID id, UUID company, boolean admin) {
        Offer o=offerRepository.findById(id).orElseThrow(()->ApiException.notFound("OFFER_NOT_FOUND","La oferta no existe"));checkCompany(o,company,admin);
        if(o.getEstado()!=OfferStatus.PROGRAMADA||!o.getInicio().isAfter(clock.instant()))throw ApiException.conflict("OFFER_NOT_CANCELLABLE","Solo se cancelan ofertas programadas antes del inicio");
        seatInventory.releaseOfferSeats(id); close(id,"CANCELADA_POR_OPERADOR"); return get(id);
    }

    // ------------------------------------------------------------------ consultas

    @Transactional(readOnly = true)
    public OfferResponse get(UUID offerId) {
        return toResponse(findVisible(offerId));
    }

    @Transactional(readOnly = true)
    public List<OfferResponse> list(UUID itineraryId) {
        List<Offer> offers = itineraryId != null
                ? offerRepository.findByItineraryIdAndEstadoNotOrderByInicioDesc(itineraryId, OfferStatus.BORRADOR)
                : offerRepository.findByEstadoInOrderByInicioAsc(
                        List.of(OfferStatus.PROGRAMADA, OfferStatus.ACTIVA, OfferStatus.AGOTADA));
        return offers.stream().map(this::toResponse).toList();
    }

    /** GetRemainingQuota + tiempo restante (US-056). */
    @Transactional(readOnly = true)
    public QuotaResponse quota(UUID offerId) {
        Offer offer = findVisible(offerId);
        return new QuotaResponse(offer.getId(), offer.getEstado().name(), offer.getCupoMax(), remaining(offer),
                offer.getFin(), secondsLeft(offer));
    }

    @Transactional(readOnly = true)
    public ReservationResponse reservationByHold(UUID offerId, UUID holdId) {
        Offer offer = findVisible(offerId);
        OfferSale sale = saleRepository.findByOfferIdAndHoldId(offerId, holdId)
                .filter(OfferSale::viva)
                .orElseThrow(() -> ApiException.notFound("OFFER_RESERVATION_NOT_FOUND",
                        "No hay una reserva de oferta viva para esa retención"));
        return toReservation(sale, offer);
    }

    public record ShowcaseOffer(UUID offerId, UUID itineraryId, String empresa, String origen, String destino,
            Instant fechaSalida, BigDecimal precioBase, BigDecimal precioOferta, BigDecimal descuentoPorcentaje,
            int cupoRestante, Instant inicio, Instant fin, long segundosRestantes, String estado) { }
    public record OperatorOffer(OfferResponse oferta, int cupoVendido, int cupoRestante) { }
    public record OfferMetrics(int cupo, long reservasCreadas, long ventas, long liberadasOVencidas,
                               BigDecimal conversion, Long tiempoEnAgotarseSegundos, BigDecimal ingresos,
                               BigDecimal descuentoTotalOtorgado, long seguidoresAlertados) { }
    public record OperatorMetrics(long ofertas, long ventas, BigDecimal ingresos, BigDecimal descuentoOtorgado,
                                  BigDecimal conversionPromedio, UUID ofertaAgotadaMasRapido) { }

    /** Agregados en PostgreSQL para no materializar las ventas en la JVM. */
    @Transactional(readOnly = true)
    public OfferMetrics metrics(UUID id, UUID company, boolean admin) {
        Offer offer=offerRepository.findById(id).orElseThrow(()->ApiException.notFound("OFFER_NOT_FOUND","La oferta no existe")); checkCompany(offer,company,admin);
        return jdbc.queryForObject("""
          select o.cupo_max, count(s.id), count(s.id) filter(where s.estado='VENDIDA'),
          count(s.id) filter(where s.estado='LIBERADA'), (select count(*) from offer_alerts_sent aa where aa.offer_id=o.id),
          case when o.agotada_en is null then null else extract(epoch from (o.agotada_en-o.inicio))::bigint end,
          coalesce(sum(case when s.estado='VENDIDA' then o.precio_oferta else 0 end),0),
          coalesce(sum(case when s.estado='VENDIDA' then (o.precio_base-o.precio_oferta) else 0 end),0)
          from offers o left join offer_sales s on s.offer_id=o.id
          where o.id=? group by o.id
          """, (rs,n)-> {long reservations=rs.getLong(2),sales=rs.getLong(3); Long elapsed=rs.getObject(6,Long.class); return new OfferMetrics(rs.getInt(1),reservations,sales,rs.getLong(4),reservations==0?BigDecimal.ZERO:BigDecimal.valueOf(sales).divide(BigDecimal.valueOf(reservations),4,java.math.RoundingMode.HALF_UP),elapsed,rs.getBigDecimal(7),rs.getBigDecimal(8),rs.getLong(5));},id);
    }

    @Transactional(readOnly = true)
    public OperatorMetrics operatorMetrics(Instant desde, Instant hasta, UUID company, boolean admin) {
        if(desde==null||hasta==null||!hasta.isAfter(desde)||Duration.between(desde,hasta).toDays()>90) throw ApiException.badRequest("INVALID_METRICS_RANGE","El rango debe ser válido y de máximo 90 días");
        String scope=admin?"":" and o.company_id=?";
        java.util.List<Object> args=new java.util.ArrayList<>();args.add(java.sql.Timestamp.from(desde));args.add(java.sql.Timestamp.from(hasta));if(!admin)args.add(company);
        return jdbc.queryForObject("""
          select count(*), coalesce(sum(x.ventas),0), coalesce(sum(x.ingresos),0), coalesce(sum(x.descuento),0),
          coalesce(avg(x.conversion),0), (array_agg(x.id order by x.elapsed nulls last))[1]
          from (select o.id,o.agotada_en-o.inicio elapsed, count(s.id) filter(where s.estado='VENDIDA') ventas,
          coalesce(sum(case when s.estado='VENDIDA' then o.precio_oferta else 0 end),0) ingresos,
          coalesce(sum(case when s.estado='VENDIDA' then o.precio_base-o.precio_oferta else 0 end),0) descuento,
          case when count(s.id)=0 then 0 else count(s.id) filter(where s.estado='VENDIDA')::numeric/count(s.id) end conversion
          from offers o left join offer_sales s on s.offer_id=o.id where o.inicio>=? and o.inicio<=?"""+scope+" group by o.id) x",
          (rs,n)->new OperatorMetrics(rs.getLong(1),rs.getLong(2),rs.getBigDecimal(3),rs.getBigDecimal(4),rs.getBigDecimal(5),rs.getObject(6,UUID.class)),args.toArray());
    }

    @Transactional(readOnly = true)
    public Page<ShowcaseOffer> showcase(String origin, String destination, int page, int size) {
        Instant now=clock.instant(), limit=now.plus(Duration.ofHours(48));
        List<Offer> candidates=offerRepository.findByEstadoInOrderByInicioAsc(List.of(OfferStatus.ACTIVA, OfferStatus.PROGRAMADA))
                .stream().filter(o -> o.getEstado()==OfferStatus.ACTIVA ? o.getFin().isAfter(now) : !o.getInicio().isBefore(now) && !o.getInicio().isAfter(limit))
                .filter(o -> origin==null || normalized(o.getOrigen()).contains(normalized(origin)))
                .filter(o -> destination==null || normalized(o.getDestino()).contains(normalized(destination)))
                .sorted(java.util.Comparator.comparingInt((Offer o)->o.getEstado()==OfferStatus.ACTIVA?0:1)
                        .thenComparing(o->o.getEstado()==OfferStatus.ACTIVA?o.getFin():o.getInicio())).toList();
        List<ShowcaseOffer> all=candidates.stream().map(o -> new ShowcaseOffer(o.getId(),o.getItineraryId(),o.getEmpresaNombre(),o.getOrigen(),o.getDestino(),o.getFechaSalida(),o.getPrecioBase(),o.getPrecioOferta(),
                o.getPrecioBase()==null||o.getPrecioBase().signum()==0?BigDecimal.ZERO:o.getPrecioBase().subtract(o.getPrecioOferta()).multiply(BigDecimal.valueOf(100)).divide(o.getPrecioBase(),2,java.math.RoundingMode.HALF_UP),remaining(o),o.getInicio(),o.getFin(),secondsLeft(o),o.getEstado().name())).toList();
        int from=Math.min(page*size,all.size()), to=Math.min(from+size,all.size());
        return new PageImpl<>(all.subList(from,to),PageRequest.of(page,size),all.size());
    }

    private static String normalized(String s) { return s==null?"":Normalizer.normalize(s,Normalizer.Form.NFD).replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT); }

    @Transactional(readOnly = true)
    public Page<OperatorOffer> operatorOffers(String estado, Instant desde, Instant hasta, int page, int size, UUID company, boolean admin) {
        List<Offer> all=(admin?offerRepository.findAll():offerRepository.findByCompanyId(company)).stream()
                .filter(o->!o.borrador()).filter(o->estado==null||o.getEstado().name().equalsIgnoreCase(estado))
                .filter(o->desde==null||!o.getInicio().isBefore(desde)).filter(o->hasta==null||!o.getInicio().isAfter(hasta))
                .sorted(java.util.Comparator.comparing(Offer::getInicio).reversed()).toList();
        int from=Math.min(page*size,all.size()),to=Math.min(from+size,all.size());
        return new PageImpl<>(all.subList(from,to).stream().map(o->{int left=remaining(o);return new OperatorOffer(toResponse(o),Math.max(0,o.getCupoMax()-left),left);}).toList(),PageRequest.of(page,size),all.size());
    }

    @Transactional
    public OfferResponse update(UUID id, UpdateOfferRequest req, UUID company, boolean admin) {
        Offer offer=offerRepository.findById(id).orElseThrow(()->ApiException.notFound("OFFER_NOT_FOUND","La oferta no existe"));
        checkCompany(offer,company,admin);
        Instant now=clock.instant();
        if(offer.getEstado()!=OfferStatus.PROGRAMADA || !offer.getInicio().isAfter(now)) throw ApiException.conflict("OFFER_NOT_EDITABLE","Solo se editan ofertas programadas antes de iniciar");
        Instant inicio=req.inicio()==null?offer.getInicio():req.inicio(), fin=req.fin()==null?offer.getFin():req.fin();
        BigDecimal precio=req.precioOferta()==null?offer.getPrecioOferta():req.precioOferta();
        List<String> seats=req.seatNumbers()==null?List.copyOf(offer.getAsientos()):req.seatNumbers().stream().map(String::trim).filter(s->!s.isEmpty()).distinct().toList();
        BigDecimal discount=offer.getPrecioBase().subtract(precio).multiply(BigDecimal.valueOf(100)).divide(offer.getPrecioBase(),2,java.math.RoundingMode.HALF_UP);
        int quotaCount=req.cupo()==null?offer.getCupoMax():req.cupo(), maxUser=req.maxPorUsuario()==null?Math.min(offer.getMaxPorUsuario(),quotaCount):req.maxPorUsuario();
        validateWindow(inicio,fin,now);
        if(discount.compareTo(minDiscount)<0||discount.compareTo(maxDiscount)>0) throw ApiException.badRequest("INVALID_DISCOUNT","Descuento fuera del rango permitido");
        if(quotaCount>seats.size()||maxUser>quotaCount) throw ApiException.badRequest("QUOTA_EXCEEDS_SEATS","Cupo o máximo por usuario inválido");
        if(!offerRepository.findOverlaps(offer.getItineraryId(),inicio,fin,OfferStatus.CERRADA).stream().filter(o->!o.getId().equals(id)).toList().isEmpty()) throw ApiException.conflict("OFFER_OVERLAP","Ventana cruzada");
        if(offer.getFechaSalida()!=null&&fin.isAfter(offer.getFechaSalida()))throw ApiException.badRequest("OFFER_ENDS_AFTER_DEPARTURE","La oferta debe terminar antes de la salida");
        boolean changed=!offer.getAsientos().equals(seats);
        List<String> oldSeats=List.copyOf(offer.getAsientos());
        if(changed){ seatInventory.releaseOfferSeats(id); try { var reserve=seatInventory.reserveOfferSeats(id,offer.getItineraryId(),seats); if(reserve==null||reserve.reservados()==null||reserve.reservados().size()<quotaCount) throw ApiException.conflict("NO_SEATS_AVAILABLE","No se pudieron reservar los asientos nuevos"); seats=reserve.reservados(); } catch(RuntimeException ex){ try{seatInventory.releaseOfferSeats(id);seatInventory.reserveOfferSeats(id,offer.getItineraryId(),oldSeats);}catch(RuntimeException ignored){} throw ex; } }
        List<String> chosen=seats;
        try { tx.executeWithoutResult(s->{Offer locked=offerRepository.lockById(id).orElseThrow(); if(locked.getEstado()!=OfferStatus.PROGRAMADA||!locked.getInicio().isAfter(clock.instant()))throw ApiException.conflict("OFFER_NOT_EDITABLE","Oferta no editable"); locked.editar(precio,quotaCount,maxUser,inicio,fin,chosen,req.permiteReprogramar()==null?locked.isPermiteReprogramar():req.permiteReprogramar()); }); }
        catch(RuntimeException ex){if(changed)try{seatInventory.releaseOfferSeats(id);seatInventory.reserveOfferSeats(id,offer.getItineraryId(),oldSeats);}catch(RuntimeException ignored){}throw ex;}
        if(changed && quota.isInitialized(id)){quota.delete(id);quota.initialize(id,quotaCount,chosen);}
        Map<String,Object> event=new LinkedHashMap<>(); event.put("offerId",id);event.put("itineraryId",offer.getItineraryId());event.put("origen",offer.getOrigen());event.put("destino",offer.getDestino());event.put("precioOferta",precio);event.put("timestamp",now);events.publishSafely(OfferEvents.OFFER_UPDATED,event);
        return get(id);
    }

    @Transactional
    public OfferResponse duplicate(UUID id, DuplicateOfferRequest req, UUID company, boolean admin, UUID userId) {
        Offer original=offerRepository.findById(id).orElseThrow(()->ApiException.notFound("OFFER_NOT_FOUND","La oferta no existe")); checkCompany(original,company,admin);
        CatalogClient.Itinerary target=catalog.itinerary(req.itineraryId()); if(!Objects.equals(original.getCompanyId(),target.companyId()))throw ApiException.notFound("ITINERARY_NOT_FOUND","El itinerario no existe"); checkCompanyId(target.companyId(),company,admin);
        BigDecimal price=target.precioBase().multiply(original.getPrecioOferta()).divide(original.getPrecioBase(),2,java.math.RoundingMode.HALF_UP);
        List<String> seats=req.seatNumbers()==null?original.getAsientos():req.seatNumbers();
        return create(new CreateOfferRequest(req.itineraryId(),seats,price,original.getCupoMax(),req.inicio(),req.fin(),original.getMaxPorUsuario(),original.isPermiteReprogramar()),userId,false,company,true,req.seatNumbers()==null);
    }

    private void checkCompany(Offer o, UUID company, boolean admin){if(!admin&&!Objects.equals(company,o.getCompanyId()))throw ApiException.notFound("OFFER_NOT_FOUND","La oferta no existe");}
    private void checkCompanyId(UUID actual, UUID company, boolean admin){if(!admin&&!Objects.equals(company,actual))throw ApiException.notFound("ITINERARY_NOT_FOUND","El itinerario no existe");}
    private static void validateWindow(Instant start,Instant end,Instant now){if(start.isBefore(now)||Duration.between(start,end).toMinutes()<5)throw ApiException.badRequest("INVALID_OFFER_WINDOW","Inicio o duración inválidos");}

    @Transactional(readOnly = true)
    public Map<String,Object> internalDetails(UUID id){Offer o=findVisible(id);Map<String,Object> details=new LinkedHashMap<>();details.put("offerId",o.getId());details.put("itineraryId",o.getItineraryId());details.put("companyId",o.getCompanyId());details.put("precioOferta",o.getPrecioOferta());details.put("precioBase",o.getPrecioBase());details.put("permiteReprogramar",o.isPermiteReprogramar());return details;}

    // ------------------------------------------------------------------ utilidades

    /** Un borrador (aún sin asientos reservados) no existe para los clientes: ni se consulta ni se reserva. */
    private Offer findVisible(UUID offerId) {
        return offerRepository.findById(offerId)
                .filter(o -> !o.borrador())
                .orElseThrow(() -> ApiException.notFound("OFFER_NOT_FOUND", "La oferta no existe"));
    }

    /** Inicializa el contador desde la BD (cupo restante real y asientos libres). Requiere transacción. */
    private void initializeCounter(Offer offer) {
        ExpectedQuota esperado = expectedQuota(offer);
        quota.initialize(offer.getId(), esperado.restante(), esperado.libres());
    }

    /** Cupo que debería tener el contador según la BD: cupo menos ventas vivas, y los asientos aún libres. */
    public record ExpectedQuota(int restante, List<String> libres) {
    }

    private ExpectedQuota expectedQuota(Offer offer) {
        List<OfferSale> vivas = saleRepository.findByOfferIdAndEstadoIn(offer.getId(), VIVAS);
        Set<String> ocupados = new HashSet<>();
        vivas.forEach(v -> ocupados.add(v.getSeatNumber()));
        List<String> libres = offer.getAsientos().stream().filter(a -> !ocupados.contains(a)).toList();
        return new ExpectedQuota(Math.max(0, Math.min(offer.getCupoMax() - vivas.size(), libres.size())), libres);
    }

    /** Reconciliación del cupo (QuotaReconciler): el cupo esperado si la oferta sigue ACTIVA; si no, vacío. */
    @Transactional(readOnly = true)
    public Optional<ExpectedQuota> expectedQuotaIfActive(UUID offerId) {
        return offerRepository.findById(offerId)
                .filter(o -> o.getEstado() == OfferStatus.ACTIVA)
                .map(this::expectedQuota);
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

    private void releaseHoldQuietly(UUID holdId, String motivo) {
        try {
            seatInventory.releaseHold(holdId, motivo);
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
                offer.getMaxPorUsuario(), remaining(offer), offer.getInicio(), offer.getFin(), offer.getEstado().name(), secondsLeft(offer),
                List.copyOf(offer.getAsientos()), offer.getMotivoCierre(),offer.isPermiteReprogramar());
    }

    private static ReservationResponse toReservation(OfferSale sale, Offer offer) {
        return new ReservationResponse(sale.getId(), sale.getOfferId(), offer.getItineraryId(), sale.getHoldId(),
                sale.getUserId(), sale.getSeatNumber(), offer.getPrecioOferta(), sale.getEstado().name());
    }
}
