package com.tripbus.flashoffers.repository;

import com.tripbus.flashoffers.domain.OfferTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OfferTemplateRepository extends JpaRepository<OfferTemplate, UUID> {

    List<OfferTemplate> findByCompanyIdOrderByCreadoEnAsc(UUID companyId);

    List<OfferTemplate> findByActivaTrueOrderByCreadoEnAsc();

    List<OfferTemplate> findAllByOrderByCreadoEnAsc();
}
