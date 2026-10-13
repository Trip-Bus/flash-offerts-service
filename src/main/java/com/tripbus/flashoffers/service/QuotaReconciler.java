package com.tripbus.flashoffers.service;

import com.tripbus.flashoffers.common.events.EventPublisher;
import com.tripbus.flashoffers.common.redis.LeaderLock;
import com.tripbus.flashoffers.domain.OfferStatus;
import com.tripbus.flashoffers.events.OfferEvents;
import com.tripbus.flashoffers.repository.OfferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * US-058: reconciliación del cupo. Si el proceso cae entre el descuento en Redis y el guardado de la venta, ese cupo
 * se pierde. Cada intervalo (un solo nodo, con {@link LeaderLock}) compara, para cada oferta ACTIVA, el contador de
 * Redis con el esperado según la BD (cupo menos ventas RESERVADA/VENDIDA). Para no pisar reservas en curso solo
 * corrige un desajuste que se repite idéntico en dos revisiones seguidas; el desajuste anterior se guarda en Redis,
 * así vale aunque cada revisión la haga un nodo distinto. AGOTADA, CERRADA, PROGRAMADA y BORRADOR no se tocan.
 */
@Component
public class QuotaReconciler {

    private static final Logger log = LoggerFactory.getLogger(QuotaReconciler.class);

    private final OfferRepository offerRepository;
    private final OfferService offerService;
    private final QuotaCounter quota;
    private final EventPublisher events;
    private final LeaderLock leaderLock;
    private final Clock clock;
    private final Duration interval;

    public QuotaReconciler(OfferRepository offerRepository, OfferService offerService, QuotaCounter quota,
                           EventPublisher events, LeaderLock leaderLock, Clock clock,
                           @Value("${tripbus.jobs.quota-reconcile-ms:60000}") long intervalMs) {
        this.offerRepository = offerRepository;
        this.offerService = offerService;
        this.quota = quota;
        this.events = events;
        this.leaderLock = leaderLock;
        this.clock = clock;
        this.interval = Duration.ofMillis(intervalMs);
    }

    @Scheduled(fixedDelayString = "${tripbus.jobs.quota-reconcile-ms:60000}",
            initialDelayString = "${tripbus.jobs.quota-reconcile-ms:60000}")
    public void tick() {
        // La llave del lock expira antes de la siguiente revisión: cada revisión corre en un único nodo.
        leaderLock.runIfLeader("flash-offers-quota-reconciler", interval.multipliedBy(3).dividedBy(4), this::runNow);
    }

    public void runNow() {
        for (UUID id : offerRepository.findIdsByEstado(OfferStatus.ACTIVA)) {
            try {
                reconcile(id);
            } catch (RuntimeException e) {
                log.warn("No se pudo reconciliar el cupo de la oferta {}: {}", id, e.getMessage());
            }
        }
    }

    void reconcile(UUID offerId) {
        // Primero Redis y después la BD: una reserva que termina entre las dos lecturas ya descontó en Redis.
        Integer actual = quota.remaining(offerId);
        if (actual == null) {
            return; // sin contador: la próxima reserva lo inicializa desde la BD
        }
        Optional<OfferService.ExpectedQuota> esperado = offerService.expectedQuotaIfActive(offerId);
        if (esperado.isEmpty()) {
            return; // dejó de estar ACTIVA entre la consulta y ahora
        }
        int expected = esperado.get().restante();
        if (actual == expected) {
            if (quota.lastMismatch(offerId) != null) {
                quota.forgetMismatch(offerId);
            }
            return;
        }
        String mismatch = expected + ":" + actual;
        if (!mismatch.equals(quota.lastMismatch(offerId))) {
            // Primera vez (o cambió desde la revisión anterior): puede ser una reserva en curso.
            quota.rememberMismatch(offerId, mismatch, interval.multipliedBy(3));
            return;
        }
        quota.forgetMismatch(offerId);
        if (quota.correct(offerId, actual, expected, esperado.get().libres())) {
            log.warn("Cupo de la oferta {} desajustado en Redis: {} -> {} (corregido por la reconciliación)",
                    offerId, actual, expected);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("offerId", offerId);
            payload.put("cupoRestante", expected);
            payload.put("timestamp", clock.instant());
            events.publishSafely(OfferEvents.OFFER_QUOTA_UPDATED, payload);
        }
    }
}
