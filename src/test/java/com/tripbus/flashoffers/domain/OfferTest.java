package com.tripbus.flashoffers.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OfferTest {

    private final Instant now = Instant.parse("2026-10-12T15:00:00Z");

    private Offer nueva() {
        return new Offer(UUID.randomUUID(), new BigDecimal("49900"), 5, now.plusSeconds(60), now.plusSeconds(3600),
                UUID.randomUUID(), now);
    }

    @Test
    void unaOfertaNuevaEsBorradorHastaProgramarse() {
        Offer offer = nueva();
        assertThat(offer.getEstado()).isEqualTo(OfferStatus.BORRADOR);
        assertThat(offer.borrador()).isTrue();

        offer.asignarAsientos(List.of("1", "2", "3"));
        offer.programar();

        assertThat(offer.getEstado()).isEqualTo(OfferStatus.PROGRAMADA);
        assertThat(offer.borrador()).isFalse();
        assertThat(offer.getCupoMax()).as("el cupo se ajusta a los asientos reservados").isEqualTo(3);
    }

    @Test
    void programarSoloAfectaAUnBorrador() {
        Offer offer = nueva();
        offer.programar();
        offer.activar(now);
        offer.programar();
        assertThat(offer.getEstado()).isEqualTo(OfferStatus.ACTIVA);

        offer.cerrar("EXPIRADA");
        offer.programar();
        assertThat(offer.getEstado()).isEqualTo(OfferStatus.CERRADA);
        assertThat(offer.getMotivoCierre()).isEqualTo("EXPIRADA");
    }
}
