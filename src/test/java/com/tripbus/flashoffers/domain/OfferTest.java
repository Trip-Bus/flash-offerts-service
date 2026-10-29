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

    @Test
    void elMaximoPorUsuarioPorDefectoEsDosYNuncaSuperaElCupo() {
        assertThat(nueva().getMaxPorUsuario()).isEqualTo(Offer.DEFAULT_MAX_POR_USUARIO);

        Offer offer = new Offer(UUID.randomUUID(), new BigDecimal("49900"), 5, 4, now.plusSeconds(60),
                now.plusSeconds(3600), UUID.randomUUID(), now);
        offer.asignarAsientos(List.of("1", "2", "3"));
        assertThat(offer.getMaxPorUsuario()).as("se ajusta al cupo reducido").isEqualTo(3);

        offer.asignarAsientos(List.of("1", "2", "3"));
        assertThat(offer.getMaxPorUsuario()).isEqualTo(3);
    }

    @Test
    void guardaDatosDeViajeYRecortaFinAlReprogramar() {
        Offer offer=nueva(); UUID company=UUID.randomUUID();
        Instant departure=now.plusSeconds(7200);
        offer.datosViaje(company,"Bogotá","Cali",departure,new BigDecimal("95000"),"Empresa",false);
        assertThat(offer.getCompanyId()).isEqualTo(company);
        assertThat(offer.getOrigen()).isEqualTo("Bogotá");
        assertThat(offer.getDestino()).isEqualTo("Cali");
        assertThat(offer.getPrecioBase()).isEqualByComparingTo("95000");
        assertThat(offer.getEmpresaNombre()).isEqualTo("Empresa");
        assertThat(offer.isPermiteReprogramar()).isFalse();
        offer.reprogramarViaje(now.plusSeconds(1800));
        assertThat(offer.getFechaSalida()).isEqualTo(now.plusSeconds(1800));
        assertThat(offer.getFin()).isEqualTo(now.plusSeconds(1800));
    }

    @Test
    void editaPrecioCupoVentanaAsientosYRegla() {
        Offer offer=nueva();
        offer.editar(new BigDecimal("45000"),2,1,now.plusSeconds(120),now.plusSeconds(1800),List.of("A1","A2"),false);
        assertThat(offer.getPrecioOferta()).isEqualByComparingTo("45000");
        assertThat(offer.getCupoMax()).isEqualTo(2);
        assertThat(offer.getMaxPorUsuario()).isEqualTo(1);
        assertThat(offer.getAsientos()).containsExactly("A1","A2");
        assertThat(offer.isPermiteReprogramar()).isFalse();
    }
}
