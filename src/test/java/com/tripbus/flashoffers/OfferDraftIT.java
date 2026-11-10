package com.tripbus.flashoffers;

import com.tripbus.flashoffers.client.CatalogClient;
import com.tripbus.flashoffers.client.SeatInventoryClient;
import com.tripbus.flashoffers.common.error.ApiException;
import com.tripbus.flashoffers.domain.Offer;
import com.tripbus.flashoffers.repository.OfferRepository;
import com.tripbus.flashoffers.service.OfferScheduler;
import com.tripbus.flashoffers.service.OfferService;
import com.tripbus.flashoffers.service.QuotaCounter;
import com.tripbus.flashoffers.web.dto.CreateOfferRequest;
import com.tripbus.flashoffers.web.dto.UpdateOfferRequest;
import com.tripbus.flashoffers.web.dto.DuplicateOfferRequest;
import com.tripbus.flashoffers.web.dto.OfferResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US-054: la oferta es BORRADOR mientras Seat Inventory reserva sus asientos. El scheduler no la activa,
 * no se lista ni se puede consultar o reservar; si la reserva falla se liberan los asientos y no queda oferta;
 * los borradores abandonados los descarta la limpieza del scheduler. PostgreSQL y Redis reales (Testcontainers).
 */
@SpringBootTest
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
class OfferDraftIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    private OfferService offerService;
    @Autowired
    private OfferScheduler scheduler;
    @Autowired
    private OfferRepository offerRepository;
    @Autowired
    private QuotaCounter quotaCounter;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;

    @MockitoBean
    private SeatInventoryClient seatInventory;
    @MockitoBean
    private CatalogClient catalog;

    private final UUID companyId = UUID.randomUUID();
    private final AtomicReference<UUID> offerId = new AtomicReference<>();
    // Un viaje por prueba: así list(itineraryId) y onItineraryCancelled no ven ofertas de otras pruebas.
    private UUID itineraryId;

    @BeforeEach
    void simular() {
        offerId.set(null);
        itineraryId = UUID.randomUUID();
        when(catalog.itinerary(itineraryId)).thenReturn(new CatalogClient.Itinerary(itineraryId, companyId,
                new BigDecimal("95000"), "PROGRAMADO", Instant.now().plus(2, ChronoUnit.DAYS)));
    }

    private OfferResponse crear() {
        Instant start=Instant.now().plus(30,ChronoUnit.SECONDS);
        OfferResponse created=offerService.create(new CreateOfferRequest(itineraryId, List.of("1", "2", "3"), new BigDecimal("49900"),
                3, start, start.plus(1, ChronoUnit.HOURS)), UUID.randomUUID(), false, null);
        offerService.activate(created.offerId());
        return offerService.get(created.offerId());
    }

    private String estado(UUID id) {
        List<String> estados = jdbc.queryForList("select estado from offers where id = ?", String.class, id);
        return estados.isEmpty() ? null : estados.get(0);
    }

    private Offer borrador(Instant creadoEn) {
        Instant now = Instant.now();
        return offerRepository.save(new Offer(itineraryId, new BigDecimal("49900"), 2, now,
                now.plus(1, ChronoUnit.HOURS), UUID.randomUUID(), creadoEn));
    }

    private void reservaConAsientos() {
        when(seatInventory.reserveOfferSeats(any(), any(), anyList())).thenAnswer(inv ->
                new SeatInventoryClient.OfferSeats(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                        List.of()));
    }

    @Test
    void mientrasSeReservanLosAsientosLaOfertaEsBorradorYNoSeActivaNiSeVe() throws Exception {
        CountDownLatch dentro = new CountDownLatch(1);
        CountDownLatch seguir = new CountDownLatch(1);
        when(seatInventory.reserveOfferSeats(any(), any(), anyList())).thenAnswer(inv -> {
            offerId.set(inv.getArgument(0));
            dentro.countDown();
            assertThat(seguir.await(30, TimeUnit.SECONDS)).isTrue();
            return new SeatInventoryClient.OfferSeats(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                    List.of());
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<OfferResponse> creacion = pool.submit(this::crear);
            assertThat(dentro.await(30, TimeUnit.SECONDS)).isTrue();
            UUID id = offerId.get();

            // El inicio ya pasó, pero el scheduler no activa un borrador (antes quedaba ACTIVA sin asientos).
            scheduler.runNow();
            assertThat(estado(id)).isEqualTo("BORRADOR");
            assertThat(offerService.list(null)).extracting(OfferResponse::offerId).doesNotContain(id);
            assertThat(offerService.list(itineraryId)).isEmpty();
            assertThatThrownBy(() -> offerService.get(id)).extracting("code").isEqualTo("OFFER_NOT_FOUND");
            assertThatThrownBy(() -> offerService.quota(id)).extracting("code").isEqualTo("OFFER_NOT_FOUND");
            assertThatThrownBy(() -> offerService.reserve(id, UUID.randomUUID(), "CC123"))
                    .extracting("code").isEqualTo("OFFER_NOT_FOUND");
            assertThat(quotaCounter.isInitialized(id)).isFalse();

            seguir.countDown();
            OfferResponse offer = creacion.get(30, TimeUnit.SECONDS);
            assertThat(offer.offerId()).isEqualTo(id);
            assertThat(offer.estado()).isEqualTo("ACTIVA");
            assertThat(offer.asientos()).containsExactly("1", "2", "3");
            assertThat(quotaCounter.remaining(id)).isEqualTo(3);
            assertThat(offerService.list(itineraryId)).extracting(OfferResponse::offerId).containsExactly(id);
        } finally {
            seguir.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void siLaReservaFallaSeLiberanLosAsientosYNoQuedaOferta() {
        when(seatInventory.reserveOfferSeats(any(), any(), anyList())).thenAnswer(inv -> {
            offerId.set(inv.getArgument(0));
            throw ApiException.unavailable("SEAT_INVENTORY_UNAVAILABLE", "Seat Inventory no está disponible");
        });

        assertThatThrownBy(this::crear).extracting("code").isEqualTo("SEAT_INVENTORY_UNAVAILABLE");
        verify(seatInventory).releaseOfferSeats(offerId.get());
        assertThat(estado(offerId.get())).isNull();
    }

    @Test
    void siTambienFallaLaLiberacionSeConservaElErrorOriginal() {
        when(seatInventory.reserveOfferSeats(any(), any(), anyList())).thenAnswer(inv -> {
            offerId.set(inv.getArgument(0));
            throw ApiException.unavailable("SEAT_INVENTORY_UNAVAILABLE", "timeout");
        });
        doThrow(ApiException.unavailable("RELEASE_FAILED", "caído")).when(seatInventory).releaseOfferSeats(any());

        assertThatThrownBy(this::crear).extracting("code").isEqualTo("SEAT_INVENTORY_UNAVAILABLE");
        verify(seatInventory).releaseOfferSeats(offerId.get());
        assertThat(estado(offerId.get())).isNull();
    }

    @Test
    void sinAsientosDisponiblesSeLiberaYNoQuedaOferta() {
        when(seatInventory.reserveOfferSeats(any(), any(), anyList())).thenAnswer(inv -> {
            offerId.set(inv.getArgument(0));
            return new SeatInventoryClient.OfferSeats(inv.getArgument(0), inv.getArgument(1), List.of(),
                    inv.getArgument(2));
        });

        assertThatThrownBy(this::crear).extracting("code").isEqualTo("NO_SEATS_AVAILABLE");
        verify(seatInventory).releaseOfferSeats(offerId.get());
        assertThat(estado(offerId.get())).isNull();
    }

    @Test
    void laLimpiezaDescartaSoloLosBorradoresDeMasDeCincoMinutos() {
        Instant now = Instant.now();
        Offer viejo = offerRepository.save(new Offer(itineraryId, new BigDecimal("49900"), 2, now,
                now.plus(1, ChronoUnit.HOURS), UUID.randomUUID(), now.minus(10, ChronoUnit.MINUTES)));
        Offer reciente = offerRepository.save(new Offer(itineraryId, new BigDecimal("49900"), 2, now,
                now.plus(1, ChronoUnit.HOURS), UUID.randomUUID(), now));

        scheduler.runNow();

        verify(seatInventory, atLeastOnce()).releaseOfferSeats(viejo.getId());
        assertThat(estado(viejo.getId())).isNull();
        verify(seatInventory, never()).releaseOfferSeats(reciente.getId());
        assertThat(estado(reciente.getId())).isEqualTo("BORRADOR");
        offerRepository.deleteById(reciente.getId());
    }

    @Test
    void siNoSePuedenLiberarLosAsientosElBorradorSeReintentaDespues() {
        // Antes de guardar el borrador: el scheduler en segundo plano también podría intentar descartarlo.
        doThrow(ApiException.unavailable("SEAT_INVENTORY_UNAVAILABLE", "caído"))
                .when(seatInventory).releaseOfferSeats(any());
        Instant now = Instant.now();
        Offer viejo = offerRepository.save(new Offer(itineraryId, new BigDecimal("49900"), 2, now,
                now.plus(1, ChronoUnit.HOURS), UUID.randomUUID(), now.minus(10, ChronoUnit.MINUTES)));

        scheduler.runNow();

        assertThat(estado(viejo.getId())).isEqualTo("BORRADOR");
        offerRepository.deleteById(viejo.getId());
    }

    @Test
    void unaRespuestaSinListaDeAsientosCuentaComoSinAsientos() {
        // Seat Inventory responde sin cuerpo (null) o con "reservados": null.
        when(seatInventory.reserveOfferSeats(any(), any(), anyList()))
                .thenAnswer(inv -> {
                    offerId.set(inv.getArgument(0));
                    return null;
                })
                .thenAnswer(inv -> {
                    offerId.set(inv.getArgument(0));
                    return new SeatInventoryClient.OfferSeats(inv.getArgument(0), inv.getArgument(1), null, null);
                });

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(this::crear).extracting("code").isEqualTo("NO_SEATS_AVAILABLE");
            verify(seatInventory).releaseOfferSeats(offerId.get());
            assertThat(estado(offerId.get())).isNull();
        }
    }

    @Test
    void siElBorradorDesaparecioDuranteLaReservaSeLiberanLosAsientos() {
        // La limpieza (u otro nodo) borró el borrador mientras Seat Inventory reservaba: no se puede programar.
        when(seatInventory.reserveOfferSeats(any(), any(), anyList())).thenAnswer(inv -> {
            offerId.set(inv.getArgument(0));
            jdbc.update("delete from offers where id = ?", (UUID) inv.getArgument(0));
            return new SeatInventoryClient.OfferSeats(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                    List.of());
        });

        assertThatThrownBy(this::crear).extracting("code").isEqualTo("OFFER_DRAFT_EXPIRED");
        verify(seatInventory).releaseOfferSeats(offerId.get());
        assertThat(estado(offerId.get())).isNull();
    }

    @Test
    void siNoSePuedeBorrarElBorradorSeConservaElErrorOriginalYLoRecogeLaLimpieza() {
        // Una fila que referencia al borrador (FK) hace fallar su borrado durante la compensación.
        when(seatInventory.reserveOfferSeats(any(), any(), anyList())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            offerId.set(id);
            jdbc.update("insert into offer_sales (id, offer_id, user_id, doc_id, seat_number, estado, creado_en, "
                    + "actualizado_en) values (?, ?, ?, 'CC1', '1', 'LIBERADA', now(), now())",
                    UUID.randomUUID(), id, UUID.randomUUID());
            throw ApiException.unavailable("SEAT_INVENTORY_UNAVAILABLE", "timeout");
        });

        assertThatThrownBy(this::crear).extracting("code").isEqualTo("SEAT_INVENTORY_UNAVAILABLE");
        UUID id = offerId.get();
        verify(seatInventory).releaseOfferSeats(id);
        assertThat(estado(id)).isEqualTo("BORRADOR");

        // Cuando el borrado vuelve a ser posible y el borrador ya es viejo, la limpieza lo descarta.
        jdbc.update("delete from offer_sales where offer_id = ?", id);
        jdbc.update("update offers set creado_en = ? where id = ?",
                Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)), id);
        scheduler.runNow();
        assertThat(estado(id)).isNull();
    }

    @Test
    void cancelarElViajeCierraLasOfertasPeroNoAnunciaLosBorradores() {
        reservaConAsientos();
        OfferResponse activa = crear();
        Offer pendiente = borrador(Instant.now());

        // Como EventConsumer: el manejador del evento ItineraryCancelled corre dentro de una transacción.
        tx.executeWithoutResult(s -> offerService.onItineraryCancelled(itineraryId));

        OfferResponse cerrada = offerService.get(activa.offerId());
        assertThat(cerrada.estado()).isEqualTo("CERRADA");
        assertThat(cerrada.motivoCierre()).isEqualTo("ITINERARIO_CANCELADO");
        assertThat(quotaCounter.isInitialized(activa.offerId())).isFalse();
        assertThat(estado(pendiente.getId())).isEqualTo("BORRADOR");

        // Cerrar de nuevo no cambia el motivo; cerrar un borrador no lo convierte en oferta visible.
        offerService.close(activa.offerId(), "CERRADA_MANUALMENTE");
        assertThat(offerService.get(activa.offerId()).motivoCierre()).isEqualTo("ITINERARIO_CANCELADO");
        offerService.close(pendiente.getId(), "CERRADA_MANUALMENTE");
        assertThat(estado(pendiente.getId())).isEqualTo("BORRADOR");
        assertThatThrownBy(() -> offerService.get(pendiente.getId())).extracting("code").isEqualTo("OFFER_NOT_FOUND");
        offerRepository.deleteById(pendiente.getId());
    }

    @Test
    void cupoYReservaPorRetencionSoloParaOfertasVisibles() {
        reservaConAsientos();
        when(seatInventory.hold(any(), anyString(), any(), any())).thenAnswer(inv ->
                new SeatInventoryClient.Hold(UUID.randomUUID(), inv.getArgument(0), List.of((String) inv.getArgument(1)),
                        inv.getArgument(2), inv.getArgument(3), "ACTIVA", Instant.now().plusSeconds(600)));
        OfferResponse offer = crear();
        var reserva = offerService.reserve(offer.offerId(), UUID.randomUUID(), "CC777").reservation();

        var quota = offerService.quota(offer.offerId());
        assertThat(quota.estado()).isEqualTo("ACTIVA");
        assertThat(quota.cupoMax()).isEqualTo(3);
        assertThat(quota.cupoRestante()).isEqualTo(2);
        assertThat(offerService.reservationByHold(offer.offerId(), reserva.holdId()).reservationId())
                .isEqualTo(reserva.reservationId());
        assertThatThrownBy(() -> offerService.reservationByHold(offer.offerId(), UUID.randomUUID()))
                .extracting("code").isEqualTo("OFFER_RESERVATION_NOT_FOUND");

        Offer pendiente = borrador(Instant.now());
        assertThatThrownBy(() -> offerService.reservationByHold(pendiente.getId(), reserva.holdId()))
                .extracting("code").isEqualTo("OFFER_NOT_FOUND");
        offerRepository.deleteById(pendiente.getId());
    }

    private OfferResponse crearProgramada() {
        reservaConAsientos();
        Instant start=Instant.now().plus(20,ChronoUnit.MINUTES);
        return offerService.create(new CreateOfferRequest(itineraryId,List.of("1","2","3"),new BigDecimal("49900"),2,
                start,start.plus(1,ChronoUnit.HOURS),1),UUID.randomUUID(),false,null);
    }

    @Test
    void editaAsientosConExitoYRechazaLaOfertaActiva() {
        OfferResponse scheduled=crearProgramada();
        UpdateOfferRequest seatsOnly=new UpdateOfferRequest(null,null,null,null,null,List.of("4","5"),false);
        OfferResponse changed=offerService.update(scheduled.offerId(),seatsOnly,null,true);
        assertThat(changed.precioOferta()).isEqualByComparingTo("49900");
        assertThat(changed.asientos()).containsExactly("4","5");
        assertThat(changed.permiteReprogramar()).isFalse();
        OfferResponse priceChanged=offerService.update(scheduled.offerId(),new UpdateOfferRequest(new BigDecimal("45000"),null,null,null,null,null,null),null,true);
        assertThat(priceChanged.precioOferta()).isEqualByComparingTo("45000");

        reservaConAsientos();
        UUID activeItinerary=UUID.randomUUID(); Instant activeStart=Instant.now().plus(30,ChronoUnit.SECONDS);
        when(catalog.itinerary(activeItinerary)).thenReturn(new CatalogClient.Itinerary(activeItinerary,companyId,new BigDecimal("95000"),"PROGRAMADO",activeStart.plus(2,ChronoUnit.DAYS)));
        OfferResponse active=offerService.create(new CreateOfferRequest(activeItinerary,List.of("1","2"),new BigDecimal("49900"),2,activeStart,activeStart.plus(1,ChronoUnit.HOURS)),UUID.randomUUID(),false,null);
        offerService.activate(active.offerId());
        assertThatThrownBy(()->offerService.update(active.offerId(),seatsOnly,null,true)).extracting("code").isEqualTo("OFFER_NOT_EDITABLE");
    }

    @Test
    void siFallaLaReservaDeAsientosAlEditarConservaLaOferta() {
        OfferResponse scheduled=crearProgramada();
        when(seatInventory.reserveOfferSeats(any(),any(),anyList())).thenThrow(ApiException.unavailable("SEAT_INVENTORY_UNAVAILABLE","caído"))
                .thenReturn(new SeatInventoryClient.OfferSeats(scheduled.offerId(),itineraryId,List.of("1","2","3"),List.of()));
        UpdateOfferRequest update=new UpdateOfferRequest(new BigDecimal("45000"),2,1,scheduled.inicio(),scheduled.fin(),List.of("4","5"),false);
        assertThatThrownBy(()->offerService.update(scheduled.offerId(),update,null,true)).extracting("code").isEqualTo("SEAT_INVENTORY_UNAVAILABLE");
        Offer persisted=offerRepository.findById(scheduled.offerId()).orElseThrow();
        assertThat(persisted.getPrecioOferta()).isEqualByComparingTo("49900");
        assertThat(offerService.get(scheduled.offerId()).asientos()).containsExactly("1","2","3");
    }

    @Test
    void cancelarOfertaProgramadaLiberaYMarcaMotivo() {
        OfferResponse scheduled=crearProgramada();
        var closed=offerService.cancelByOperator(scheduled.offerId(),null,true);
        assertThat(closed.estado()).isEqualTo("CERRADA");
        assertThat(closed.motivoCierre()).isEqualTo("CANCELADA_POR_OPERADOR");
        verify(seatInventory).releaseOfferSeats(scheduled.offerId());
    }

    @Test
    void otraEmpresaNoPuedeEditarNiCancelar() {
        OfferResponse scheduled=crearProgramada();
        UpdateOfferRequest update=new UpdateOfferRequest(new BigDecimal("45000"),2,1,scheduled.inicio(),scheduled.fin(),null,true);
        UUID foreignCompany=UUID.randomUUID();
        assertThatThrownBy(()->offerService.update(scheduled.offerId(),update,foreignCompany,false)).extracting("code").isEqualTo("OFFER_NOT_FOUND");
        assertThatThrownBy(()->offerService.cancelByOperator(scheduled.offerId(),foreignCompany,false)).extracting("code").isEqualTo("OFFER_NOT_FOUND");
    }

    @Test
    void duplicarUsaAsientosLibresCuandoLosOriginalesEstanOcupados() {
        OfferResponse original=crearProgramada();
        UUID target=UUID.randomUUID();
        when(catalog.itinerary(target)).thenReturn(new CatalogClient.Itinerary(target,companyId,new BigDecimal("190000"),"PROGRAMADO",Instant.now().plus(2,ChronoUnit.DAYS),"Bogotá","Cali","Bus Co"));
        when(seatInventory.reserveOfferSeats(any(),any(),anyList())).thenAnswer(inv->{
            List<String> asked=inv.getArgument(2);
            return new SeatInventoryClient.OfferSeats(inv.getArgument(0),inv.getArgument(1),asked.isEmpty()?List.of("A1","A2"):List.of(),asked.isEmpty()?List.of():asked);
        });
        Instant start=Instant.now().plus(30,ChronoUnit.MINUTES);
        OfferResponse copy=offerService.duplicate(original.offerId(),new DuplicateOfferRequest(target,start,start.plus(1,ChronoUnit.HOURS),null),null,true,UUID.randomUUID());
        assertThat(copy.cupoMax()).isEqualTo(original.cupoMax());
        assertThat(copy.maxPorUsuario()).isEqualTo(original.maxPorUsuario());
        assertThat(copy.asientos()).containsExactly("A1","A2");
        assertThat(copy.precioOferta()).isEqualByComparingTo("99800.00");
    }

    @Test
    void rellenaDatosAntiguosYRecortaFinAlReprogramarseElViaje() {
        Instant now=Instant.now();
        Offer old=new Offer(itineraryId,new BigDecimal("49900"),2,now.plusSeconds(60),now.plusSeconds(3600),UUID.randomUUID(),now);
        old.asignarAsientos(List.of("1","2"));old.programar();offerRepository.saveAndFlush(old);
        when(catalog.itinerary(itineraryId)).thenReturn(new CatalogClient.Itinerary(itineraryId,companyId,new BigDecimal("95000"),"PROGRAMADO",now.plusSeconds(7200),"Bogotá","Cali","Bus Co"));
        offerService.backfillItineraryDetails();
        Offer updated=offerRepository.findById(old.getId()).orElseThrow();
        assertThat(updated.getOrigen()).isEqualTo("Bogotá");
        assertThat(updated.getDestino()).isEqualTo("Cali");
        Instant departure=now.plusSeconds(1800).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        offerService.onItineraryRescheduled(itineraryId,departure);
        assertThat(offerRepository.findById(old.getId()).orElseThrow().getFin()).isEqualTo(departure.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    void vitrinaFiltraSinTildesYOrdenaActivasAntesDeProgramadas() {
        reservaConAsientos();
        Instant start=Instant.now().plusSeconds(60),end=start.plus(1,ChronoUnit.HOURS);
        when(catalog.itinerary(itineraryId)).thenReturn(new CatalogClient.Itinerary(itineraryId,companyId,new BigDecimal("95000"),"PROGRAMADO",end.plusSeconds(7200),"ÁguilaNorte","CaliDestino","Bus Co"));
        OfferResponse active=offerService.create(new CreateOfferRequest(itineraryId,List.of("1","2"),new BigDecimal("49900"),2,start,end),UUID.randomUUID(),false,null);
        offerService.activate(active.offerId());
        UUID nextItinerary=UUID.randomUUID();
        when(catalog.itinerary(nextItinerary)).thenReturn(new CatalogClient.Itinerary(nextItinerary,companyId,new BigDecimal("95000"),"PROGRAMADO",end.plusSeconds(7200),"ÁguilaNorte","CaliDestino","Bus Co"));
        OfferResponse scheduled=offerService.create(new CreateOfferRequest(nextItinerary,List.of("3","4"),new BigDecimal("49900"),2,start.plusSeconds(120),end.plusSeconds(120)),UUID.randomUUID(),false,null);
        var page=offerService.showcase("aguilanorte","calidestino",0,20);
        assertThat(page.getContent()).extracting(OfferService.ShowcaseOffer::offerId).containsExactly(active.offerId(),scheduled.offerId());
        assertThat(offerService.showcase("águilanorte","medellín",0,20).getContent()).isEmpty();
    }

    @Test
    void metricasDeOfertaYDeOperadorSoloVenLaEmpresaPropia() {
        UUID company=UUID.randomUUID();
        when(catalog.itinerary(itineraryId)).thenReturn(new CatalogClient.Itinerary(itineraryId,company,new BigDecimal("95000"),"PROGRAMADO",Instant.now().plus(2,ChronoUnit.DAYS)));
        reservaConAsientos();
        when(seatInventory.hold(any(), anyString(), any(), any())).thenAnswer(inv ->
                new SeatInventoryClient.Hold(UUID.randomUUID(), inv.getArgument(0), List.of((String) inv.getArgument(1)),
                        inv.getArgument(2), inv.getArgument(3), "ACTIVA", Instant.now().plusSeconds(600)));
        OfferResponse offer=crear();

        var empty=offerService.metrics(offer.offerId(),company,false);
        assertThat(empty.cupo()).isEqualTo(3);
        assertThat(empty.reservasCreadas()).isZero();
        assertThat(empty.conversion()).isEqualByComparingTo("0");
        assertThat(empty.tiempoEnAgotarseSegundos()).isNull();

        var reserva=offerService.reserve(offer.offerId(),UUID.randomUUID(),"CC1").reservation();
        offerService.onSeatSold(reserva.holdId());
        var sold=offerService.metrics(offer.offerId(),null,true);
        assertThat(sold.reservasCreadas()).isEqualTo(1);
        assertThat(sold.ventas()).isEqualTo(1);
        assertThat(sold.conversion()).isEqualByComparingTo("1");
        assertThat(sold.ingresos()).isEqualByComparingTo("49900");
        assertThat(sold.descuentoTotalOtorgado()).isEqualByComparingTo("45100");
        assertThatThrownBy(()->offerService.metrics(offer.offerId(),UUID.randomUUID(),false)).extracting("code").isEqualTo("OFFER_NOT_FOUND");

        Instant desde=Instant.now().minus(1,ChronoUnit.HOURS),hasta=Instant.now().plus(1,ChronoUnit.DAYS);
        var own=offerService.operatorMetrics(desde,hasta,company,false);
        assertThat(own.ofertas()).isEqualTo(1);
        assertThat(own.ventas()).isEqualTo(1);
        assertThat(own.ingresos()).isEqualByComparingTo("49900");
        assertThat(own.ofertaAgotadaMasRapido()).isEqualTo(offer.offerId());
        assertThat(offerService.operatorMetrics(desde,hasta,UUID.randomUUID(),false).ofertas()).isZero();
        assertThat(offerService.operatorMetrics(desde,hasta,null,true).ofertas()).isGreaterThanOrEqualTo(1);
        for (Instant[] rango : new Instant[][]{{null,hasta},{desde,null},{hasta,desde},{desde,desde.plus(91,ChronoUnit.DAYS)}}) {
            assertThatThrownBy(()->offerService.operatorMetrics(rango[0],rango[1],company,false)).extracting("code").isEqualTo("INVALID_METRICS_RANGE");
        }
    }

    @Test
    void listadoDelOperadorFiltraPorEmpresaEstadoYFechas() {
        UUID company=UUID.randomUUID();
        when(catalog.itinerary(itineraryId)).thenReturn(new CatalogClient.Itinerary(itineraryId,company,new BigDecimal("95000"),"PROGRAMADO",Instant.now().plus(2,ChronoUnit.DAYS)));
        OfferResponse scheduled=crearProgramada();
        Offer pendiente=borrador(Instant.now());

        var page=offerService.operatorOffers("programada",scheduled.inicio().minusSeconds(1),scheduled.inicio().plusSeconds(1),0,10,company,false);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).oferta().offerId()).isEqualTo(scheduled.offerId());
        assertThat(page.getContent().get(0).cupoVendido()).isZero();
        assertThat(page.getContent().get(0).cupoRestante()).isEqualTo(2);
        assertThat(offerService.operatorOffers(null,null,null,0,10,company,false).getTotalElements()).isEqualTo(1);
        assertThat(offerService.operatorOffers("CERRADA",null,null,0,10,company,false).getContent()).isEmpty();
        assertThat(offerService.operatorOffers(null,scheduled.inicio().plusSeconds(1),null,0,10,company,false).getContent()).isEmpty();
        assertThat(offerService.operatorOffers(null,null,scheduled.inicio().minusSeconds(1),0,10,company,false).getContent()).isEmpty();
        assertThat(offerService.operatorOffers(null,null,null,5,10,company,false).getContent()).isEmpty();
        assertThat(offerService.operatorOffers(null,null,null,0,100,null,true).getContent()).extracting(o->o.oferta().offerId()).contains(scheduled.offerId());
        offerRepository.deleteById(pendiente.getId());
    }

    @Test
    void editarValidaVentanaDescuentoCupoSalidaYCruces() {
        OfferResponse scheduled=crearProgramada();
        UUID id=scheduled.offerId();
        Instant inicio=scheduled.inicio(),fin=scheduled.fin();
        assertThatThrownBy(()->offerService.update(id,new UpdateOfferRequest(null,null,null,Instant.now().minusSeconds(60),null,null,null),null,true)).extracting("code").isEqualTo("INVALID_OFFER_WINDOW");
        assertThatThrownBy(()->offerService.update(id,new UpdateOfferRequest(null,null,null,null,inicio.plusSeconds(60),null,null),null,true)).extracting("code").isEqualTo("INVALID_OFFER_WINDOW");
        assertThatThrownBy(()->offerService.update(id,new UpdateOfferRequest(new BigDecimal("94000"),null,null,null,null,null,null),null,true)).extracting("code").isEqualTo("INVALID_DISCOUNT");
        assertThatThrownBy(()->offerService.update(id,new UpdateOfferRequest(new BigDecimal("10000"),null,null,null,null,null,null),null,true)).extracting("code").isEqualTo("INVALID_DISCOUNT");
        assertThatThrownBy(()->offerService.update(id,new UpdateOfferRequest(null,5,null,null,null,null,null),null,true)).extracting("code").isEqualTo("QUOTA_EXCEEDS_SEATS");
        assertThatThrownBy(()->offerService.update(id,new UpdateOfferRequest(null,2,3,null,null,null,null),null,true)).extracting("code").isEqualTo("QUOTA_EXCEEDS_SEATS");
        assertThatThrownBy(()->offerService.update(id,new UpdateOfferRequest(null,null,null,null,Instant.now().plus(3,ChronoUnit.DAYS),null,null),null,true)).extracting("code").isEqualTo("OFFER_ENDS_AFTER_DEPARTURE");

        OfferResponse next=offerService.create(new CreateOfferRequest(itineraryId,List.of("7","8"),new BigDecimal("49900"),2,
                fin.plus(1,ChronoUnit.HOURS),fin.plus(2,ChronoUnit.HOURS)),UUID.randomUUID(),false,null);
        assertThatThrownBy(()->offerService.update(id,new UpdateOfferRequest(null,null,null,null,next.inicio().plusSeconds(60),null,null),null,true)).extracting("code").isEqualTo("OFFER_OVERLAP");

        // Seat Inventory reserva menos asientos que el cupo: se restauran los originales.
        when(seatInventory.reserveOfferSeats(any(),any(),anyList())).thenAnswer(inv ->
                new SeatInventoryClient.OfferSeats(inv.getArgument(0),inv.getArgument(1),List.of("4"),List.of("5")))
                .thenAnswer(inv -> new SeatInventoryClient.OfferSeats(inv.getArgument(0),inv.getArgument(1),inv.getArgument(2),List.of()));
        assertThatThrownBy(()->offerService.update(id,new UpdateOfferRequest(null,null,null,null,null,List.of("4","5"),null),null,true)).extracting("code").isEqualTo("NO_SEATS_AVAILABLE");
        assertThat(offerService.get(id).asientos()).containsExactly("1","2","3");
        assertThat(offerService.get(id).inicio()).isCloseTo(inicio,within(1,ChronoUnit.MILLIS));
    }

    @Test
    void crearValidaVentanaCupoViajeDescuentoYEmpresa() {
        reservaConAsientos();
        Instant start=Instant.now().plus(20,ChronoUnit.MINUTES),end=start.plus(1,ChronoUnit.HOURS);
        BigDecimal price=new BigDecimal("49900");
        UUID user=UUID.randomUUID();
        assertThatThrownBy(()->offerService.create(new CreateOfferRequest(itineraryId,List.of("1"),price,1,end,start),user,false,null)).extracting("code").isEqualTo("INVALID_OFFER_WINDOW");
        assertThatThrownBy(()->offerService.create(new CreateOfferRequest(itineraryId,List.of("1"),price,1,Instant.now().minusSeconds(60),end),user,false,null)).extracting("code").isEqualTo("INVALID_OFFER_WINDOW");
        assertThatThrownBy(()->offerService.create(new CreateOfferRequest(itineraryId,List.of("1"),price,1,start,start.plusSeconds(60)),user,false,null)).extracting("code").isEqualTo("INVALID_OFFER_WINDOW");
        assertThatThrownBy(()->offerService.create(new CreateOfferRequest(itineraryId,List.of("1"," "),price,2,start,end),user,false,null)).extracting("code").isEqualTo("QUOTA_EXCEEDS_SEATS");
        assertThatThrownBy(()->offerService.create(new CreateOfferRequest(itineraryId,List.of("1","2"),price,1,start,end,2),user,false,null)).extracting("code").isEqualTo("MAX_PER_USER_EXCEEDS_QUOTA");
        assertThatThrownBy(()->offerService.create(new CreateOfferRequest(itineraryId,List.of("1"),new BigDecimal("94000"),1,start,end),user,false,null)).extracting("code").isEqualTo("INVALID_DISCOUNT");
        assertThatThrownBy(()->offerService.create(new CreateOfferRequest(itineraryId,List.of("1"),new BigDecimal("10000"),1,start,end),user,false,null)).extracting("code").isEqualTo("INVALID_DISCOUNT");
        assertThatThrownBy(()->offerService.create(new CreateOfferRequest(itineraryId,List.of("1"),price,1,start,end),user,true,UUID.randomUUID())).extracting("code").isEqualTo("NOT_YOUR_COMPANY");
        assertThatThrownBy(()->offerService.create(new CreateOfferRequest(itineraryId,List.of("1"),price,1,start,start.plus(3,ChronoUnit.DAYS)),user,false,null)).extracting("code").isEqualTo("OFFER_ENDS_AFTER_DEPARTURE");
        when(catalog.itinerary(itineraryId)).thenReturn(new CatalogClient.Itinerary(itineraryId,companyId,new BigDecimal("95000"),"CANCELADO",Instant.now().plus(2,ChronoUnit.DAYS)));
        assertThatThrownBy(()->offerService.create(new CreateOfferRequest(itineraryId,List.of("1"),price,1,start,end),user,false,null)).extracting("code").isEqualTo("ITINERARY_NOT_AVAILABLE");
        assertThat(offerService.list(itineraryId)).isEmpty();
    }

    @Test
    void cancelarOfertaActivaNoSePermite() {
        reservaConAsientos();
        OfferResponse active=crear();
        assertThatThrownBy(()->offerService.cancelByOperator(active.offerId(),companyId,false)).extracting("code").isEqualTo("OFFER_NOT_CANCELLABLE");
    }

    private UUID destinoDuplicado(UUID company) {
        UUID target=UUID.randomUUID();
        when(catalog.itinerary(target)).thenReturn(new CatalogClient.Itinerary(target,company,new BigDecimal("95000"),"PROGRAMADO",Instant.now().plus(2,ChronoUnit.DAYS)));
        return target;
    }

    @Test
    void duplicarRechazaOtraEmpresaYFaltaDeAsientos() {
        OfferResponse original=crearProgramada();
        Instant start=Instant.now().plus(30,ChronoUnit.MINUTES),end=start.plus(1,ChronoUnit.HOURS);
        UUID user=UUID.randomUUID();

        UUID foreign=destinoDuplicado(UUID.randomUUID());
        assertThatThrownBy(()->offerService.duplicate(original.offerId(),new DuplicateOfferRequest(foreign,start,end,null),null,true,user)).extracting("code").isEqualTo("ITINERARY_NOT_FOUND");
        UUID own=destinoDuplicado(companyId);
        assertThatThrownBy(()->offerService.duplicate(original.offerId(),new DuplicateOfferRequest(own,start,end,null),UUID.randomUUID(),false,user)).extracting("code").isEqualTo("OFFER_NOT_FOUND");

        // Asientos indicados: si no alcanzan para el cupo, no hay respaldo automático.
        when(seatInventory.reserveOfferSeats(any(),any(),anyList())).thenAnswer(inv ->
                new SeatInventoryClient.OfferSeats(inv.getArgument(0),inv.getArgument(1),List.of("9"),List.of("10")));
        assertThatThrownBy(()->offerService.duplicate(original.offerId(),new DuplicateOfferRequest(own,start,end,List.of("9","10")),companyId,false,user)).extracting("code").isEqualTo("NO_SEATS_AVAILABLE");

        // Asientos automáticos: ni los originales ni los libres alcanzan.
        assertThatThrownBy(()->offerService.duplicate(original.offerId(),new DuplicateOfferRequest(own,start,end,null),companyId,false,user)).extracting("code").isEqualTo("NO_SEATS_AVAILABLE");

        // Asientos automáticos: la reserva de los originales falla y la de libres también -> error original.
        when(seatInventory.reserveOfferSeats(any(),any(),anyList())).thenThrow(ApiException.unavailable("SEAT_INVENTORY_UNAVAILABLE","caído"));
        assertThatThrownBy(()->offerService.duplicate(original.offerId(),new DuplicateOfferRequest(own,start,end,null),companyId,false,user)).extracting("code").isEqualTo("SEAT_INVENTORY_UNAVAILABLE");
        assertThat(offerService.list(own)).isEmpty();
    }

    @Test
    void duplicarConAsientosAutomaticosSeRecuperaSiFallaLaReservaDeLosOriginales() {
        OfferResponse original=crearProgramada();
        UUID own=destinoDuplicado(companyId);
        when(seatInventory.reserveOfferSeats(any(),any(),anyList())).thenAnswer(inv -> {
            List<String> asked=inv.getArgument(2);
            if(!asked.isEmpty()) throw ApiException.unavailable("SEAT_INVENTORY_UNAVAILABLE","caído");
            return new SeatInventoryClient.OfferSeats(inv.getArgument(0),inv.getArgument(1),List.of("B1","B2"),List.of());
        });
        Instant start=Instant.now().plus(30,ChronoUnit.MINUTES);
        OfferResponse copy=offerService.duplicate(original.offerId(),new DuplicateOfferRequest(own,start,start.plus(1,ChronoUnit.HOURS),null),companyId,false,UUID.randomUUID());
        assertThat(copy.asientos()).containsExactly("B1","B2");
    }

    @Test
    void reprogramarElViajeMasTardeNoRecortaElFin() {
        OfferResponse scheduled=crearProgramada();
        Instant later=Instant.now().plus(3,ChronoUnit.DAYS);
        offerService.onItineraryRescheduled(itineraryId,later);
        Offer updated=offerRepository.findById(scheduled.offerId()).orElseThrow();
        assertThat(updated.getFin()).isCloseTo(scheduled.fin(),within(1,ChronoUnit.MILLIS));
        assertThat(updated.getAgotadaEn()).isNull();
    }

    @Test
    void crearSinReprogramacionYSinFechaDeSalidaConservaLaPreferencia() {
        reservaConAsientos();
        when(catalog.itinerary(itineraryId)).thenReturn(new CatalogClient.Itinerary(itineraryId,companyId,new BigDecimal("95000"),"PROGRAMADO",null));
        Instant start=Instant.now().plus(20,ChronoUnit.MINUTES);
        OfferResponse offer=offerService.create(new CreateOfferRequest(itineraryId,List.of("1","2"),new BigDecimal("49900"),2,
                start,start.plus(1,ChronoUnit.HOURS),1,false),UUID.randomUUID(),false,null);
        assertThat(offer.permiteReprogramar()).isFalse();
        assertThat(offer.estado()).isEqualTo("PROGRAMADA");
    }

    @Test
    void duplicarRechazaRespuestasVaciasDeSeatInventory() {
        OfferResponse original=crearProgramada();
        UUID own=destinoDuplicado(companyId);
        Instant start=Instant.now().plus(30,ChronoUnit.MINUTES),end=start.plus(1,ChronoUnit.HOURS);
        UUID user=UUID.randomUUID();

        // Asientos automáticos: los originales no responden y los libres llegan sin lista.
        when(seatInventory.reserveOfferSeats(any(),any(),anyList())).thenReturn(null)
                .thenReturn(new SeatInventoryClient.OfferSeats(UUID.randomUUID(),own,null,List.of()));
        assertThatThrownBy(()->offerService.duplicate(original.offerId(),new DuplicateOfferRequest(own,start,end,null),companyId,false,user)).extracting("code").isEqualTo("NO_SEATS_AVAILABLE");

        // Asientos automáticos: la reserva de libres falla después de una respuesta insuficiente.
        when(seatInventory.reserveOfferSeats(any(),any(),anyList())).thenReturn(new SeatInventoryClient.OfferSeats(UUID.randomUUID(),own,null,List.of()))
                .thenThrow(ApiException.unavailable("SEAT_INVENTORY_UNAVAILABLE","caído"));
        assertThatThrownBy(()->offerService.duplicate(original.offerId(),new DuplicateOfferRequest(own,start,end,null),companyId,false,user)).extracting("code").isEqualTo("SEAT_INVENTORY_UNAVAILABLE");

        // Asientos indicados: Seat Inventory no responde o responde sin lista.
        doReturn(null,new SeatInventoryClient.OfferSeats(UUID.randomUUID(),own,null,List.of()))
                .when(seatInventory).reserveOfferSeats(any(),any(),anyList());
        DuplicateOfferRequest manual=new DuplicateOfferRequest(own,start,end,List.of("9","10"));
        assertThatThrownBy(()->offerService.duplicate(original.offerId(),manual,companyId,false,user)).extracting("code").isEqualTo("NO_SEATS_AVAILABLE");
        assertThatThrownBy(()->offerService.duplicate(original.offerId(),manual,companyId,false,user)).extracting("code").isEqualTo("NO_SEATS_AVAILABLE");

        // Asientos indicados: si la reserva falla no se intenta con asientos libres.
        when(seatInventory.reserveOfferSeats(any(),any(),anyList())).thenThrow(ApiException.unavailable("SEAT_INVENTORY_UNAVAILABLE","caído"));
        assertThatThrownBy(()->offerService.duplicate(original.offerId(),manual,companyId,false,user)).extracting("code").isEqualTo("SEAT_INVENTORY_UNAVAILABLE");
        assertThat(offerService.list(own)).isEmpty();
    }

    @Test
    void editarFallaSiSeatInventoryNoDevuelveAsientosYRestauraLosOriginales() {
        OfferResponse scheduled=crearProgramada();
        UUID id=scheduled.offerId();
        when(seatInventory.reserveOfferSeats(any(),any(),anyList())).thenReturn(null)
                .thenReturn(new SeatInventoryClient.OfferSeats(id,itineraryId,null,List.of()))
                .thenAnswer(inv -> new SeatInventoryClient.OfferSeats(inv.getArgument(0),inv.getArgument(1),inv.getArgument(2),List.of()));
        UpdateOfferRequest seats=new UpdateOfferRequest(null,null,null,null,null,List.of("4","5"),null);
        assertThatThrownBy(()->offerService.update(id,seats,null,true)).extracting("code").isEqualTo("NO_SEATS_AVAILABLE");
        // La restauración también falla: se conserva el error original y la oferta no cambia.
        when(seatInventory.reserveOfferSeats(any(),any(),anyList())).thenReturn(new SeatInventoryClient.OfferSeats(id,itineraryId,null,List.of()))
                .thenThrow(ApiException.unavailable("SEAT_INVENTORY_UNAVAILABLE","caído"));
        assertThatThrownBy(()->offerService.update(id,seats,null,true)).extracting("code").isEqualTo("NO_SEATS_AVAILABLE");
        assertThat(offerService.get(id).asientos()).containsExactly("1","2","3");
    }

    @Test
    void editarAsientosDeOfertaConContadorLoReinicia() {
        OfferResponse scheduled=crearProgramada();
        UUID id=scheduled.offerId();
        quotaCounter.initialize(id,2,List.of("1","2","3"));
        reservaConAsientos();
        OfferResponse changed=offerService.update(id,new UpdateOfferRequest(null,2,2,null,null,List.of(" 7 ","8",""),true),companyId,false);
        assertThat(changed.asientos()).containsExactly("7","8");
        assertThat(quotaCounter.isInitialized(id)).isTrue();
        assertThat(quotaCounter.remaining(id)).isEqualTo(2);
        quotaCounter.delete(id);
    }
}
