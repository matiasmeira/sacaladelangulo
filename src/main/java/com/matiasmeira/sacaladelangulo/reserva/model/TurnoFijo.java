package com.matiasmeira.sacaladelangulo.reserva.model;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * La REGLA de un turno fijo semanal: qué cancha, qué día, qué horario, durante qué período
 * y para quién. Las ocurrencias son Reservas materializadas que apuntan acá con
 * Reserva.turnoFijo.
 *
 * <p>Existe como entidad y no como una simple columna de agrupación en `reservas` porque la
 * regla tiene que sobrevivir a sus ocurrencias: si el dueño cancela las 17 reservas de la
 * serie, con una columna no quedaría nada que renovar.
 *
 * <p>El período es INMUTABLE. Terminar una serie antes de tiempo no acorta fechaFinPeriodo:
 * se marca estado=CANCELADO con canceladoDesde, para no perder hasta dónde llegaba el
 * compromiso original.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "turnos_fijos")
public class TurnoFijo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancha_id", nullable = false)
    private Cancha cancha;

    @Enumerated(EnumType.STRING)
    @Column(name = "deporte_seleccionado", nullable = false)
    private Deporte deporteSeleccionado;

    @Enumerated(EnumType.STRING)
    @Column(name = "dia_semana", nullable = false)
    private DayOfWeek diaSemana;

    @Column(name = "hora_inicio", nullable = false)
    private LocalTime horaInicio;

    @Column(name = "hora_fin", nullable = false)
    private LocalTime horaFin;

    @Column(name = "fecha_inicio_periodo", nullable = false)
    private LocalDate fechaInicioPeriodo;

    @Column(name = "fecha_fin_periodo", nullable = false)
    private LocalDate fechaFinPeriodo;

    /** Nulo en las series de mostrador, que se identifican con nombreClienteManual. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "jugador_id")
    private Usuario jugador;

    @Column(name = "nombre_cliente_manual")
    private String nombreClienteManual;

    @Column(name = "telefono_cliente_manual")
    private String telefonoClienteManual;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoTurnoFijo estado;

    /**
     * Desde qué fecha la serie dejó de generar compromiso. No nulo si y sólo si el estado
     * es CANCELADO; hay un CHECK en V24 que lo garantiza a nivel base.
     */
    @Column(name = "cancelado_desde")
    private LocalDate canceladoDesde;

    /**
     * Id de la serie del año anterior que se renovó para crear esta. Con índice único: una
     * serie se renueva UNA vez, y el segundo intento falla en la base aunque el chequeo del
     * servicio no llegue a correr.
     */
    @Column(name = "renovado_desde_id")
    private Long renovadoDesdeId;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime fechaCreacion;

    @Version
    private Long version;

    @PrePersist
    public void prePersist() {
        if (fechaCreacion == null) {
            fechaCreacion = LocalDateTime.now();
        }
        if (estado == null) {
            estado = EstadoTurnoFijo.ACTIVO;
        }
    }
}
