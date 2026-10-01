package com.tripbus.flashoffers.web;

import com.tripbus.flashoffers.common.security.CurrentUser;
import com.tripbus.flashoffers.service.OfferService;
import com.tripbus.flashoffers.web.dto.CreateOfferRequest;
import com.tripbus.flashoffers.web.dto.OfferResponse;
import com.tripbus.flashoffers.web.dto.QuotaResponse;
import com.tripbus.flashoffers.web.dto.ReservationResponse;
import com.tripbus.flashoffers.web.dto.ReserveRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/offers")
public class OfferController {

    private final OfferService offerService;

    public OfferController(OfferService offerService) {
        this.offerService = offerService;
    }

    /** US-054: crear oferta relámpago (operador de la empresa o administrador). */
    @PostMapping
    @PreAuthorize("hasAnyRole('OPERADOR', 'ADMIN')")
    public ResponseEntity<OfferResponse> create(@Valid @RequestBody CreateOfferRequest request,
                                                Authentication authentication) {
        boolean isOperator = CurrentUser.hasRole(authentication, "OPERADOR");
        OfferResponse offer = offerService.create(request, CurrentUser.id(authentication), isOperator,
                CurrentUser.companyId(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(offer);
    }

    @GetMapping
    public List<OfferResponse> list(@RequestParam(required = false) UUID itineraryId) {
        return offerService.list(itineraryId);
    }

    /** GetOfferStatus. */
    @GetMapping("/{id}")
    public OfferResponse get(@PathVariable UUID id) {
        return offerService.get(id);
    }

    /** GetRemainingQuota + tiempo restante (US-056). */
    @GetMapping("/{id}/quota")
    public QuotaResponse quota(@PathVariable UUID id) {
        return offerService.quota(id);
    }

    /** ReserveOfferSeat (US-058). 201 nueva reserva, 200 si ya existía para esa persona, 409 si se agotó. */
    @PostMapping("/{id}/reservations")
    public ResponseEntity<ReservationResponse> reserve(@PathVariable UUID id, @Valid @RequestBody ReserveRequest request,
                                                       Authentication authentication) {
        OfferService.ReserveResult result = offerService.reserve(id, CurrentUser.id(authentication), request.docId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.reservation());
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('OPERADOR', 'ADMIN')")
    public OfferResponse activate(@PathVariable UUID id) {
        offerService.activate(id);
        return offerService.get(id);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('OPERADOR', 'ADMIN')")
    public OfferResponse close(@PathVariable UUID id) {
        offerService.close(id, "CERRADA_MANUALMENTE");
        return offerService.get(id);
    }
}
