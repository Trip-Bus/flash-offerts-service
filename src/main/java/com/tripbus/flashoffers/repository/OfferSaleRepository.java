package com.tripbus.flashoffers.repository;

import com.tripbus.flashoffers.domain.OfferSale;
import com.tripbus.flashoffers.domain.SaleStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OfferSaleRepository extends JpaRepository<OfferSale, UUID> {

    Optional<OfferSale> findByOfferIdAndUserIdAndDocId(UUID offerId, UUID userId, String docId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from OfferSale s where s.holdId = :holdId")
    Optional<OfferSale> lockByHoldId(@Param("holdId") UUID holdId);

    Optional<OfferSale> findByOfferIdAndHoldId(UUID offerId, UUID holdId);

    long countByOfferIdAndEstadoIn(UUID offerId, Collection<SaleStatus> estados);

    List<OfferSale> findByOfferIdAndEstadoIn(UUID offerId, Collection<SaleStatus> estados);

    long countByOfferIdAndUserIdAndEstadoIn(UUID offerId, UUID userId, Collection<SaleStatus> estados);
}
