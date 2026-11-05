package com.tripbus.flashoffers.client;

import com.tripbus.flashoffers.common.error.ApiException;
import com.tripbus.flashoffers.config.OffersProperties;
import com.tripbus.flashoffers.config.RequestIdPropagationInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Catalog. El {@link RestClient.Builder} es el de Spring Boot: trae la observación HTTP, así cada llamada abre un
 * span hijo y propaga el contexto de traza (cabecera {@code traceparent}); además se propaga el requestId.
 */
@Component
public class CatalogClient {

    private final RestClient restClient;

    public CatalogClient(OffersProperties properties, RestClient.Builder builder,
                         @Value("${tripbus.internal-token}") String token) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(3000);
        this.restClient = builder
                .baseUrl(properties.catalogUrl())
                .requestFactory(factory)
                .defaultHeader("X-Internal-Token", token)
                .requestInterceptor(new RequestIdPropagationInterceptor())
                .build();
    }

    public record Itinerary(UUID itineraryId, UUID companyId, BigDecimal precioBase, String estado,
                            Instant fechaSalida, String origen, String destino, String empresaNombre) {
        public Itinerary(UUID itineraryId, UUID companyId, BigDecimal precioBase, String estado, Instant fechaSalida) {
            this(itineraryId, companyId, precioBase, estado, fechaSalida, null, null, null);
        }
    }

    /** Viaje programado de {@code GET /internal/itineraries} (jobs de ofertas automáticas y recurrentes). */
    public record Trip(UUID itineraryId, UUID companyId, UUID routeId, BigDecimal precioBase, String estado,
                       Instant fechaSalida) {
    }

    public Itinerary itinerary(UUID itineraryId) {
        try {
            return restClient.get().uri("/itineraries/{id}", itineraryId).retrieve().body(Itinerary.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw ApiException.notFound("ITINERARY_NOT_FOUND", "El itinerario no existe");
        } catch (RestClientException e) {
            throw ApiException.unavailable("CATALOG_UNAVAILABLE", "Catalog no está disponible");
        }
    }

    /** Viajes de la empresa (y de la ruta, si se indica) que salen entre {@code desde} y {@code hasta}. */
    public List<Trip> trips(UUID companyId, UUID routeId, Instant desde, Instant hasta) {
        try {
            List<Trip> trips = restClient.get().uri(b -> {
                        b.path("/internal/itineraries").queryParam("companyId", companyId)
                                .queryParam("desde", desde).queryParam("hasta", hasta);
                        if (routeId != null) {
                            b.queryParam("routeId", routeId);
                        }
                        return b.build();
                    })
                    .retrieve().body(new ParameterizedTypeReference<List<Trip>>() { });
            return trips != null ? trips : List.of();
        } catch (RestClientException e) {
            throw ApiException.unavailable("CATALOG_UNAVAILABLE", "Catalog no está disponible");
        }
    }
}
