package com.tripbus.flashoffers;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripbus.flashoffers.common.error.ApiException;
import com.tripbus.flashoffers.service.WaitingRoomScheduler;
import com.tripbus.flashoffers.service.WaitingRoomService;
import com.tripbus.flashoffers.service.WaitingRoomService.QueueState;
import com.tripbus.flashoffers.web.dto.OfferResponse;
import com.tripbus.flashoffers.web.dto.UpdateOfferRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** US-162: sala de espera virtual con Redis real. */
class WaitingRoomIT extends Sprint6IT {

    @Autowired
    private WaitingRoomService waitingRoom;
    @Autowired
    private WaitingRoomScheduler scheduler;
    @Autowired
    private MeterRegistry meters;

    /** Oferta con sala que empieza en 5 minutos: la sala ya está abierta (abre 10 min antes). */
    private OfferResponse ofertaConSala(int cupo) {
        return oferta(cupo, Instant.now().plus(5, ChronoUnit.MINUTES), true);
    }

    private static String token(UUID offerId, UUID userId) {
        return "offer:{" + offerId + "}:admission:" + userId;
    }

    private double sinTurno() {
        return meters.get("tripbus.reservas.oferta").tag("resultado", "sin_turno").counter().count();
    }

    @Test
    void entrarAntesDeTiempoOSinSalaSeRechaza() {
        OfferResponse temprana = oferta(2, Instant.now().plus(30, ChronoUnit.MINUTES), true);
        UUID user = UUID.randomUUID();
        UUID tempranaId = temprana.offerId();
        assertThatThrownBy(() -> waitingRoom.join(tempranaId, user))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("QUEUE_NOT_OPEN");
        assertThat(waitingRoom.status(tempranaId, user).estado()).isEqualTo(QueueState.NO_INSCRITO);

        UUID sinSala = oferta(2, Instant.now().plus(5, ChronoUnit.MINUTES), false).offerId();
        assertThatThrownBy(() -> waitingRoom.join(sinSala, user)).extracting("code").isEqualTo("QUEUE_NOT_ENABLED");
        UUID inexistente = UUID.randomUUID();
        assertThatThrownBy(() -> waitingRoom.join(inexistente, user)).extracting("code").isEqualTo("OFFER_NOT_FOUND");
    }

    @Test
    void cadaPasajeroEntraUnaSolaVezYAntesDelSorteoNoTienePosicion() {
        OfferResponse offer = ofertaConSala(5);
        assertThat(offer.salaEsperaHabilitada()).isTrue();
        UUID user = UUID.randomUUID();
        WaitingRoomService.JoinResult primera = waitingRoom.join(offer.offerId(), user);
        WaitingRoomService.JoinResult segunda = waitingRoom.join(offer.offerId(), user);
        assertThat(primera.created()).isTrue();
        assertThat(segunda.created()).isFalse();
        assertThat(primera.status().estado()).isEqualTo(QueueState.EN_SALA);
        assertThat(primera.status().posicion()).isNull();
        assertThat(segunda.status().enFila()).isEqualTo(1);
    }

    @Test
    void alIniciarSeSorteaElOrdenYQuienLlegaDespuesVaAlFinalPorOrdenDeLlegada() {
        OfferResponse offer = ofertaConSala(40);
        UUID offerId = offer.offerId();
        List<UUID> tempranos = IntStream.range(0, 12).mapToObj(i -> UUID.randomUUID()).toList();
        tempranos.forEach(u -> waitingRoom.join(offerId, u));

        offerService.activate(offerId);
        WaitingRoomService.Batch lote = waitingRoom.processBatch(offerId);
        assertThat(lote.admitidos()).hasSize(2).isSubsetOf(tempranos);

        UUID tarde1 = UUID.randomUUID();
        UUID tarde2 = UUID.randomUUID();
        assertThat(waitingRoom.join(offerId, tarde1).status().posicion()).isEqualTo(11);
        assertThat(waitingRoom.join(offerId, tarde2).status().posicion()).isEqualTo(12);

        List<UUID> enFila = new ArrayList<>(tempranos);
        enFila.removeAll(lote.admitidos());
        List<WaitingRoomService.QueueStatus> estados = enFila.stream().map(u -> waitingRoom.status(offerId, u)).toList();
        assertThat(estados).extracting(WaitingRoomService.QueueStatus::estado).containsOnly(QueueState.EN_FILA);
        List<Long> posiciones = estados.stream().map(WaitingRoomService.QueueStatus::posicion).toList();
        assertThat(posiciones).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);

        // Orden sorteado: los 12 de antes del inicio, del 1 al 12, en el orden en que salen de la fila.
        List<UUID> sorteado = new ArrayList<>(lote.admitidos());
        enFila.stream().sorted((a, b) -> Long.compare(waitingRoom.status(offerId, a).posicion(),
                waitingRoom.status(offerId, b).posicion())).forEach(sorteado::add);
        assertThat(sorteado).as("el sorteo no conserva el orden de llegada").isNotEqualTo(tempranos)
                .containsExactlyInAnyOrderElementsOf(tempranos);
    }

    @Test
    void cadaLoteAdmiteHastaElTamanoConfiguradoYPublicaLosEventos() {
        OfferResponse offer = ofertaConSala(10);
        UUID offerId = offer.offerId();
        List<UUID> users = IntStream.range(0, 5).mapToObj(i -> UUID.randomUUID()).toList();
        users.forEach(u -> waitingRoom.join(offerId, u));
        offerService.activate(offerId);

        scheduler.runNow();
        List<UUID> admitidos1 = users.stream()
                .filter(u -> waitingRoom.status(offerId, u).estado() == QueueState.ADMITIDO).toList();
        assertThat(admitidos1).hasSize(2);
        WaitingRoomService.QueueStatus admitido = waitingRoom.status(offerId, admitidos1.getFirst());
        assertThat(admitido.admisionExpiraEn()).isBetween(Instant.now().plus(Duration.ofMinutes(4)),
                Instant.now().plus(Duration.ofMinutes(5)).plusSeconds(1));
        assertThat(redis.getExpire(token(offerId, admitidos1.getFirst()))).isBetween(290L, 300L);

        WaitingRoomService.Batch segundo = waitingRoom.processBatch(offerId);
        assertThat(segundo.admitidos()).hasSize(2).doesNotContainAnyElementsOf(admitidos1);
        assertThat(segundo.enFila()).isEqualTo(1);
        assertThat(segundo.totalAdmitidos()).isEqualTo(4);

        List<JsonNode> admitted = eventos("OfferQueueAdmitted", offerId);
        assertThat(admitted).hasSize(4);
        assertThat(admitted.getFirst().hasNonNull("userId")).isTrue();
        assertThat(admitted.getFirst().hasNonNull("expiraEn")).isTrue();
        List<JsonNode> updated = eventos("OfferQueueUpdated", offerId);
        assertThat(updated).hasSize(2);
        assertThat(updated.getLast().path("enFila").asLong()).isEqualTo(1);
        assertThat(updated.getLast().path("admitidos").asLong()).isEqualTo(4);
    }

    @Test
    void sinTokenOConElTokenVencidoNoSeReserva() {
        OfferResponse offer = ofertaConSala(5);
        UUID offerId = offer.offerId();
        UUID admitido = UUID.randomUUID();
        UUID sinTurno = UUID.randomUUID();
        waitingRoom.join(offerId, admitido);
        offerService.activate(offerId);
        waitingRoom.processBatch(offerId);
        double antes = sinTurno();

        String doc = documento();
        assertThatThrownBy(() -> offerService.reserve(offerId, sinTurno, doc))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(403))
                .extracting("code").isEqualTo("QUEUE_TURN_REQUIRED");
        assertThat(sinTurno()).isEqualTo(antes + 1);

        assertThat(offerService.reserve(offerId, admitido, documento()).created()).isTrue();

        // Token vencido: la llave expira en Redis.
        redis.expire(token(offerId, admitido), Duration.ofMillis(1));
        await().atMost(Duration.ofSeconds(5)).until(() -> !Boolean.TRUE.equals(redis.hasKey(token(offerId, admitido))));
        assertThat(waitingRoom.status(offerId, admitido).estado()).isEqualTo(QueueState.TURNO_VENCIDO);
        String otroDoc = documento();
        assertThatThrownBy(() -> offerService.reserve(offerId, admitido, otroDoc))
                .extracting("code").isEqualTo("QUEUE_TURN_REQUIRED");
    }

    @Test
    void laSalaSeCierraCuandoLaOfertaSeAgota() {
        OfferResponse offer = ofertaConSala(1);
        UUID offerId = offer.offerId();
        UUID comprador = UUID.randomUUID();
        UUID esperando = UUID.randomUUID();
        waitingRoom.join(offerId, comprador);
        offerService.activate(offerId);
        waitingRoom.processBatch(offerId);
        waitingRoom.join(offerId, esperando);

        offerService.reserve(offerId, comprador, documento());
        assertThat(offerService.get(offerId).estado()).isEqualTo("AGOTADA");

        assertThat(waitingRoom.status(offerId, esperando).estado()).isEqualTo(QueueState.CERRADA);
        UUID nuevo = UUID.randomUUID();
        assertThatThrownBy(() -> waitingRoom.join(offerId, nuevo)).extracting("code").isEqualTo("QUEUE_CLOSED");
        assertThat(waitingRoom.processBatch(offerId).admitidos()).isEmpty();
        assertThat(eventos("OfferQueueUpdated", offerId).getLast().path("enFila").asLong()).isZero();
        scheduler.runNow(); // la oferta AGOTADA vuelve a cerrar la sala sin efecto
        assertThat(waitingRoom.status(offerId, esperando).estado()).isEqualTo(QueueState.CERRADA);
    }

    @Test
    void laSalaSeCierraCuandoLaOfertaTermina() {
        OfferResponse offer = ofertaConSala(3);
        UUID offerId = offer.offerId();
        UUID user = UUID.randomUUID();
        waitingRoom.join(offerId, user);
        offerService.activate(offerId);
        offerService.close(offerId, "EXPIRADA");
        assertThat(waitingRoom.status(offerId, user).estado()).isEqualTo(QueueState.CERRADA);
        UUID otro = UUID.randomUUID();
        assertThatThrownBy(() -> waitingRoom.join(offerId, otro)).extracting("code").isEqualTo("QUEUE_CLOSED");
    }

    @Test
    void laSalaSeHabilitaAlEditar() {
        OfferResponse offer = oferta(2, Instant.now().plus(2, ChronoUnit.HOURS), false);
        var editada = offerService.update(offer.offerId(), new UpdateOfferRequest(
                null, null, null, null, null, null, null, true), companyId, false);
        assertThat(editada.salaEsperaHabilitada()).isTrue();
        assertThat(editada.origenCreacion()).isEqualTo("MANUAL");
    }
}
