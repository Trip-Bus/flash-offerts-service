package com.tripbus.flashoffers.web;

import com.tripbus.flashoffers.service.OfferService;
import com.tripbus.flashoffers.web.dto.ReservationResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Usado por Booking para validar el precio de oferta de una retención. */
@RestController
@RequestMapping("/internal/offers")
public class InternalOfferController {

    private final OfferService offerService;

    public InternalOfferController(OfferService offerService) {
        this.offerService = offerService;
    }

    @GetMapping("/{offerId}/reservations/by-hold/{holdId}")
    public ReservationResponse byHold(@PathVariable UUID offerId, @PathVariable UUID holdId) {
        return offerService.reservationByHold(offerId, holdId);
    }
}
