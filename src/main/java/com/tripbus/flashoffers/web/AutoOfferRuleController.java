package com.tripbus.flashoffers.web;

import com.tripbus.flashoffers.common.security.OperatorScope;
import com.tripbus.flashoffers.service.AutoOfferRuleService;
import com.tripbus.flashoffers.web.dto.AutoOfferRuleRequest;
import com.tripbus.flashoffers.web.dto.AutoOfferRuleResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** US-167: reglas de ofertas automáticas por baja ocupación (OPERADOR de la empresa o ADMIN). */
@RestController
@RequestMapping("/operator/auto-offer-rules")
@PreAuthorize("hasAnyRole('OPERADOR','ADMIN')")
public class AutoOfferRuleController {

    private final AutoOfferRuleService service;

    public AutoOfferRuleController(AutoOfferRuleService service) {
        this.service = service;
    }

    @GetMapping
    public List<AutoOfferRuleResponse> list(Authentication auth) {
        return service.list(OperatorScope.of(auth));
    }

    @GetMapping("/{id}")
    public AutoOfferRuleResponse get(@PathVariable UUID id, Authentication auth) {
        return service.get(id, OperatorScope.of(auth));
    }

    @PostMapping
    public ResponseEntity<AutoOfferRuleResponse> create(@Valid @RequestBody AutoOfferRuleRequest request,
                                                        Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request, OperatorScope.of(auth)));
    }

    @PutMapping("/{id}")
    public AutoOfferRuleResponse update(@PathVariable UUID id, @Valid @RequestBody AutoOfferRuleRequest request,
                                        Authentication auth) {
        return service.update(id, request, OperatorScope.of(auth));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication auth) {
        service.delete(id, OperatorScope.of(auth));
        return ResponseEntity.noContent().build();
    }
}
