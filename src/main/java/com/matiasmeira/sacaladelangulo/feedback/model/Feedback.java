package com.matiasmeira.sacaladelangulo.feedback.model;

import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

/**
 * Calificación y comentario que un jugador deja sobre una reserva ya finalizada.
 * A lo sumo un Feedback por Reserva (reserva_id es unique). No guarda una referencia
 * directa a Usuario ni a Establecimiento: se navega siempre a través de la reserva
 * (reserva.getJugador() / reserva.getCancha().getEstablecimiento()), igual que hace
 * el resto del dominio con Reserva -> Cancha -> Establecimiento.
 */
@Slf4j
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "feedbacks")
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reserva_id", nullable = false, unique = true)
    private Reserva reserva;

    @Column(nullable = false)
    private Integer puntuacion;

    @Column(length = 1000)
    private String comentario;

    @Column(nullable = false)
    @Builder.Default
    private Boolean destacado = false;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime fechaCreacion;

    @PrePersist
    public void prePersist() {
        if (fechaCreacion == null) {
            fechaCreacion = LocalDateTime.now();
            log.debug("Inicializando fechaCreacion a {}", fechaCreacion);
        }
        if (destacado == null) {
            destacado = false;
        }
    }
}
