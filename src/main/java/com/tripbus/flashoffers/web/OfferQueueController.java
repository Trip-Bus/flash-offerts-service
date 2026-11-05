package com.tripbus.flashoffers.web;

import com.tripbus.flashoffers.common.security.CurrentUser;
import com.tripbus.flashoffers.service.WaitingRoomService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** US-162: sala de espera virtual de una oferta. */
@RestController
@RequestMapping("/offers/{id}/queue")
public class OfferQueueController {

    private final WaitingRoomService waitingRoom;

    public OfferQueueController(WaitingRoomService waitingRoom) {
        this.waitingRoom = waitingRoom;
    }

    /** Entra a la sala (desde 10 min antes del inicio). 201 al entrar, 200 si ya estaba. */
    @PostMapping
    @PreAuthorize("hasRole('PASAJERO')")
    public ResponseEntity<WaitingRoomService.QueueStatus> join(@PathVariable UUID id, Authentication auth) {
        WaitingRoomService.JoinResult result = waitingRoom.join(id, CurrentUser.id(auth));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.status());
    }

    /** Estado y posición del pasajero en la sala. */
    @GetMapping("/me")
    public WaitingRoomService.QueueStatus me(@PathVariable UUID id, Authentication auth) {
        return waitingRoom.status(id, CurrentUser.id(auth));
    }
}
