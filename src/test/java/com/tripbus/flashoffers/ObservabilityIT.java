package com.tripbus.flashoffers;

import com.sun.net.httpserver.HttpServer;
import com.tripbus.flashoffers.common.events.EventPublisher;
import com.tripbus.flashoffers.service.OfferMeters;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HT-10 con el contexto real y las trazas encendidas: el RestClient.Builder de Spring Boot (el que usan
 * CatalogClient y SeatInventoryClient) manda traceparent, los eventos del stream lo llevan y las métricas de
 * ofertas salen en /actuator/prometheus.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.tracing.sampling.probability=1.0", "tripbus.events.stream=tripbus:events:obs"})
@AutoConfigureObservability
@ActiveProfiles("it")
class ObservabilityIT extends ContainersIT {

    @Autowired
    private RestClient.Builder builder;
    @Autowired
    private Tracer tracer;
    @Autowired
    private EventPublisher events;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private OfferMeters meters;
    @LocalServerPort
    private int port;

    @Test
    void lasLlamadasHttpSalientesPropaganElContextoDeTraza() throws Exception {
        AtomicReference<String> traceparent = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            traceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            Span span = tracer.nextSpan().name("prueba").start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                builder.baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build()
                        .get().uri("/itineraries/x").retrieve().toBodilessEntity();
            } finally {
                span.end();
            }
            assertThat(traceparent.get()).isNotNull().contains(span.context().traceId());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void losEventosDelStreamLlevanElContextoDeTraza() {
        Span span = tracer.nextSpan().name("reserva").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            events.publish("OfferQuotaUpdated", Map.of("cupoRestante", 1));
        } finally {
            span.end();
        }
        List<MapRecord<String, Object, Object>> records =
                redis.opsForStream().read(StreamOffset.fromStart("tripbus:events:obs"));
        assertThat(records).isNotEmpty();
        assertThat(String.valueOf(records.getLast().getValue().get("traceparent"))).contains(span.context().traceId());
    }

    @Test
    void lasMetricasDeOfertasSeExponenEnPrometheus() {
        meters.reservation(OfferMeters.ReservationResult.SIN_TURNO);
        String body = RestClient.create().get().uri("http://localhost:" + port + "/actuator/prometheus")
                .retrieve().body(String.class);
        assertThat(body).contains("tripbus_reservas_oferta_total{")
                .contains("resultado=\"sin_turno\"")
                .contains("resultado=\"limite_usuario\"")
                .contains("tripbus_ofertas_activadas_total{");
    }
}
