package com.tripbus.flashoffers;

import com.tripbus.flashoffers.client.CatalogClient;
import com.tripbus.flashoffers.client.SeatInventoryClient;
import com.tripbus.flashoffers.common.error.ApiException;
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
import org.springframework.data.redis.core.StringRedisTemplate;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * US-058: 1.000 solicitudes concurrentes sobre una oferta con cupo 20 → exactamente 20
 * reservas y el contador nunca baja de cero. Gate de la HU: corre contra PostgreSQL 16 y Redis 7
 * reales (Testcontainers, requiere Docker): Flyway aplica las migraciones REALES de db/migration y el
 * contador de cupo es el script Lua real de QuotaCounter. Catalog y Seat Inventory se simulan.
 */
@SpringBootTest
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
class FlashOfferQuotaIT {

    private static final int SOLICITUDES = 1000;
    private static final int CUPO = 20;

    // Contenedores nuevos en cada ejecución: la base y el Redis empiezan vacíos (no hace falta flyway.clean).
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    private OfferService offerService;
    @Autowired
    private QuotaCounter quotaCounter;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private SeatInventoryClient seatInventory;
    @MockitoBean
    private CatalogClient catalog;

    private final UUID companyId = UUID.randomUUID();
    private final UUID itineraryId = UUID.randomUUID();

    @BeforeEach
    void simular() {
        when(catalog.itinerary(itineraryId)).thenReturn(new CatalogClient.Itinerary(itineraryId, companyId,
                new BigDecimal("95000"), "PROGRAMADO", Instant.now().plus(2, ChronoUnit.DAYS)));
        when(seatInventory.reserveOfferSeats(any(), any(), anyList())).thenAnswer(inv ->
                new SeatInventoryClient.OfferSeats(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), List.of()));
        when(seatInventory.hold(any(), anyString(), any(), any())).thenAnswer(inv ->
                new SeatInventoryClient.Hold(UUID.randomUUID(), inv.getArgument(0), List.of((String) inv.getArgument(1)),
                        inv.getArgument(2), inv.getArgument(3), "ACTIVA", Instant.now().plusSeconds(600)));
    }

    private OfferResponse nuevaOferta(int cupo) {
        List<String> asientos = IntStream.rangeClosed(1, cupo).mapToObj(String::valueOf).toList();
        return offerService.create(new CreateOfferRequest(itineraryId, asientos, new BigDecimal("49900"), cupo,
                Instant.now(), Instant.now().plus(1, ChronoUnit.HOURS)), UUID.randomUUID(), false, null);
    }

    @Test
    void milSolicitudesConcurrentesVendenExactamenteElCupo() throws Exception {
        OfferResponse offer = nuevaOferta(CUPO);
        assertThat(offer.estado()).isEqualTo("ACTIVA");
        String quotaKey = "offer:{" + offer.offerId() + "}:quota";

        AtomicBoolean corriendo = new AtomicBoolean(true);
        AtomicInteger minimoObservado = new AtomicInteger(Integer.MAX_VALUE);
        Thread vigilante = new Thread(() -> {
            while (corriendo.get()) {
                String v = redis.opsForValue().get(quotaKey);
                if (v != null) {
                    minimoObservado.accumulateAndGet(Integer.parseInt(v), Math::min);
                }
            }
        });
        vigilante.start();

        ExecutorService pool = Executors.newFixedThreadPool(200);
        CountDownLatch salida = new CountDownLatch(1);
        AtomicInteger vendidas = new AtomicInteger();
        AtomicInteger agotadas = new AtomicInteger();
        List<Future<?>> futuros = new ArrayList<>();
        for (int i = 0; i < SOLICITUDES; i++) {
            futuros.add(pool.submit(() -> {
                salida.await();
                try {
                    if (offerService.reserve(offer.offerId(), UUID.randomUUID(), "CC" + UUID.randomUUID().toString()
                            .substring(0, 8)).created()) {
                        vendidas.incrementAndGet();
                    }
                } catch (ApiException e) {
                    agotadas.incrementAndGet();
                }
                return null;
            }));
        }
        salida.countDown();
        for (Future<?> f : futuros) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        corriendo.set(false);
        vigilante.join();

        assertThat(vendidas.get()).isEqualTo(CUPO);
        assertThat(agotadas.get()).isEqualTo(SOLICITUDES - CUPO);
        assertThat(minimoObservado.get()).as("el contador nunca baja de cero").isGreaterThanOrEqualTo(0);
        assertThat(quotaCounter.remaining(offer.offerId())).isZero();
        assertThat(jdbc.queryForObject("select count(distinct seat_number) from offer_sales where offer_id = ?",
                Long.class, offer.offerId())).isEqualTo((long) CUPO);
        assertThat(offerService.get(offer.offerId()).estado()).isEqualTo("AGOTADA");
    }

    @Test
    void laMismaPersonaNoReservaDosVecesYAlLiberarseVuelveElCupo() {
        OfferResponse offer = nuevaOferta(2);
        UUID user = UUID.randomUUID();
        var primera = offerService.reserve(offer.offerId(), user, "CC123456");
        var segunda = offerService.reserve(offer.offerId(), user, "CC123456");
        assertThat(primera.created()).isTrue();
        assertThat(segunda.created()).isFalse();
        assertThat(segunda.reservation().reservationId()).isEqualTo(primera.reservation().reservationId());
        assertThat(quotaCounter.remaining(offer.offerId())).isEqualTo(1);

        offerService.reserve(offer.offerId(), UUID.randomUUID(), "CC999999");
        assertThat(offerService.get(offer.offerId()).estado()).isEqualTo("AGOTADA");

        offerService.onHoldReleased(primera.reservation().holdId());
        assertThat(quotaCounter.remaining(offer.offerId())).isEqualTo(1);
        assertThat(offerService.get(offer.offerId()).estado()).isEqualTo("ACTIVA");
    }

    @Test
    void unOperadorNoPuedeCrearOfertasDeOtraEmpresa() {
        assertThatThrownBy(() -> offerService.create(new CreateOfferRequest(itineraryId, List.of("1"),
                new BigDecimal("49900"), 1, Instant.now(), Instant.now().plus(1, ChronoUnit.HOURS)),
                UUID.randomUUID(), true, UUID.randomUUID()))
                .extracting("code").isEqualTo("NOT_YOUR_COMPANY");
    }

    @Test
    void elPrecioDeOfertaDebeSerMenorAlBase() {
        assertThatThrownBy(() -> offerService.create(new CreateOfferRequest(itineraryId, List.of("1"),
                new BigDecimal("95000"), 1, Instant.now(), Instant.now().plus(1, ChronoUnit.HOURS)),
                UUID.randomUUID(), false, null))
                .extracting("code").isEqualTo("OFFER_PRICE_NOT_LOWER");
    }
}
