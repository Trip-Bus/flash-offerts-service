package com.tripbus.flashoffers.client;

import com.tripbus.flashoffers.common.error.ApiException;
import com.tripbus.flashoffers.config.OffersProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Component
public class CatalogClient {

    private final RestClient restClient;

    public CatalogClient(OffersProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(3000);
        this.restClient = RestClient.builder().baseUrl(properties.catalogUrl()).requestFactory(factory).build();
    }

    public record Itinerary(UUID itineraryId, UUID companyId, BigDecimal precioBase, String estado,
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
}
