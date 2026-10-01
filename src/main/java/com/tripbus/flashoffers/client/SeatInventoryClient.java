package com.tripbus.flashoffers.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.flashoffers.common.error.ApiException;
import com.tripbus.flashoffers.config.OffersProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Seat Inventory (interno): reservar asientos para la oferta y retenerlos para el comprador. */
@Component
public class SeatInventoryClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;

    public SeatInventoryClient(OffersProperties properties, @Value("${tripbus.internal-token}") String token) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(5000);
        this.restClient = RestClient.builder()
                .baseUrl(properties.seatInventoryUrl())
                .requestFactory(factory)
                .defaultHeader("X-Internal-Token", token)
                .build();
    }

    public record OfferSeats(UUID offerId, UUID itineraryId, List<String> reservados, List<String> omitidos) {
    }

    public record Hold(UUID holdId, UUID itineraryId, List<String> seatNumbers, UUID userId, UUID offerId,
                       String estado, Instant expiraEn) {
    }

    public OfferSeats reserveOfferSeats(UUID offerId, UUID itineraryId, List<String> seats) {
        try {
            return restClient.post().uri("/internal/offers/{id}/seats", offerId)
                    .body(Map.of("itineraryId", itineraryId, "seatNumbers", seats))
                    .retrieve().body(OfferSeats.class);
        } catch (RestClientException e) {
            throw translate(e);
        }
    }

    public void releaseOfferSeats(UUID offerId) {
        try {
            restClient.post().uri("/internal/offers/{id}/release", offerId).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw translate(e);
        }
    }

    public Hold hold(UUID itineraryId, String seatNumber, UUID userId, UUID offerId) {
        try {
            return restClient.post().uri("/internal/holds")
                    .body(Map.of("itineraryId", itineraryId, "seatNumbers", List.of(seatNumber), "userId", userId,
                            "offerId", offerId))
                    .retrieve().body(Hold.class);
        } catch (RestClientException e) {
            throw translate(e);
        }
    }

    public void releaseHold(UUID holdId, String motivo) {
        try {
            restClient.post().uri("/internal/holds/{id}/release", holdId)
                    .body(Map.of("motivo", motivo)).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw translate(e);
        }
    }

    private static ApiException translate(RestClientException e) {
        if (e instanceof HttpStatusCodeException http && http.getStatusCode().is4xxClientError()) {
            String code = "SEAT_INVENTORY_ERROR";
            String message = "Seat Inventory rechazó la operación";
            try {
                JsonNode error = MAPPER.readTree(http.getResponseBodyAsString()).path("error");
                code = error.path("code").asText(code);
                message = error.path("message").asText(message);
            } catch (Exception ignored) {
                // cuerpo no JSON
            }
            return new ApiException(HttpStatus.valueOf(http.getStatusCode().value()), code, message);
        }
        return ApiException.unavailable("SEAT_INVENTORY_UNAVAILABLE", "Seat Inventory no está disponible");
    }
}
