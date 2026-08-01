package com.matiasmeira.sacaladelangulo.establecimiento.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Entidad que representa una tarifa de precio dinámico para una cancha.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tarifas")
public class Tarifa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancha_id", nullable = false)
    private Cancha cancha;

    @Enumerated(EnumType.STRING)
    @Column(name = "dia_semana", nullable = false)
    private DayOfWeek diaSemana;

    @Column(name = "hora_inicio", nullable = false)
    private LocalTime horaInicio;

    @Column(name = "hora_fin", nullable = false)
    private LocalTime horaFin;

    @Column(nullable = false)
    private BigDecimal precio;

    /**
     * Precio exacto (no proporcional) para una duración puntual dentro de esta franja
     * horaria/día. Mismo criterio de fallback que Cancha.preciosPorDuracion: si la
     * duración reservada no tiene entrada acá, se calcula proporcional sobre precio.
     */
    @ElementCollection
    @CollectionTable(name = "tarifa_precios_duracion", joinColumns = @JoinColumn(name = "tarifa_id"))
    @MapKeyColumn(name = "duracion_minutos")
    @Column(name = "precio")
    @Builder.Default
    private Map<Integer, BigDecimal> preciosPorDuracion = new HashMap<>();
}
