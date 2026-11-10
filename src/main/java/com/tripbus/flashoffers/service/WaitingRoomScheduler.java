package com.tripbus.flashoffers.service;

import com.tripbus.flashoffers.common.redis.LeaderLock;
import com.tripbus.flashoffers.config.WaitingRoomProperties;
import com.tripbus.flashoffers.domain.OfferStatus;
import com.tripbus.flashoffers.repository.OfferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * US-162: cada {@code tripbus.offers.queue.batch-interval} (10 s), en un solo nodo, admite un lote en cada oferta
 * ACTIVA con sala de espera (el primer lote sortea el orden) y cierra la sala de las que se agotaron. El cierre por
 * fin de la oferta lo hace {@link OfferService#close}; aquí se repite por si Redis falló en ese momento.
 */
@Component
public class WaitingRoomScheduler {

    private static final Logger log = LoggerFactory.getLogger(WaitingRoomScheduler.class);

    private final OfferRepository offers;
    private final WaitingRoomService waitingRoom;
    private final LeaderLock leaderLock;
    private final Duration window;

    public WaitingRoomScheduler(OfferRepository offers, WaitingRoomService waitingRoom, LeaderLock leaderLock,
                                WaitingRoomProperties properties) {
        this.offers = offers;
        this.waitingRoom = waitingRoom;
        this.leaderLock = leaderLock;
        // La llave del lock vence antes del siguiente lote: cada lote corre en un único nodo.
        this.window = properties.batchInterval().multipliedBy(4).dividedBy(5);
    }

    @Scheduled(fixedDelayString = "${tripbus.offers.queue.batch-interval:10s}",
            initialDelayString = "${tripbus.offers.queue.batch-interval:10s}")
    public void tick() {
        leaderLock.runIfLeader("flash-offers-waiting-room", window, this::runNow);
    }

    public void runNow() {
        for (UUID id : offers.findIdsWithWaitingRoomByEstado(OfferStatus.ACTIVA)) {
            try {
                waitingRoom.processBatch(id);
            } catch (RuntimeException e) {
                log.warn("No se pudo admitir el lote de la oferta {}: {}", id, e.getMessage());
            }
        }
        for (UUID id : offers.findIdsWithWaitingRoomByEstado(OfferStatus.AGOTADA)) {
            waitingRoom.close(id);
        }
    }
}
