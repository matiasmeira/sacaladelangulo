package com.matiasmeira.sacaladelangulo.establecimiento.model;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Bloqueo de un jugador en un establecimiento: mientras exista, ese jugador no puede
 * reservar por sí mismo en ninguna cancha de ese establecimiento (ver
 * ReservaService.crearReserva). Es una restricción por establecimiento, no global a la
 * cuenta del jugador.
 */
@Entity
@Table(name = "bloqueos_jugador",
        uniqueConstraints = @UniqueConstraint(columnNames = {"establecimiento_id", "jugador_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BloqueoJugador {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "establecimiento_id", nullable = false)
    private Establecimiento establecimiento;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "jugador_id", nullable = false)
    private Usuario jugador;

    @Column(length = 255)
    private String motivo;

    @Column(name = "fecha_bloqueo", nullable = false, updatable = false)
    private LocalDateTime fechaBloqueo;

    @PrePersist
    public void prePersist() {
        if (fechaBloqueo == null) {
            fechaBloqueo = LocalDateTime.now();
        }
    }
}
