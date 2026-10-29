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

    /** US-058: máximo por persona cuando la oferta no indica otro (o el cupo, si es menor). */
    public static final int DEFAULT_MAX_POR_USUARIO = 2;

    @Id
    private UUID id;

    @Column(name = "itinerary_id", nullable = false)
    private UUID itineraryId;

    @Column(name = "company_id") private UUID companyId;
    private String origen;
    private String destino;
    @Column(name = "fecha_salida") private Instant fechaSalida;
    @Column(name = "precio_base") private BigDecimal precioBase;
    @Column(name = "empresa_nombre") private String empresaNombre;
    @Column(name = "permite_reprogramar", nullable = false) private boolean permiteReprogramar = true;

    @Column(name = "precio_oferta", nullable = false)
    private BigDecimal precioOferta;

    @Column(name = "cupo_max", nullable = false)
    private int cupoMax;

    /** US-058: cupos que una misma persona (userId) puede tomar en la oferta, sumando todos sus documentos. */
    @Column(name = "max_por_usuario", nullable = false)
    private int maxPorUsuario;

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

    @Column(name = "agotada_en")
    private Instant agotadaEn;

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
        this(itineraryId, precioOferta, cupoMax, Math.min(DEFAULT_MAX_POR_USUARIO, cupoMax), inicio, fin, creadoPor,
                now);
    }

    public Offer(UUID itineraryId, BigDecimal precioOferta, int cupoMax, int maxPorUsuario, Instant inicio, Instant fin,
                 UUID creadoPor, Instant now) {
        this.id = UUID.randomUUID();
        this.itineraryId = itineraryId;
        this.precioOferta = precioOferta;
        this.cupoMax = cupoMax;
        this.maxPorUsuario = maxPorUsuario;
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
        // ck_offers_max_por_usuario: el máximo por persona nunca supera el cupo.
        if (maxPorUsuario > cupoMax) {
            maxPorUsuario = cupoMax;
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

    public void agotar(Instant now) {
        this.estado = OfferStatus.AGOTADA;
        this.agotadaEn = now;
    }

    public void reabrir() {
        this.estado = OfferStatus.ACTIVA;
    }

    public void cerrar(String motivo) {
        this.estado = OfferStatus.CERRADA;
        this.motivoCierre = motivo;
    }

    public void datosViaje(UUID companyId, String origen, String destino, Instant salida, BigDecimal base,
                           String empresa, boolean permiteReprogramar) {
        this.companyId = companyId; this.origen = origen; this.destino = destino; this.fechaSalida = salida;
        this.precioBase = base; this.empresaNombre = empresa; this.permiteReprogramar = permiteReprogramar;
    }

    public void editar(BigDecimal precio, int cupo, int maxUsuario, Instant inicio, Instant fin,
                       List<String> seats, boolean reprogramar) {
        this.precioOferta=precio; this.cupoMax=cupo; this.maxPorUsuario=maxUsuario;
        this.inicio=inicio; this.fin=fin; this.permiteReprogramar=reprogramar;
        this.asientos.clear(); this.asientos.addAll(seats);
    }
    public void reprogramarViaje(Instant salida) { this.fechaSalida=salida; if (fin.isAfter(salida)) this.fin=salida; }
    public UUID getCompanyId(){return companyId;} public String getOrigen(){return origen;} public String getDestino(){return destino;}
    public Instant getFechaSalida(){return fechaSalida;} public BigDecimal getPrecioBase(){return precioBase;}
    public String getEmpresaNombre(){return empresaNombre;} public boolean isPermiteReprogramar(){return permiteReprogramar;}

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

    public int getMaxPorUsuario() {
        return maxPorUsuario;
    }

    public Instant getInicio() {
        return inicio;
    }

    public Instant getFin() {
        return fin;
    }
    public Instant getAgotadaEn() { return agotadaEn; }

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
