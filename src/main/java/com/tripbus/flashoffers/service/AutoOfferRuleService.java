package com.tripbus.flashoffers.service;

import com.tripbus.flashoffers.common.error.ApiException;
import com.tripbus.flashoffers.common.security.OperatorScope;
import com.tripbus.flashoffers.domain.AutoOfferRule;
import com.tripbus.flashoffers.repository.AutoOfferRuleRepository;
import com.tripbus.flashoffers.web.dto.AutoOfferRuleRequest;
import com.tripbus.flashoffers.web.dto.AutoOfferRuleResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** US-167: CRUD de reglas de ofertas automáticas (OPERADOR de la empresa o ADMIN). */
@Service
public class AutoOfferRuleService {

    private static final String NOT_FOUND = "AUTO_OFFER_RULE_NOT_FOUND";

    private final AutoOfferRuleRepository rules;
    private final Clock clock;
    private final BigDecimal minDiscount;
    private final BigDecimal maxDiscount;

    public AutoOfferRuleService(AutoOfferRuleRepository rules, Clock clock,
                                @Value("${tripbus.offers.min-discount:5}") BigDecimal minDiscount,
                                @Value("${tripbus.offers.max-discount:70}") BigDecimal maxDiscount) {
        this.rules = rules;
        this.clock = clock;
        this.minDiscount = minDiscount;
        this.maxDiscount = maxDiscount;
    }

    @Transactional(readOnly = true)
    public List<AutoOfferRuleResponse> list(OperatorScope scope) {
        List<AutoOfferRule> all = scope.admin() ? rules.findAllByOrderByCreadoEnAsc()
                : rules.findByCompanyIdOrderByCreadoEnAsc(scope.companyId());
        return all.stream().map(AutoOfferRuleResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AutoOfferRuleResponse get(UUID id, OperatorScope scope) {
        return AutoOfferRuleResponse.from(find(id, scope));
    }

    @Transactional
    public AutoOfferRuleResponse create(AutoOfferRuleRequest request, OperatorScope scope) {
        AutoOfferRule rule = new AutoOfferRule(scope.companyFor(request.companyId()), clock.instant());
        apply(rule, request);
        return AutoOfferRuleResponse.from(rules.save(rule));
    }

    @Transactional
    public AutoOfferRuleResponse update(UUID id, AutoOfferRuleRequest request, OperatorScope scope) {
        AutoOfferRule rule = find(id, scope);
        apply(rule, request);
        return AutoOfferRuleResponse.from(rule);
    }

    @Transactional
    public void delete(UUID id, OperatorScope scope) {
        rules.delete(find(id, scope));
    }

    private void apply(AutoOfferRule rule, AutoOfferRuleRequest r) {
        GeneratedOffers.validateDiscount(r.descuento(), minDiscount, maxDiscount);
        rule.configurar(r.routeId(), r.horasAntes(), r.ocupacionMaxima(), r.descuento(), r.cupoMaximo(),
                r.activa() == null || r.activa(), clock.instant());
    }

    private AutoOfferRule find(UUID id, OperatorScope scope) {
        AutoOfferRule rule = rules.findById(id)
                .orElseThrow(() -> ApiException.notFound(NOT_FOUND, "La regla no existe"));
        scope.check(rule.getCompanyId(), NOT_FOUND, "La regla no existe");
        return rule;
    }
}
