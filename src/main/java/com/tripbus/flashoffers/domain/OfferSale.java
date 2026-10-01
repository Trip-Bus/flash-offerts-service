package com.tripbus.flashoffers.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "offer_sales")
public class OfferSale {

    @Id
    private UUID id;

    @Column(name = "offer_id", nullable = false)
    private UUID offerId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "doc_id", nullable = false)
    private String docId;

    @Column(name = "seat_number", nullable = false)
    private String seatNumber;

    @Column(name = "hold_id")
    private UUID holdId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SaleStatus estado;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    protected OfferSale() {
    }

    public OfferSale(UUID offerId, UUID userId, String docId, String seatNumber, UUID holdId, Instant now) {
        this.id = UUID.randomUUID();
        this.offerId = offerId;
        this.userId = userId;
        this.docId = docId;
        this.seatNumber = seatNumber;
        this.holdId = holdId;
        this.estado = SaleStatus.RESERVADA;
        this.creadoEn = now;
        this.actualizadoEn = now;
    }

    public void reservarDeNuevo(String seatNumber, UUID holdId, Instant now) {
        this.seatNumber = seatNumber;
        this.holdId = holdId;
        this.estado = SaleStatus.RESERVADA;
        this.actualizadoEn = now;
    }

    public void vender(Instant now) {
        this.estado = SaleStatus.VENDIDA;
        this.actualizadoEn = now;
    }

    public void liberar(Instant now) {
        this.estado = SaleStatus.LIBERADA;
        this.actualizadoEn = now;
    }

    public boolean viva() {
        return estado == SaleStatus.RESERVADA || estado == SaleStatus.VENDIDA;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOfferId() {
        return offerId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getDocId() {
        return docId;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public UUID getHoldId() {
        return holdId;
    }

    public SaleStatus getEstado() {
        return estado;
    }

    public Instant getActualizadoEn() {
        return actualizadoEn;
    }
}
