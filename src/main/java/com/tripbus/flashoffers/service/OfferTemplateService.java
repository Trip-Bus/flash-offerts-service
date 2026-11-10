package com.tripbus.flashoffers.service;

import com.tripbus.flashoffers.common.error.ApiException;
import com.tripbus.flashoffers.common.security.OperatorScope;
import com.tripbus.flashoffers.domain.Offer;
import com.tripbus.flashoffers.domain.OfferTemplate;
import com.tripbus.flashoffers.repository.OfferTemplateRepository;
import com.tripbus.flashoffers.web.dto.OfferTemplateRequest;
import com.tripbus.flashoffers.web.dto.OfferTemplateResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.util.List;
import java.util.UUID;

/** US-169: CRUD de plantillas de ofertas recurrentes (OPERADOR de la empresa o ADMIN). */
@Service
public class OfferTemplateService {

    private static final String NOT_FOUND = "OFFER_TEMPLATE_NOT_FOUND";

    private final OfferTemplateRepository templates;
    private final Clock clock;
    private final BigDecimal minDiscount;
    private final BigDecimal maxDiscount;

    public OfferTemplateService(OfferTemplateRepository templates, Clock clock,
                                @Value("${tripbus.offers.min-discount:5}") BigDecimal minDiscount,
                                @Value("${tripbus.offers.max-discount:70}") BigDecimal maxDiscount) {
        this.templates = templates;
        this.clock = clock;
        this.minDiscount = minDiscount;
        this.maxDiscount = maxDiscount;
    }

    @Transactional(readOnly = true)
    public List<OfferTemplateResponse> list(OperatorScope scope) {
        List<OfferTemplate> all = scope.admin() ? templates.findAllByOrderByCreadoEnAsc()
                : templates.findByCompanyIdOrderByCreadoEnAsc(scope.companyId());
        return all.stream().map(OfferTemplateResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public OfferTemplateResponse get(UUID id, OperatorScope scope) {
        return OfferTemplateResponse.from(find(id, scope));
    }

    @Transactional
    public OfferTemplateResponse create(OfferTemplateRequest request, OperatorScope scope) {
        OfferTemplate template = new OfferTemplate(scope.companyFor(request.companyId()), clock.instant());
        apply(template, request);
        return OfferTemplateResponse.from(templates.save(template));
    }

    @Transactional
    public OfferTemplateResponse update(UUID id, OfferTemplateRequest request, OperatorScope scope) {
        OfferTemplate template = find(id, scope);
        apply(template, request);
        return OfferTemplateResponse.from(template);
    }

    @Transactional
    public void delete(UUID id, OperatorScope scope) {
        templates.delete(find(id, scope));
    }

    private void apply(OfferTemplate template, OfferTemplateRequest r) {
        GeneratedOffers.validateDiscount(r.descuento(), minDiscount, maxDiscount);
        int maxPorUsuario = r.maxPorUsuario() != null ? r.maxPorUsuario()
                : Math.min(Offer.DEFAULT_MAX_POR_USUARIO, r.cupo());
        if (maxPorUsuario > r.cupo()) {
            throw ApiException.badRequest("MAX_PER_USER_EXCEEDS_QUOTA",
                    "El máximo por usuario no puede ser mayor que el cupo");
        }
        List<DayOfWeek> dias = r.diasSemana().stream().map(DayOfWeek::of).toList();
        template.configurar(r.routeId(),
                new OfferTemplate.Horario(dias, r.horaInicio(), r.duracionMinutos(), r.horasAntesSalida()),
                new OfferTemplate.Condiciones(r.descuento(), r.cupo(), maxPorUsuario,
                        r.permiteReprogramar() == null || r.permiteReprogramar(), Boolean.TRUE.equals(r.salaEspera())),
                r.activa() == null || r.activa(), clock.instant());
    }

    private OfferTemplate find(UUID id, OperatorScope scope) {
        OfferTemplate template = templates.findById(id)
                .orElseThrow(() -> ApiException.notFound(NOT_FOUND, "La plantilla no existe"));
        scope.check(template.getCompanyId(), NOT_FOUND, "La plantilla no existe");
        return template;
    }
}
