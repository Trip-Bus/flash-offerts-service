package com.tripbus.flashoffers.web;

import com.tripbus.flashoffers.common.security.CurrentUser;
import com.tripbus.flashoffers.service.OfferService;
import com.tripbus.flashoffers.web.dto.CreateOfferRequest;
import com.tripbus.flashoffers.web.dto.OfferResponse;
import com.tripbus.flashoffers.web.dto.QuotaResponse;
import com.tripbus.flashoffers.web.dto.ReservationResponse;
import com.tripbus.flashoffers.web.dto.ReserveRequest;
import com.tripbus.flashoffers.web.dto.UpdateOfferRequest;
import com.tripbus.flashoffers.web.dto.DuplicateOfferRequest;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.util.List;
import java.util.UUID;
import com.tripbus.flashoffers.service.RouteFollowService;
import com.tripbus.flashoffers.web.dto.RouteFollowRequest;
import com.tripbus.flashoffers.web.dto.RouteFollowResponse;

@RestController
@RequestMapping("/offers")
public class OfferController {

    private final OfferService offerService;
    private final RouteFollowService follows;

    @Autowired
    public OfferController(OfferService offerService, RouteFollowService follows) {
        this.offerService = offerService; this.follows=follows;
    }
    /** Constructor conservado para las pruebas y adaptadores existentes. */
    public OfferController(OfferService offerService) { this(offerService, null); }

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
    @PostMapping("/follows") public ResponseEntity<RouteFollowResponse> follow(@Valid @RequestBody RouteFollowRequest request,Authentication auth){return ResponseEntity.status(HttpStatus.CREATED).body(followService().follow(CurrentUser.id(auth),request));}
    @GetMapping("/follows") public List<RouteFollowResponse> follows(Authentication auth){return followService().list(CurrentUser.id(auth));}
    @DeleteMapping("/follows/{id}") public ResponseEntity<Void> unfollow(@PathVariable UUID id,Authentication auth){followService().delete(CurrentUser.id(auth),id);return ResponseEntity.noContent().build();}
    private RouteFollowService followService(){if(follows==null)throw new IllegalStateException("RouteFollowService no configurado");return follows;}

    @GetMapping("/showcase")
    public org.springframework.data.domain.Page<OfferService.ShowcaseOffer> showcase(
            @RequestParam(required=false) String origin, @RequestParam(required=false) String destination,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return offerService.showcase(origin,destination,Math.max(0,page),Math.min(100,Math.max(1,size)));
    }

    @PutMapping("/{id}") @PreAuthorize("hasAnyRole('OPERADOR','ADMIN')")
    public OfferResponse update(@PathVariable UUID id,@Valid @RequestBody UpdateOfferRequest request,Authentication auth){
        return offerService.update(id,request,CurrentUser.companyId(auth),CurrentUser.hasRole(auth,"ADMIN"));
    }
    @DeleteMapping("/{id}") @PreAuthorize("hasAnyRole('OPERADOR','ADMIN')")
    public OfferResponse cancel(@PathVariable UUID id,Authentication auth){return offerService.cancelByOperator(id,CurrentUser.companyId(auth),CurrentUser.hasRole(auth,"ADMIN"));}
    @PostMapping("/{id}/duplicate") @PreAuthorize("hasAnyRole('OPERADOR','ADMIN')")
    public ResponseEntity<OfferResponse> duplicate(@PathVariable UUID id,@Valid @RequestBody DuplicateOfferRequest request,Authentication auth){
        return ResponseEntity.status(HttpStatus.CREATED).body(offerService.duplicate(id,request,CurrentUser.companyId(auth),CurrentUser.hasRole(auth,"ADMIN"),CurrentUser.id(auth)));
    }

    /** GetOfferStatus. */
    @GetMapping("/{id}")
    public OfferResponse get(@PathVariable UUID id) {
        return offerService.get(id);
    }
    @GetMapping("/{id}/metrics") @PreAuthorize("hasAnyRole('OPERADOR','ADMIN')")
    public OfferService.OfferMetrics metrics(@PathVariable UUID id,Authentication auth){return offerService.metrics(id,CurrentUser.companyId(auth),CurrentUser.hasRole(auth,"ADMIN"));}

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
