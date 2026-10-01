package com.tripbus.flashoffers.service;

import com.tripbus.flashoffers.common.redis.LeaderLock;
import com.tripbus.flashoffers.domain.OfferStatus;
import com.tripbus.flashoffers.repository.OfferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Activación y cierre automáticos de ofertas según su horario (un solo nodo por ventana). */
@Component
public class OfferScheduler {

    private static final Logger log = LoggerFactory.getLogger(OfferScheduler.class);

    private final OfferRepository offerRepository;
    private final OfferService offerService;
    private final LeaderLock leaderLock;
    private final Clock clock;

    public OfferScheduler(OfferRepository offerRepository, OfferService offerService, LeaderLock leaderLock,
                          Clock clock) {
        this.offerRepository = offerRepository;
        this.offerService = offerService;
        this.leaderLock = leaderLock;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tripbus.jobs.offer-scheduler-ms:2000}", initialDelay = 3000)
    public void tick() {
        leaderLock.runIfLeader("flash-offers-scheduler", Duration.ofMillis(1500), this::runNow);
    }

    public void runNow() {
        var now = clock.instant();
        for (UUID id : offerRepository.findIdsToActivate(OfferStatus.PROGRAMADA, now)) {
            try {
                offerService.activate(id);
                log.info("Oferta {} activada", id);
            } catch (RuntimeException e) {
                log.warn("No se pudo activar la oferta {}: {}", id, e.getMessage());
            }
        }
        for (UUID id : offerRepository.findIdsToClose(
                List.of(OfferStatus.PROGRAMADA, OfferStatus.ACTIVA, OfferStatus.AGOTADA), now)) {
            try {
                offerService.close(id, "EXPIRADA");
                log.info("Oferta {} cerrada por horario", id);
            } catch (RuntimeException e) {
                log.warn("No se pudo cerrar la oferta {}: {}", id, e.getMessage());
            }
        }
    }
}
