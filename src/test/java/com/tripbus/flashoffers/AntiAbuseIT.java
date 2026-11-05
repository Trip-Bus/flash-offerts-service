package com.tripbus.flashoffers;

import com.tripbus.flashoffers.common.error.ApiException;
import com.tripbus.flashoffers.common.error.TooManyRequestsException;
import com.tripbus.flashoffers.service.ReservationRateLimiter;
import com.tripbus.flashoffers.web.dto.OfferResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** US-168: límite de reservas por documento al día y de intentos por usuario y minuto (Redis real). */
class AntiAbuseIT extends Sprint6IT {

    @Autowired
    private ReservationRateLimiter rateLimiter;
    @Autowired
    private MeterRegistry meters;

    private OfferResponse ofertaActiva() {
        OfferResponse offer = oferta(5, Instant.now().plus(30, ChronoUnit.SECONDS), false);
        offerService.activate(offer.offerId());
        return offer;
    }

    private double limiteUsuario() {
        return meters.get("tripbus.reservas.oferta").tag("resultado", "limite_usuario").counter().count();
    }

    @Test
    void unDocumentoTieneComoMaximoDosReservasDeOfertaAlDiaEnTodasLasOfertas() {
        String doc = documento();
        OfferResponse a = ofertaActiva();
        OfferResponse b = ofertaActiva();
        OfferResponse c = ofertaActiva();
        assertThat(offerService.reserve(a.offerId(), UUID.randomUUID(), doc).created()).isTrue();
        assertThat(offerService.reserve(b.offerId(), UUID.randomUUID(), doc).created()).isTrue();
        double antes = limiteUsuario();

        UUID tercera = c.offerId();
        UUID otroUsuario = UUID.randomUUID();
        assertThatThrownBy(() -> offerService.reserve(tercera, otroUsuario, doc))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getStatus().value()).isEqualTo(409);
                    assertThat(((ApiException) e).getDetails()).containsEntry("maxPorDocumentoAlDia", 2);
                })
                .extracting("code").isEqualTo("DOCUMENT_DAILY_LIMIT");
        assertThat(limiteUsuario()).isEqualTo(antes + 1);
        assertThat(offerService.quota(tercera).cupoRestante()).as("el rechazo no gasta cupo").isEqualTo(5);

        // Otro documento sí puede reservar en la tercera oferta.
        assertThat(offerService.reserve(tercera, otroUsuario, documento()).created()).isTrue();
    }

    @Test
    void unaReservaLiberadaYaNoCuentaParaElLimiteDelDocumento() {
        String doc = documento();
        OfferResponse a = ofertaActiva();
        OfferResponse b = ofertaActiva();
        var primera = offerService.reserve(a.offerId(), UUID.randomUUID(), doc).reservation();
        offerService.reserve(b.offerId(), UUID.randomUUID(), doc);
        offerService.onHoldReleased(primera.holdId());
        assertThat(offerService.reserve(ofertaActiva().offerId(), UUID.randomUUID(), doc).created()).isTrue();
    }

    @Test
    void masDeDiezIntentosPorMinutoRespondenDemasiadasSolicitudes() {
        // La ventana es el minuto en curso: no se empieza en sus últimos segundos para no partir los intentos.
        await().atMost(Duration.ofSeconds(10)).until(() -> Instant.now().getEpochSecond() % 60 <= 55);
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            rateLimiter.check(user);
        }
        assertThatThrownBy(() -> rateLimiter.check(user))
                .isInstanceOf(TooManyRequestsException.class)
                .satisfies(e -> {
                    TooManyRequestsException tooMany = (TooManyRequestsException) e;
                    assertThat(tooMany.getStatus().value()).isEqualTo(429);
                    assertThat(tooMany.getRetryAfterSeconds()).isBetween(1L, 60L);
                    assertThat(tooMany.getCode()).isEqualTo("TOO_MANY_ATTEMPTS");
                });
        // El contador es por usuario.
        rateLimiter.check(UUID.randomUUID());
    }
}
