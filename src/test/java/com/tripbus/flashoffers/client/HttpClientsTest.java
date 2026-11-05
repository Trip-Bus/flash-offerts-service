package com.tripbus.flashoffers.client;

import com.tripbus.flashoffers.common.error.ApiException;
import com.tripbus.flashoffers.config.OffersProperties;
import com.tripbus.flashoffers.config.RequestIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Catalog y Seat Inventory: rutas internas, token interno, requestId y traducción de errores. */
class HttpClientsTest {

    private FakeHttpServer server;
    private CatalogClient catalog;
    private SeatInventoryClient seats;

    @BeforeEach
    void levantar() throws IOException {
        server = new FakeHttpServer();
        OffersProperties props = new OffersProperties(server.url(), server.url());
        catalog = new CatalogClient(props, RestClient.builder(), "secreto");
        seats = new SeatInventoryClient(props, RestClient.builder(), "secreto");
    }

    @AfterEach
    void apagar() {
        server.close();
        MDC.clear();
    }

    @Test
    void consultaLosViajesInternosConFiltrosYToken() {
        UUID company = UUID.randomUUID();
        UUID route = UUID.randomUUID();
        UUID itinerary = UUID.randomUUID();
        server.respond(200, "[{\"itineraryId\":\"" + itinerary + "\",\"companyId\":\"" + company
                + "\",\"routeId\":\"" + route + "\",\"precioBase\":100000,\"estado\":\"PROGRAMADO\","
                + "\"fechaSalida\":\"2026-11-06T12:00:00Z\"}]");
        MDC.put(RequestIdFilter.MDC_KEY, "req-1");
        List<CatalogClient.Trip> trips = catalog.trips(company, route, Instant.parse("2026-11-05T00:00:00Z"),
                Instant.parse("2026-11-07T00:00:00Z"));
        assertThat(trips).singleElement().satisfies(t -> {
            assertThat(t.itineraryId()).isEqualTo(itinerary);
            assertThat(t.routeId()).isEqualTo(route);
        });
        FakeHttpServer.Request request = server.last();
        assertThat(request.uri()).startsWith("/internal/itineraries?").contains("companyId=" + company)
                .contains("routeId=" + route).contains("desde=2026-11-05T00:00:00Z");
        assertThat(request.header("X-Internal-Token")).isEqualTo("secreto");
        assertThat(request.header(RequestIdFilter.HEADER)).isEqualTo("req-1");
    }

    @Test
    void sinRutaNoMandaElFiltroYUnCuerpoVacioEsUnaListaVacia() {
        server.respond(200, "null");
        assertThat(catalog.trips(UUID.randomUUID(), null, Instant.now(), Instant.now())).isEmpty();
        assertThat(server.last().uri()).doesNotContain("routeId");
    }

    @Test
    void catalogCaidoEsServicioNoDisponible() {
        server.respond(500, "{}");
        UUID company = UUID.randomUUID();
        Instant now = Instant.now();
        assertThatThrownBy(() -> catalog.trips(company, null, now, now))
                .extracting("code").isEqualTo("CATALOG_UNAVAILABLE");
        assertThatThrownBy(() -> catalog.itinerary(company)).extracting("code").isEqualTo("CATALOG_UNAVAILABLE");
        server.respond(404, "{}");
        assertThatThrownBy(() -> catalog.itinerary(company)).extracting("code").isEqualTo("ITINERARY_NOT_FOUND");
    }

    @Test
    void consultaLaOcupacionDeUnViaje() {
        UUID itinerary = UUID.randomUUID();
        server.respond(200, "{\"itineraryId\":\"" + itinerary + "\",\"capacidad\":40,\"ocupados\":10,"
                + "\"asientosLibres\":[\"1\",\"2\"]}");
        SeatInventoryClient.Occupancy occupancy = seats.occupancy(itinerary);
        assertThat(occupancy.porcentaje()).isEqualTo(25.0);
        assertThat(occupancy.libres()).containsExactly("1", "2");
        assertThat(server.last().uri()).isEqualTo("/internal/occupancy?itineraryId=" + itinerary);
        assertThat(server.last().header("X-Internal-Token")).isEqualTo("secreto");
    }

    @Test
    void ocupacionSinCapacidadSeTomaComoLlenaYSinListaComoVacia() {
        SeatInventoryClient.Occupancy occupancy = new SeatInventoryClient.Occupancy(UUID.randomUUID(), 0, 0, null);
        assertThat(occupancy.porcentaje()).isEqualTo(100.0);
        assertThat(occupancy.libres()).isEmpty();
    }

    @Test
    void reservaYLiberaAsientosYRetenciones() {
        UUID offer = UUID.randomUUID();
        UUID itinerary = UUID.randomUUID();
        UUID hold = UUID.randomUUID();
        server.respond(200, "{\"offerId\":\"" + offer + "\",\"itineraryId\":\"" + itinerary
                + "\",\"reservados\":[\"1\"],\"omitidos\":[\"2\"]}");
        assertThat(seats.reserveOfferSeats(offer, itinerary, List.of("1", "2")).reservados()).containsExactly("1");
        assertThat(server.last().uri()).isEqualTo("/internal/offers/" + offer + "/seats");

        server.respond(200, "{\"holdId\":\"" + hold + "\",\"seatNumbers\":[\"1\"],\"estado\":\"ACTIVA\"}");
        assertThat(seats.hold(itinerary, "1", UUID.randomUUID(), offer).holdId()).isEqualTo(hold);
        assertThat(server.last().uri()).isEqualTo("/internal/holds");

        server.respond(204, "");
        seats.releaseHold(hold, "RESERVA_FALLIDA");
        assertThat(server.last().uri()).isEqualTo("/internal/holds/" + hold + "/release");
        seats.releaseOfferSeats(offer);
        assertThat(server.last().uri()).isEqualTo("/internal/offers/" + offer + "/release");
        assertThat(server.requests).allSatisfy(r -> assertThat(r.method()).isEqualTo("POST"));
    }

    @Test
    void losErroresDeLasOperacionesDeAsientosSeTraducen() {
        UUID offer = UUID.randomUUID();
        UUID itinerary = UUID.randomUUID();
        List<String> asientos = List.of("1");
        server.respond(409, "no es json");
        assertThatThrownBy(() -> seats.reserveOfferSeats(offer, itinerary, asientos)).extracting("code")
                .isEqualTo("SEAT_INVENTORY_ERROR");
        server.respond(409, "{\"error\":{\"code\":\"SEAT_TAKEN\",\"message\":\"Ocupado\"}}");
        UUID user = UUID.randomUUID();
        assertThatThrownBy(() -> seats.hold(itinerary, "1", user, offer)).extracting("code").isEqualTo("SEAT_TAKEN");
        server.respond(500, "{}");
        assertThatThrownBy(() -> seats.releaseHold(offer, "x")).extracting("code").isEqualTo("SEAT_INVENTORY_UNAVAILABLE");
        assertThatThrownBy(() -> seats.releaseOfferSeats(offer)).extracting("code").isEqualTo("SEAT_INVENTORY_UNAVAILABLE");
    }

    @Test
    void losErroresDeSeatInventorySeTraducen() {
        UUID itinerary = UUID.randomUUID();
        server.respond(404, "{\"error\":{\"code\":\"ITINERARY_NOT_FOUND\",\"message\":\"No existe\"}}");
        assertThatThrownBy(() -> seats.occupancy(itinerary)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("ITINERARY_NOT_FOUND");
        server.respond(503, "caído");
        assertThatThrownBy(() -> seats.occupancy(itinerary)).extracting("code")
                .isEqualTo("SEAT_INVENTORY_UNAVAILABLE");
    }
}
