package com.tripbus.flashoffers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.flashoffers.client.CatalogClient;
import com.tripbus.flashoffers.client.SeatInventoryClient;
import com.tripbus.flashoffers.service.OfferService;
import com.tripbus.flashoffers.web.dto.CreateOfferRequest;
import com.tripbus.flashoffers.web.dto.OfferResponse;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Base de las pruebas de integración del sprint 6 (sala de espera, antiabuso, automáticas y recurrentes). Comparten un
 * único contexto de Spring: los lotes de la sala y el job automático no corren solos (intervalo de 1 h); las pruebas
 * los invocan con runNow(). Lotes de 2 personas para probarlos con pocos usuarios.
 */
@SpringBootTest(properties = {
        "tripbus.offers.queue.batch-interval=1h",
        "tripbus.offers.queue.batch-size=2",
        "tripbus.jobs.auto-offers-ms=3600000"})
@ActiveProfiles("it")
abstract class Sprint6IT extends ContainersIT {

    static final BigDecimal BASE = new BigDecimal("100000");

    @Autowired
    OfferService offerService;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    SeatInventoryClient seatInventory;
    @MockitoBean
    CatalogClient catalog;

    final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void simularServicios() {
        when(seatInventory.reserveOfferSeats(any(), any(), anyList())).thenAnswer(inv ->
                new SeatInventoryClient.OfferSeats(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), List.of()));
        when(seatInventory.hold(any(), anyString(), any(), any())).thenAnswer(inv ->
                new SeatInventoryClient.Hold(UUID.randomUUID(), inv.getArgument(0), List.of((String) inv.getArgument(1)),
                        inv.getArgument(2), inv.getArgument(3), "ACTIVA", Instant.now().plusSeconds(600)));
    }

    /** Un viaje programado de la empresa, que sale en {@code salida}. */
    UUID viaje(Instant salida) {
        UUID itinerary = UUID.randomUUID();
        when(catalog.itinerary(itinerary)).thenReturn(new CatalogClient.Itinerary(itinerary, companyId, BASE,
                "PROGRAMADO", salida, "Bogotá", "Cali", "Expreso"));
        return itinerary;
    }

    /** Oferta de cupo {@code cupo} que empieza en {@code inicio} y dura una hora. */
    OfferResponse oferta(int cupo, Instant inicio, boolean salaEspera) {
        UUID itinerary = viaje(inicio.plusSeconds(86_400));
        List<String> asientos = IntStream.rangeClosed(1, cupo).mapToObj(String::valueOf).toList();
        return offerService.create(new CreateOfferRequest(itinerary, asientos, new BigDecimal("60000"), cupo, inicio,
                inicio.plusSeconds(3600), null, null, salaEspera), UUID.randomUUID(), false, null);
    }

    static String documento() {
        return "CC" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Payloads de los eventos de un tipo publicados para la oferta. */
    List<JsonNode> eventos(String type, UUID offerId) {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(StreamOffset.fromStart("tripbus:events:test"));
        return records == null ? List.of() : records.stream()
                .filter(r -> type.equals(r.getValue().get("type")))
                .map(r -> read(String.valueOf(r.getValue().get("payload"))))
                .filter(p -> offerId.toString().equals(p.path("offerId").asText()))
                .toList();
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
