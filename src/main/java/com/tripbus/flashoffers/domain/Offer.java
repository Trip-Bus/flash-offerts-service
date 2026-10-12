package com.tripbus.flashoffers.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "offers")
public class Offer {

    @Id
    private UUID id;

    @Column(name = "itinerary_id", nullable = false)
    private UUID itineraryId;

    @Column(name = "precio_oferta", nullable = false)
    private BigDecimal precioOferta;

    @Column(name = "cupo_max", nullable = false)
    private int cupoMax;

    @Column(nullable = false)
    private Instant inicio;

    @Column(nullable = false)
    private Instant fin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OfferStatus estado;

    @Column(name = "motivo_cierre")
    private String motivoCierre;

    @Column(name = "creado_por")
    private UUID creadoPor;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "offer_seats", joinColumns = @JoinColumn(name = "offer_id"))
    @Column(name = "seat_number", nullable = false)
    private List<String> asientos = new ArrayList<>();

    @Version
    private long version;

    protected Offer() {
    }

    public Offer(UUID itineraryId, BigDecimal precioOferta, int cupoMax, Instant inicio, Instant fin, UUID creadoPor,
                 Instant now) {
        this.id = UUID.randomUUID();
        this.itineraryId = itineraryId;
        this.precioOferta = precioOferta;
        this.cupoMax = cupoMax;
        this.inicio = inicio;
        this.fin = fin;
        this.creadoPor = creadoPor;
        this.creadoEn = now;
        this.estado = OfferStatus.BORRADOR;
    }

    public boolean vigente(Instant now) {
        return !now.isBefore(inicio) && now.isBefore(fin);
    }

    public void asignarAsientos(List<String> asientos) {
        this.asientos.clear();
        this.asientos.addAll(asientos);
        if (cupoMax > asientos.size()) {
            cupoMax = asientos.size();
        }
    }

    /** El borrador ya tiene sus asientos reservados: pasa a PROGRAMADA y el scheduler ya puede activarlo. */
    public void programar() {
        if (estado == OfferStatus.BORRADOR) {
            this.estado = OfferStatus.PROGRAMADA;
        }
    }

    public boolean borrador() {
        return estado == OfferStatus.BORRADOR;
    }

    public void activar(Instant now) {
        this.estado = OfferStatus.ACTIVA;
        if (inicio.isAfter(now)) {
            this.inicio = now;
        }
    }

    public void agotar() {
        this.estado = OfferStatus.AGOTADA;
    }

    public void reabrir() {
        this.estado = OfferStatus.ACTIVA;
    }

    public void cerrar(String motivo) {
        this.estado = OfferStatus.CERRADA;
        this.motivoCierre = motivo;
    }

    public UUID getId() {
        return id;
    }

    public UUID getItineraryId() {
        return itineraryId;
    }

    public BigDecimal getPrecioOferta() {
        return precioOferta;
    }

    public int getCupoMax() {
        return cupoMax;
    }

    public Instant getInicio() {
        return inicio;
    }

    public Instant getFin() {
        return fin;
    }

    public OfferStatus getEstado() {
        return estado;
    }

    public String getMotivoCierre() {
        return motivoCierre;
    }

    public List<String> getAsientos() {
        return asientos;
    }
}
