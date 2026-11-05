package com.tripbus.flashoffers.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * US-169: plantilla de oferta recurrente. Para cada viaje de la ruta que sale un día de {@code diasSemana}, la oferta
 * empieza a la {@code horaInicio} del día que queda {@code horasAntesSalida} horas antes de la salida y dura
 * {@code duracionMinutos}.
 */
@Entity
@Table(name = "offer_templates")
public class OfferTemplate {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "route_id", nullable = false)
    private UUID routeId;

    /** Días ISO separados por coma (1 = lunes ... 7 = domingo). */
    @Column(name = "dias_semana", nullable = false)
    private String diasSemana;

    @Column(name = "hora_inicio", nullable = false)
    private LocalTime horaInicio;

    @Column(name = "duracion_minutos", nullable = false)
    private int duracionMinutos;

    @Column(nullable = false)
    private BigDecimal descuento;

    @Column(nullable = false)
    private int cupo;

    @Column(name = "max_por_usuario", nullable = false)
    private int maxPorUsuario;

    @Column(name = "permite_reprogramar", nullable = false)
    private boolean permiteReprogramar;

    @Column(name = "sala_espera", nullable = false)
    private boolean salaEspera;

    @Column(name = "horas_antes_salida", nullable = false)
    private int horasAntesSalida;

    @Column(nullable = false)
    private boolean activa;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    protected OfferTemplate() {
    }

    public OfferTemplate(UUID companyId, Instant now) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.creadoEn = now;
        this.actualizadoEn = now;
    }

    /** Horario de la plantilla: días de salida, hora de inicio, duración y horas antes de la salida. */
    public record Horario(Collection<DayOfWeek> dias, LocalTime horaInicio, int duracionMinutos, int horasAntesSalida) {
    }

    /** Condiciones de la oferta que genera la plantilla. */
    public record Condiciones(BigDecimal descuento, int cupo, int maxPorUsuario, boolean permiteReprogramar,
                              boolean salaEspera) {
    }

    /** Reemplaza la configuración de la plantilla (crear y editar). */
    public void configurar(UUID routeId, Horario horario, Condiciones condiciones, boolean activa, Instant now) {
        this.routeId = routeId;
        this.diasSemana = horario.dias().stream().distinct().sorted().map(d -> String.valueOf(d.getValue()))
                .collect(Collectors.joining(","));
        this.horaInicio = horario.horaInicio();
        this.duracionMinutos = horario.duracionMinutos();
        this.horasAntesSalida = horario.horasAntesSalida();
        this.descuento = condiciones.descuento();
        this.cupo = condiciones.cupo();
        this.maxPorUsuario = condiciones.maxPorUsuario();
        this.permiteReprogramar = condiciones.permiteReprogramar();
        this.salaEspera = condiciones.salaEspera();
        this.activa = activa;
        this.actualizadoEn = now;
    }

    public Set<DayOfWeek> dias() {
        Set<DayOfWeek> dias = EnumSet.noneOf(DayOfWeek.class);
        Arrays.stream(diasSemana.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .forEach(s -> dias.add(DayOfWeek.of(Integer.parseInt(s))));
        return dias;
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

    public LocalTime getHoraInicio() {
        return horaInicio;
    }

    public int getDuracionMinutos() {
        return duracionMinutos;
    }

    public BigDecimal getDescuento() {
        return descuento;
    }

    public int getCupo() {
        return cupo;
    }

    public int getMaxPorUsuario() {
        return maxPorUsuario;
    }

    public boolean isPermiteReprogramar() {
        return permiteReprogramar;
    }

    public boolean isSalaEspera() {
        return salaEspera;
    }

    public int getHorasAntesSalida() {
        return horasAntesSalida;
    }

    public boolean isActiva() {
        return activa;
    }

    public Instant getActualizadoEn() {
        return actualizadoEn;
    }
}
