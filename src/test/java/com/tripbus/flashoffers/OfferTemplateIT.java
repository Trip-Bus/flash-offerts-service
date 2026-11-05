package com.tripbus.flashoffers;

import com.tripbus.flashoffers.client.CatalogClient;
import com.tripbus.flashoffers.client.SeatInventoryClient;
import com.tripbus.flashoffers.common.security.OperatorScope;
import com.tripbus.flashoffers.service.OfferTemplateJob;
import com.tripbus.flashoffers.service.OfferTemplateService;
import com.tripbus.flashoffers.web.dto.OfferTemplateRequest;
import com.tripbus.flashoffers.web.dto.OfferTemplateResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** US-169: ofertas recurrentes desde plantillas (catalog y seat-inventory simulados). */
class OfferTemplateIT extends Sprint6IT {

    private static final ZoneId ZONA = ZoneId.of("America/Bogota");

    @Autowired
    private OfferTemplateService templates;
    @Autowired
    private OfferTemplateJob job;

    private final UUID route = UUID.randomUUID();

    private OperatorScope operador() {
        return new OperatorScope(companyId, false);
    }

    private OfferTemplateRequest plantilla(List<Integer> dias, int cupo, Integer maxPorUsuario) {
        return new OfferTemplateRequest(null, route, dias, LocalTime.of(10, 0), 60, new BigDecimal("25"), cupo,
                maxPorUsuario, false, true, 24, true);
    }

    private void libres(UUID itinerary, int n) {
        List<String> libres = IntStream.rangeClosed(1, n).mapToObj(String::valueOf).toList();
        when(seatInventory.occupancy(itinerary)).thenReturn(new SeatInventoryClient.Occupancy(itinerary, 40, 40 - n, libres));
    }

    private List<Map<String, Object>> ofertasDe(UUID itinerary) {
        return jdbc.queryForList("select template_id, origen_creacion, cupo_max, max_por_usuario, precio_oferta, inicio, "
                + "fin, sala_espera_habilitada, permite_reprogramar from offers where itinerary_id = ?", itinerary);
    }

    @Test
    void creaLasOfertasDeLosViajesDeEsosDiasYNoLasDuplica() {
        Instant salida = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        Instant otroDia = salida.plus(1, ChronoUnit.DAYS);
        int dia = salida.atZone(ZONA).getDayOfWeek().getValue();
        UUID viaje = viaje(salida);
        UUID viajeOtroDia = viaje(otroDia);
        when(catalog.trips(eq(companyId), eq(route), any(), any())).thenReturn(List.of(
                new CatalogClient.Trip(viaje, companyId, route, BASE, "PROGRAMADO", salida),
                new CatalogClient.Trip(viajeOtroDia, companyId, route, BASE, "PROGRAMADO", otroDia)));
        libres(viaje, 10);
        libres(viajeOtroDia, 10);
        OfferTemplateResponse template = templates.create(plantilla(List.of(dia), 4, 1), operador());

        job.runNow();
        List<Map<String, Object>> creadas = ofertasDe(viaje);
        assertThat(creadas).singleElement().satisfies(o -> {
            assertThat(o.get("template_id")).isEqualTo(template.id());
            assertThat(o.get("origen_creacion")).isEqualTo("RECURRENTE");
            assertThat(o.get("cupo_max")).isEqualTo(4);
            assertThat(o.get("max_por_usuario")).isEqualTo(1);
            assertThat((BigDecimal) o.get("precio_oferta")).isEqualByComparingTo("75000");
            assertThat(o.get("sala_espera_habilitada")).isEqualTo(true);
            assertThat(o.get("permite_reprogramar")).isEqualTo(false);
            Instant inicio = ((Timestamp) o.get("inicio")).toInstant();
            // Día que queda 24 h antes de la salida, a las 10:00 (hora de la plataforma), durante 60 minutos.
            assertThat(inicio.atZone(ZONA).toLocalDate()).isEqualTo(salida.minus(24, ChronoUnit.HOURS).atZone(ZONA).toLocalDate());
            assertThat(inicio.atZone(ZONA).toLocalTime()).isEqualTo(LocalTime.of(10, 0));
            assertThat(((Timestamp) o.get("fin")).toInstant()).isEqualTo(inicio.plus(60, ChronoUnit.MINUTES));
        });
        assertThat(ofertasDe(viajeOtroDia)).as("otro día de la semana").isEmpty();

        // Idempotente: otra pasada no duplica la oferta de la misma plantilla y viaje.
        job.runNow();
        assertThat(ofertasDe(viaje)).hasSize(1);
    }

    @Test
    void sinAsientosLibresOConLaVentanaYaPasadaNoCrea() {
        Instant salida = Instant.now().plus(2, ChronoUnit.HOURS);
        int dia = salida.atZone(ZONA).getDayOfWeek().getValue();
        UUID pronto = viaje(salida);
        Instant salidaLejana = Instant.now().plus(3, ChronoUnit.DAYS);
        UUID sinCupo = viaje(salidaLejana);
        when(catalog.trips(eq(companyId), eq(route), any(), any())).thenReturn(List.of(
                new CatalogClient.Trip(pronto, companyId, route, BASE, "PROGRAMADO", salida),
                new CatalogClient.Trip(sinCupo, companyId, route, BASE, "PROGRAMADO", salidaLejana)));
        libres(pronto, 5);
        libres(sinCupo, 0);
        templates.create(plantilla(List.of(1, 2, 3, 4, 5, 6, 7), 4, null), operador());
        job.runNow();
        // El viaje sale en 2 h: la oferta de 24 h antes ya pasó.
        assertThat(ofertasDe(pronto)).isEmpty();
        assertThat(ofertasDe(sinCupo)).isEmpty();
        assertThat(dia).isBetween(1, 7);
    }

    @Test
    void crudDePlantillasConAlcanceDeEmpresa() {
        OfferTemplateResponse template = templates.create(plantilla(List.of(5, 1, 5), 3, null), operador());
        assertThat(template.diasSemana()).containsExactly(1, 5);
        assertThat(template.maxPorUsuario()).isEqualTo(2);
        assertThat(template.salaEspera()).isTrue();
        assertThat(templates.list(operador())).extracting(OfferTemplateResponse::id).contains(template.id());
        UUID id = template.id();
        OperatorScope otra = new OperatorScope(UUID.randomUUID(), false);
        assertThatThrownBy(() -> templates.get(id, otra)).extracting("code").isEqualTo("OFFER_TEMPLATE_NOT_FOUND");
        assertThat(templates.list(new OperatorScope(null, true))).extracting(OfferTemplateResponse::id).contains(id);

        OfferTemplateResponse editada = templates.update(id, new OfferTemplateRequest(null, route, List.of(7),
                LocalTime.of(8, 30), 30, new BigDecimal("40"), 6, 3, null, null, 12, false), operador());
        assertThat(editada.diasSemana()).containsExactly(7);
        assertThat(editada.activa()).isFalse();
        assertThat(editada.permiteReprogramar()).isTrue();
        assertThat(templates.get(id, operador()).horaInicio()).isEqualTo(LocalTime.of(8, 30));

        OfferTemplateRequest maxAlto = plantilla(List.of(1), 2, 3);
        OperatorScope scope = operador();
        assertThatThrownBy(() -> templates.create(maxAlto, scope)).extracting("code")
                .isEqualTo("MAX_PER_USER_EXCEEDS_QUOTA");
        OfferTemplateRequest descuentoBajo = new OfferTemplateRequest(null, route, List.of(1), LocalTime.NOON, 30,
                new BigDecimal("1"), 2, null, null, null, 0, null);
        assertThatThrownBy(() -> templates.create(descuentoBajo, scope)).extracting("code").isEqualTo("INVALID_DISCOUNT");

        templates.delete(id, operador());
        assertThatThrownBy(() -> templates.get(id, scope)).extracting("code").isEqualTo("OFFER_TEMPLATE_NOT_FOUND");
    }
}
