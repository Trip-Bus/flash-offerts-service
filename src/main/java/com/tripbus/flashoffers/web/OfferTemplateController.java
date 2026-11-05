package com.tripbus.flashoffers.web;

import com.tripbus.flashoffers.common.security.OperatorScope;
import com.tripbus.flashoffers.service.OfferTemplateService;
import com.tripbus.flashoffers.web.dto.OfferTemplateRequest;
import com.tripbus.flashoffers.web.dto.OfferTemplateResponse;
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

/** US-169: plantillas de ofertas recurrentes (OPERADOR de la empresa o ADMIN). */
@RestController
@RequestMapping("/operator/offer-templates")
@PreAuthorize("hasAnyRole('OPERADOR','ADMIN')")
public class OfferTemplateController {

    private final OfferTemplateService service;

    public OfferTemplateController(OfferTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public List<OfferTemplateResponse> list(Authentication auth) {
        return service.list(OperatorScope.of(auth));
    }

    @GetMapping("/{id}")
    public OfferTemplateResponse get(@PathVariable UUID id, Authentication auth) {
        return service.get(id, OperatorScope.of(auth));
    }

    @PostMapping
    public ResponseEntity<OfferTemplateResponse> create(@Valid @RequestBody OfferTemplateRequest request,
                                                        Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request, OperatorScope.of(auth)));
    }

    @PutMapping("/{id}")
    public OfferTemplateResponse update(@PathVariable UUID id, @Valid @RequestBody OfferTemplateRequest request,
                                        Authentication auth) {
        return service.update(id, request, OperatorScope.of(auth));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication auth) {
        service.delete(id, OperatorScope.of(auth));
        return ResponseEntity.noContent().build();
    }
}
