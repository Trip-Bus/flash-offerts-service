package com.tripbus.flashoffers.service;

import com.tripbus.flashoffers.domain.OfferSource;
import com.tripbus.flashoffers.domain.OfferStatus;
import com.tripbus.flashoffers.repository.OfferRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * HT-10: métricas de negocio de las ofertas (en /actuator/prometheus):
 * <ul>
 *   <li>{@code tripbus_reservas_oferta_total{resultado=ok|agotada|limite_usuario|sin_turno}}</li>
 *   <li>{@code tripbus_ofertas_activadas_total{origen=MANUAL|AUTOMATICA|RECURRENTE}}</li>
 *   <li>{@code tripbus_ofertas_cupo_restante{offerId}}: gauge del cupo restante de cada oferta ACTIVA, refrescado
 *   cada {@code tripbus.jobs.quota-gauge-ms} desde el contador de Redis (cada réplica publica el suyo).</li>
 * </ul>
 */
@Component
public class OfferMeters {

    private static final Logger log = LoggerFactory.getLogger(OfferMeters.class);

    static final String RESERVATIONS = "tripbus.reservas.oferta";
    static final String ACTIVATED = "tripbus.ofertas.activadas";
    static final String REMAINING = "tripbus.ofertas.cupo.restante";

    /** Resultado de una solicitud de reserva de oferta (etiqueta {@code resultado}). */
    public enum ReservationResult {
        OK("ok"), AGOTADA("agotada"), LIMITE_USUARIO("limite_usuario"), SIN_TURNO("sin_turno");

        private final String tag;

        ReservationResult(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    private final MeterRegistry registry;
    private final OfferRepository offers;
    private final QuotaCounter quota;
    private final MultiGauge remainingGauge;

    public OfferMeters(MeterRegistry registry, OfferRepository offers, QuotaCounter quota) {
        this.registry = registry;
        this.offers = offers;
        this.quota = quota;
        // Se registran en 0 para que la serie exista desde el arranque (las alertas no dependen de un primer evento).
        for (ReservationResult r : ReservationResult.values()) {
            reservationCounter(r);
        }
        for (OfferSource s : OfferSource.values()) {
            activatedCounter(s);
        }
        this.remainingGauge = MultiGauge.builder(REMAINING)
                .description("Cupo restante de cada oferta activa")
                .register(registry);
    }

    public void reservation(ReservationResult result) {
        reservationCounter(result).increment();
    }

    public void activated(OfferSource source) {
        activatedCounter(source == null ? OfferSource.MANUAL : source).increment();
    }

    private Counter reservationCounter(ReservationResult result) {
        return Counter.builder(RESERVATIONS)
                .description("Solicitudes de reserva de oferta por resultado")
                .tag("resultado", result.tag())
                .register(registry);
    }

    private Counter activatedCounter(OfferSource source) {
        return Counter.builder(ACTIVATED)
                .description("Ofertas activadas por origen")
                .tag("origen", source.name())
                .register(registry);
    }

    /** Refresca el gauge: una serie por oferta ACTIVA; las que dejaron de estarlo desaparecen. */
    @Scheduled(fixedDelayString = "${tripbus.jobs.quota-gauge-ms:15000}", initialDelay = 5000)
    public void refreshRemaining() {
        try {
            List<MultiGauge.Row<?>> rows = new ArrayList<>();
            for (UUID id : offers.findIdsByEstado(OfferStatus.ACTIVA)) {
                Integer left = quota.remaining(id);
                if (left != null) {
                    rows.add(MultiGauge.Row.of(Tags.of("offerId", id.toString()), Math.max(0, left)));
                }
            }
            remainingGauge.register(rows, true);
        } catch (RuntimeException e) {
            log.debug("No se pudo refrescar el gauge de cupo: {}", e.getMessage());
        }
    }
}
