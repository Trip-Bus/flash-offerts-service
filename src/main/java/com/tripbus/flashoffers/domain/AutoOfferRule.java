package com.tripbus.flashoffers.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * US-167: regla de oferta automática por baja ocupación. Para los viajes de la empresa (de la ruta, si se indica)
 * que salen dentro de {@code horasAntes} con ocupación por debajo de {@code ocupacionMaxima} (%) y sin oferta, el job
 * crea una oferta con {@code descuento} (%) sobre los asientos libres, hasta {@code cupoMaximo}.
 */
@Entity
@Table(name = "auto_offer_rules")
public class AutoOfferRule {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "route_id")
    private UUID routeId;

    @Column(name = "horas_antes", nullable = false)
    private int horasAntes;

    @Column(name = "ocupacion_maxima", nullable = false)
    private BigDecimal ocupacionMaxima;

    @Column(nullable = false)
    private BigDecimal descuento;

    @Column(name = "cupo_maximo", nullable = false)
    private int cupoMaximo;

    @Column(nullable = false)
    private boolean activa;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    protected AutoOfferRule() {
    }

    public AutoOfferRule(UUID companyId, Instant now) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.creadoEn = now;
        this.actualizadoEn = now;
    }

    /** Reemplaza la configuración de la regla (crear y editar). */
    public void configurar(UUID routeId, int horasAntes, BigDecimal ocupacionMaxima, BigDecimal descuento,
                           int cupoMaximo, boolean activa, Instant now) {
        this.routeId = routeId;
        this.horasAntes = horasAntes;
        this.ocupacionMaxima = ocupacionMaxima;
        this.descuento = descuento;
        this.cupoMaximo = cupoMaximo;
        this.activa = activa;
        this.actualizadoEn = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getRouteId() {
        return routeId;
    }

    public int getHorasAntes() {
        return horasAntes;
    }

    public BigDecimal getOcupacionMaxima() {
        return ocupacionMaxima;
    }

    public BigDecimal getDescuento() {
        return descuento;
    }

    public int getCupoMaximo() {
        return cupoMaximo;
    }

    public boolean isActiva() {
        return activa;
    }

    public Instant getActualizadoEn() {
        return actualizadoEn;
    }
}
