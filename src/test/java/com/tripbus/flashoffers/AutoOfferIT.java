package com.tripbus.flashoffers;

import com.tripbus.flashoffers.client.CatalogClient;
import com.tripbus.flashoffers.client.SeatInventoryClient;
import com.tripbus.flashoffers.common.error.ApiException;
import com.tripbus.flashoffers.common.security.OperatorScope;
import com.tripbus.flashoffers.service.AutoOfferJob;
import com.tripbus.flashoffers.service.AutoOfferRuleService;
import com.tripbus.flashoffers.web.dto.AutoOfferRuleRequest;
import com.tripbus.flashoffers.web.dto.AutoOfferRuleResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/** US-167: ofertas automáticas por baja ocupación (catalog y seat-inventory simulados). */
class AutoOfferIT extends Sprint6IT {

    @Autowired
    private AutoOfferRuleService rules;
    @Autowired
    private AutoOfferJob job;
    @Autowired
    private MeterRegistry meters;

    private OperatorScope operador() {
        return new OperatorScope(companyId, false);
    }

    private AutoOfferRuleResponse regla(UUID routeId, int horasAntes, String ocupacionMaxima, int cupoMaximo) {
        return rules.create(new AutoOfferRuleRequest(null, routeId, horasAntes, new BigDecimal(ocupacionMaxima),
                new BigDecimal("30"), cupoMaximo, true), operador());
    }

    private CatalogClient.Trip trip(UUID itinerary, Instant salida) {
        return new CatalogClient.Trip(itinerary, companyId, null, BASE, "PROGRAMADO", salida);
    }

    private void ocupacion(UUID itinerary, int capacidad, int ocupados) {
        List<String> libres = IntStream.rangeClosed(ocupados + 1, capacidad).mapToObj(String::valueOf).toList();
        when(seatInventory.occupancy(itinerary))
                .thenReturn(new SeatInventoryClient.Occupancy(itinerary, capacidad, ocupados, libres));
    }

    private List<Map<String, Object>> ofertasDe(UUID itinerary) {
        return jdbc.queryForList("select id, origen_creacion, cupo_max, precio_oferta, estado from offers "
                + "where itinerary_id = ?", itinerary);
    }

    @Test
    void creaUnaOfertaParaElViajeConBajaOcupacionYNoLaDuplica() {
        AutoOfferRuleResponse rule = regla(null, 24, "50", 5);
        Instant salida = Instant.now().plus(6, ChronoUnit.HOURS);
        UUID bajo = viaje(salida);
        UUID lleno = viaje(salida);
        UUID conOferta = viaje(salida);
        UUID cancelado = UUID.randomUUID();
        when(catalog.trips(eq(companyId), isNull(), any(), any())).thenReturn(List.of(trip(bajo, salida),
                trip(lleno, salida), trip(conOferta, salida),
                new CatalogClient.Trip(cancelado, companyId, null, BASE, "CANCELADO", salida)));
        ocupacion(bajo, 40, 10);
        ocupacion(lleno, 40, 30);
        ocupacion(conOferta, 40, 0);
        // El viaje con oferta manual no recibe una automática.
        offerService.create(new com.tripbus.flashoffers.web.dto.CreateOfferRequest(conOferta, List.of("1"),
                new BigDecimal("60000"), 1, Instant.now().plusSeconds(60), Instant.now().plusSeconds(3600)),
                UUID.randomUUID(), true, companyId);

        assertThat(job.runNow()).isGreaterThanOrEqualTo(1);
        List<Map<String, Object>> creadas = ofertasDe(bajo);
        assertThat(creadas).singleElement().satisfies(o -> {
            assertThat(o.get("origen_creacion")).isEqualTo("AUTOMATICA");
            assertThat(o.get("cupo_max")).isEqualTo(5);
            assertThat((BigDecimal) o.get("precio_oferta")).isEqualByComparingTo("70000");
            assertThat(o.get("estado")).isEqualTo("PROGRAMADA");
        });
        assertThat(ofertasDe(lleno)).isEmpty();
        assertThat(ofertasDe(conOferta)).singleElement().satisfies(o -> assertThat(o.get("origen_creacion")).isEqualTo("MANUAL"));
        assertThat(ofertasDe(cancelado)).isEmpty();

        // Segunda pasada: no duplica.
        job.runNow();
        assertThat(ofertasDe(bajo)).hasSize(1);
        // Ni aunque la oferta automática ya haya cerrado: una sola por viaje.
        UUID offerId = (UUID) creadas.getFirst().get("id");
        offerService.close(offerId, "CERRADA_MANUALMENTE");
        job.runNow();
        assertThat(ofertasDe(bajo)).hasSize(1);
        assertThat(rule.activa()).isTrue();
    }

    @Test
    void lasOfertasAutomaticasSeMarcanEnLaMetricaDeActivadas() {
        regla(null, 24, "80", 3);
        Instant salida = Instant.now().plus(4, ChronoUnit.HOURS);
        UUID bajo = viaje(salida);
        when(catalog.trips(eq(companyId), isNull(), any(), any())).thenReturn(List.of(trip(bajo, salida)));
        ocupacion(bajo, 10, 2);
        job.runNow();
        UUID offerId = (UUID) ofertasDe(bajo).getFirst().get("id");
        double antes = meters.get("tripbus.ofertas.activadas").tag("origen", "AUTOMATICA").counter().count();
        offerService.activate(offerId);
        assertThat(meters.get("tripbus.ofertas.activadas").tag("origen", "AUTOMATICA").counter().count())
                .isEqualTo(antes + 1);
        assertThat(offerService.get(offerId).origenCreacion()).isEqualTo("AUTOMATICA");
    }

    @Test
    void unaReglaDeRutaSoloConsultaEsaRutaYUnaInactivaNoCrea() {
        UUID route = UUID.randomUUID();
        AutoOfferRuleResponse rule = regla(route, 12, "90", 2);
        Instant salida = Instant.now().plus(3, ChronoUnit.HOURS);
        UUID bajo = viaje(salida);
        when(catalog.trips(eq(companyId), eq(route), any(), any())).thenReturn(List.of(trip(bajo, salida)));
        ocupacion(bajo, 10, 0);
        rules.update(rule.id(), new AutoOfferRuleRequest(null, route, 12, new BigDecimal("90"), new BigDecimal("30"),
                2, false), operador());
        job.runNow();
        assertThat(ofertasDe(bajo)).isEmpty();
    }

    @Test
    void crudDeReglasConAlcanceDeEmpresa() {
        AutoOfferRuleResponse rule = regla(null, 24, "50", 5);
        assertThat(rules.list(operador())).extracting(AutoOfferRuleResponse::id).contains(rule.id());
        assertThat(rules.get(rule.id(), operador()).companyId()).isEqualTo(companyId);
        OperatorScope otra = new OperatorScope(UUID.randomUUID(), false);
        UUID id = rule.id();
        assertThatThrownBy(() -> rules.get(id, otra)).extracting("code").isEqualTo("AUTO_OFFER_RULE_NOT_FOUND");
        assertThat(rules.list(otra)).isEmpty();
        assertThat(rules.list(new OperatorScope(null, true))).extracting(AutoOfferRuleResponse::id).contains(id);

        AutoOfferRuleResponse editada = rules.update(id, new AutoOfferRuleRequest(null, null, 48, new BigDecimal("40"),
                new BigDecimal("20"), 8, true), operador());
        assertThat(editada.horasAntes()).isEqualTo(48);
        assertThat(editada.cupoMaximo()).isEqualTo(8);

        AutoOfferRuleRequest descuentoAlto = new AutoOfferRuleRequest(null, null, 24, new BigDecimal("50"),
                new BigDecimal("90"), 5, true);
        OperatorScope scope = operador();
        assertThatThrownBy(() -> rules.create(descuentoAlto, scope)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("INVALID_DISCOUNT");
        OperatorScope admin = new OperatorScope(null, true);
        AutoOfferRuleRequest sinEmpresa = new AutoOfferRuleRequest(null, null, 24, new BigDecimal("50"),
                new BigDecimal("30"), 5, null);
        assertThatThrownBy(() -> rules.create(sinEmpresa, admin)).extracting("code").isEqualTo("COMPANY_REQUIRED");
        UUID otraEmpresa = UUID.randomUUID();
        assertThat(rules.create(new AutoOfferRuleRequest(otraEmpresa, null, 24, new BigDecimal("50"),
                new BigDecimal("30"), 5, null), admin).companyId()).isEqualTo(otraEmpresa);

        rules.delete(id, operador());
        assertThatThrownBy(() -> rules.get(id, scope)).extracting("code").isEqualTo("AUTO_OFFER_RULE_NOT_FOUND");
    }
}
