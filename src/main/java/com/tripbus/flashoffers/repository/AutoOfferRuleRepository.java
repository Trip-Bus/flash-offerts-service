package com.tripbus.flashoffers.repository;

import com.tripbus.flashoffers.domain.AutoOfferRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AutoOfferRuleRepository extends JpaRepository<AutoOfferRule, UUID> {

    List<AutoOfferRule> findByCompanyIdOrderByCreadoEnAsc(UUID companyId);

    List<AutoOfferRule> findByActivaTrueOrderByCreadoEnAsc();

    List<AutoOfferRule> findAllByOrderByCreadoEnAsc();
}
