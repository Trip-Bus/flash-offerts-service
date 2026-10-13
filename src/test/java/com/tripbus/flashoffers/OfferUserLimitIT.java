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
import java.time.Duration;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US-058: límite de cupos por persona (sumando sus documentos) y reconciliación del contador de Redis con las ventas
 * de la BD. PostgreSQL y Redis reales (Testcontainers); la reconciliación corre cada segundo.
 */
@SpringBootTest(properties = "tripbus.jobs.quota-reconcile-ms=1000")
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
class OfferUserLimitIT {

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
        when(seatInventory.hold(any(), anyString(), any(), any())).thenAnswer(inv -> hold(inv.getArgument(1),
                inv.getArgument(2), inv.getArgument(3)));
    }

    private static SeatInventoryClient.Hold hold(String seat, UUID userId, UUID offerId) {
        return new SeatInventoryClient.Hold(UUID.randomUUID(), offerId, List.of(seat), userId, offerId, "ACTIVA",
                Instant.now().plusSeconds(600));
    }

    private OfferResponse nuevaOferta(int cupo, Integer maxPorUsuario) {
        List<String> asientos = IntStream.rangeClosed(1, cupo).mapToObj(String::valueOf).toList();
        return offerService.create(new CreateOfferRequest(itineraryId, asientos, new BigDecimal("49900"), cupo,
                Instant.now(), Instant.now().plus(1, ChronoUnit.HOURS), maxPorUsuario), UUID.randomUUID(), false, null);
    }

    private long ventasVivas(UUID offerId, UUID userId) {
        return jdbc.queryForObject("select count(*) from offer_sales where offer_id = ? and user_id = ? "
                + "and estado in ('RESERVADA', 'VENDIDA')", Long.class, offerId, userId);
    }

    @Test
    void laMismaPersonaConTresDocumentosSoloTomaDosCuposYLaTerceraNoGastaCupo() {
        OfferResponse offer = nuevaOferta(5, null);
        assertThat(offer.maxPorUsuario()).as("por defecto 2").isEqualTo(2);
        UUID user = UUID.randomUUID();

        assertThat(offerService.reserve(offer.offerId(), user, "CC100").created()).isTrue();
        assertThat(offerService.reserve(offer.offerId(), user, "CC200").created()).isTrue();
        assertThat(quotaCounter.remaining(offer.offerId())).isEqualTo(3);

        assertThatThrownBy(() -> offerService.reserve(offer.offerId(), user, "CC300"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getCode()).isEqualTo("USER_LIMIT_REACHED");
                    assertThat(api.getStatus().value()).isEqualTo(409);
                });
        assertThat(quotaCounter.remaining(offer.offerId())).as("la tercera no descuenta cupo").isEqualTo(3);
        verify(seatInventory, times(2)).hold(any(), anyString(), eq(user), eq(offer.offerId()));
        assertThat(ventasVivas(offer.offerId(), user)).isEqualTo(2);

        // Repetir un documento que ya tiene reserva sigue siendo idempotente, aunque esté en el límite.
        assertThat(offerService.reserve(offer.offerId(), user, "CC100").created()).isFalse();
        // Al liberar una, vuelve a tener cupo.
        String holdId = jdbc.queryForObject("select hold_id::text from offer_sales where offer_id = ? and doc_id = 'CC100'",
                String.class, offer.offerId());
        offerService.onHoldReleased(UUID.fromString(holdId));
        assertThat(offerService.reserve(offer.offerId(), user, "CC100").created()).as("reusa su venta liberada").isTrue();
        assertThatThrownBy(() -> offerService.reserve(offer.offerId(), user, "CC300"))
                .extracting("code").isEqualTo("USER_LIMIT_REACHED");
        assertThat(ventasVivas(offer.offerId(), user)).isEqualTo(2);
    }

    @Test
    void elMismoDocumentoEnParaleloDevuelveLaMismaReservaSinGastarOtroCupo() throws Exception {
        OfferResponse offer = nuevaOferta(5, null);
        UUID user = UUID.randomUUID();
        when(seatInventory.hold(any(), anyString(), any(), any())).thenAnswer(inv -> {
            Thread.sleep(200);
            return hold(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<OfferService.ReserveResult>> futuros = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futuros.add(pool.submit(() -> {
                salida.await();
                return offerService.reserve(offer.offerId(), user, "CC777");
            }));
        }
        salida.countDown();
        OfferService.ReserveResult a = futuros.get(0).get(60, TimeUnit.SECONDS);
        OfferService.ReserveResult b = futuros.get(1).get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(a.created() ^ b.created()).as("solo una crea la reserva").isTrue();
        assertThat(a.reservation().reservationId()).isEqualTo(b.reservation().reservationId());
        assertThat(quotaCounter.remaining(offer.offerId())).isEqualTo(4);
    }

    @Test
    void siLaRetencionFallaElCupoVuelveYNoQuedaVenta() {
        OfferResponse offer = nuevaOferta(3, null);
        UUID user = UUID.randomUUID();
        when(seatInventory.hold(any(), anyString(), any(), any())).thenThrow(new IllegalStateException("caído"));

        assertThatThrownBy(() -> offerService.reserve(offer.offerId(), user, "CC1"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(quotaCounter.remaining(offer.offerId())).isEqualTo(3);
        assertThat(redis.opsForList().size("offer:{" + offer.offerId() + "}:seats")).isEqualTo(3);
        assertThat(ventasVivas(offer.offerId(), user)).isZero();
    }

    @Test
    void dosReservasSimultaneasDeLaMismaPersonaConMaximoUnoSoloLograUna() throws Exception {
        OfferResponse offer = nuevaOferta(5, 1);
        UUID user = UUID.randomUUID();
        // Retención lenta: sin el lock por (oferta, usuario) ambas pasarían el conteo antes de guardar.
        when(seatInventory.hold(any(), anyString(), any(), any())).thenAnswer(inv -> {
            Thread.sleep(200);
            return hold(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
        });

        int intentos = 6;
        ExecutorService pool = Executors.newFixedThreadPool(intentos);
        CountDownLatch salida = new CountDownLatch(1);
        AtomicInteger creadas = new AtomicInteger();
        AtomicInteger limitadas = new AtomicInteger();
        List<Future<?>> futuros = new ArrayList<>();
        for (int i = 0; i < intentos; i++) {
            String doc = "CC" + i;
            futuros.add(pool.submit(() -> {
                salida.await();
                try {
                    if (offerService.reserve(offer.offerId(), user, doc).created()) {
                        creadas.incrementAndGet();
                    }
                } catch (ApiException e) {
                    if ("USER_LIMIT_REACHED".equals(e.getCode())) {
                        limitadas.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        salida.countDown();
        for (Future<?> f : futuros) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(creadas.get()).isEqualTo(1);
        assertThat(limitadas.get()).isEqualTo(intentos - 1);
        assertThat(ventasVivas(offer.offerId(), user)).isEqualTo(1);
        assertThat(quotaCounter.remaining(offer.offerId())).isEqualTo(4);
    }

    @Test
    void elMaximoPorUsuarioNoPuedeSuperarElCupoYPorDefectoSeAjustaAlCupo() {
        assertThatThrownBy(() -> nuevaOferta(2, 3))
                .extracting("code").isEqualTo("MAX_PER_USER_EXCEEDS_QUOTA");
        assertThat(nuevaOferta(1, null).maxPorUsuario()).isEqualTo(1);
    }

    @Test
    void unContadorDesajustadoSeCorrigeTrasDosRevisionesSeguidas() {
        OfferResponse offer = nuevaOferta(3, null);
        UUID offerId = offer.offerId();
        assertThat(offerService.reserve(offerId, UUID.randomUUID(), "CC1").created()).isTrue();
        assertThat(quotaCounter.remaining(offerId)).isEqualTo(2);

        // Simula una caída entre el descuento en Redis y el guardado de la venta: cupo y asiento perdidos.
        assertThat(quotaCounter.reserve(offerId)).isNotNull();
        assertThat(quotaCounter.remaining(offerId)).isEqualTo(1);

        // Se corrige en la segunda revisión que ve el mismo desajuste (las revisiones van separadas >= 1 s).
        await().atLeast(Duration.ofMillis(900)).atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
                .until(() -> Integer.valueOf(2).equals(quotaCounter.remaining(offerId)));
        assertThat(redis.opsForList().size("offer:{" + offerId + "}:seats")).isEqualTo(2);

        // El cupo recuperado se puede vender.
        assertThat(offerService.reserve(offerId, UUID.randomUUID(), "CC2").created()).isTrue();
        assertThat(offerService.reserve(offerId, UUID.randomUUID(), "CC3").created()).isTrue();
        assertThat(offerService.get(offerId).estado()).isEqualTo("AGOTADA");
        assertThat(jdbc.queryForObject("select count(distinct seat_number) from offer_sales where offer_id = ?",
                Long.class, offerId)).isEqualTo(3L);
    }
}
