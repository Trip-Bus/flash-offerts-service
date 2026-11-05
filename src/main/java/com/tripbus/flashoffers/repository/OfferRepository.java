package com.tripbus.flashoffers.repository;

import com.tripbus.flashoffers.domain.Offer;
import com.tripbus.flashoffers.domain.OfferSource;
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

    List<Offer> findByItineraryIdAndEstadoNotOrderByInicioDesc(UUID itineraryId, OfferStatus estado);

    @Query("select o.id from Offer o where o.estado = :estado and o.creadoEn < :limite")
    List<UUID> findIdsByEstadoCreatedBefore(@Param("estado") OfferStatus estado, @Param("limite") Instant limite);

    List<Offer> findByEstadoInOrderByInicioAsc(Collection<OfferStatus> estados);

    @Query("select o from Offer o where o.itineraryId=:itinerary and o.estado<>:closed and o.inicio < :fin and o.fin > :inicio")
    List<Offer> findOverlaps(@Param("itinerary") UUID itinerary, @Param("inicio") Instant inicio,
                             @Param("fin") Instant fin, @Param("closed") OfferStatus closed);

    List<Offer> findByCompanyId(UUID companyId);
    List<Offer> findByCompanyIdIsNull();

    @Query("select o.id from Offer o where o.itineraryId = :itineraryId and o.estado <> :estado")
    List<UUID> findIdsByItineraryIdAndEstadoNot(@Param("itineraryId") UUID itineraryId,
                                                @Param("estado") OfferStatus estado);

    @Query("select o.id from Offer o where o.estado = :estado")
    List<UUID> findIdsByEstado(@Param("estado") OfferStatus estado);

    /** US-162: ofertas con sala de espera en un estado (lotes de admisión y cierre de la sala). */
    @Query("select o.id from Offer o where o.salaEsperaHabilitada = true and o.estado = :estado")
    List<UUID> findIdsWithWaitingRoomByEstado(@Param("estado") OfferStatus estado);

    /** US-167: el viaje ya tiene una oferta que no está cerrada. */
    boolean existsByItineraryIdAndEstadoNot(UUID itineraryId, OfferStatus estado);

    /** US-167: una sola oferta automática por viaje. */
    boolean existsByItineraryIdAndOrigenCreacion(UUID itineraryId, OfferSource origen);

    /** US-169: la plantilla ya generó la oferta de este viaje. */
    boolean existsByTemplateIdAndItineraryId(UUID templateId, UUID itineraryId);
}
