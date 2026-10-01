package com.tripbus.flashoffers.repository;

import com.tripbus.flashoffers.domain.Offer;
import com.tripbus.flashoffers.domain.OfferStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OfferRepository extends JpaRepository<Offer, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Offer o where o.id = :id")
    Optional<Offer> lockById(@Param("id") UUID id);

    @Query("select o.id from Offer o where o.estado = :estado and o.inicio <= :now and o.fin > :now")
    List<UUID> findIdsToActivate(@Param("estado") OfferStatus estado, @Param("now") Instant now);

    @Query("select o.id from Offer o where o.estado in :estados and o.fin <= :now")
    List<UUID> findIdsToClose(@Param("estados") Collection<OfferStatus> estados, @Param("now") Instant now);

    List<Offer> findByItineraryIdOrderByInicioDesc(UUID itineraryId);

    List<Offer> findByEstadoInOrderByInicioAsc(Collection<OfferStatus> estados);

    @Query("select o.id from Offer o where o.itineraryId = :itineraryId and o.estado <> :estado")
    List<UUID> findIdsByItineraryIdAndEstadoNot(@Param("itineraryId") UUID itineraryId,
                                                @Param("estado") OfferStatus estado);
}
