package com.tripbus.flashoffers.web;
import com.tripbus.flashoffers.common.security.CurrentUser;
import com.tripbus.flashoffers.service.OfferService;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController @RequestMapping("/operator/offers")
public class OperatorOfferController {
 private final OfferService service;
 public OperatorOfferController(OfferService service){this.service=service;}
 @GetMapping @PreAuthorize("hasAnyRole('OPERADOR','ADMIN')")
 public Page<OfferService.OperatorOffer> list(@RequestParam(required=false) String estado,
   @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant desde,
   @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant hasta,
   @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,Authentication auth){
  return service.operatorOffers(estado,desde,hasta,Math.max(0,page),Math.min(100,Math.max(1,size)),CurrentUser.companyId(auth),CurrentUser.hasRole(auth,"ADMIN"));
 }
 @GetMapping("/metrics") @PreAuthorize("hasAnyRole('OPERADOR','ADMIN')")
 public OfferService.OperatorMetrics metrics(@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant desde,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant hasta,Authentication auth){return service.operatorMetrics(desde,hasta,CurrentUser.companyId(auth),CurrentUser.hasRole(auth,"ADMIN"));}
}
