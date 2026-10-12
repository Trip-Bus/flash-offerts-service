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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
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

    @MockitoBean
    private SeatInventoryClient seatInventory;
    @MockitoBean
    private CatalogClient catalog;

    private final UUID companyId = UUID.randomUUID();
    private final UUID itineraryId = UUID.randomUUID();
    private final AtomicReference<UUID> offerId = new AtomicReference<>();

    @BeforeEach
    void simular() {
        offerId.set(null);
        when(catalog.itinerary(itineraryId)).thenReturn(new CatalogClient.Itinerary(itineraryId, companyId,
                new BigDecimal("95000"), "PROGRAMADO", Instant.now().plus(2, ChronoUnit.DAYS)));
    }

    private OfferResponse crear() {
        return offerService.create(new CreateOfferRequest(itineraryId, List.of("1", "2", "3"), new BigDecimal("49900"),
                3, Instant.now().minusSeconds(5), Instant.now().plus(1, ChronoUnit.HOURS)), UUID.randomUUID(),
                false, null);
    }

    private String estado(UUID id) {
        List<String> estados = jdbc.queryForList("select estado from offers where id = ?", String.class, id);
        return estados.isEmpty() ? null : estados.get(0);
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
}
