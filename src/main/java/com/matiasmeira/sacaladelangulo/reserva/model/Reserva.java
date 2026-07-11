package com.matiasmeira.sacaladelangulo.reserva.model;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entidad que representa una reserva de cancha deportiva.
 * Implementa Optimistic Locking mediante @Version para garantizar concurrencia segura.
 */
@Slf4j
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "reservas")
public class Reserva {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Usuario que realiza la reserva (jugador). Nulo cuando la reserva fue cargada
     * manualmente por el dueño para un cliente presencial/telefónico sin cuenta
     * en la plataforma (ver nombreClienteManual/telefonoClienteManual).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "jugador_id", nullable = true)
    private Usuario jugador;

    /**
     * Cancha deportiva reservada.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancha_id", nullable = false)
    private Cancha cancha;

    /**
     * Nombre del cliente para reservas de mostrador (sin usuario registrado).
     */
    @Column(name = "nombre_cliente_manual")
    private String nombreClienteManual;

    /**
     * Teléfono del cliente para reservas de mostrador (opcional).
     */
    @Column(name = "telefono_cliente_manual")
    private String telefonoClienteManual;

    /**
     * Fecha y hora de inicio de la reserva.
     */
    @Column(name = "fecha_hora_inicio", nullable = false)
    private LocalDateTime fechaHoraInicio;

    /**
     * Fecha y hora de fin de la reserva.
     */
    @Column(name = "fecha_hora_fin", nullable = false)
    private LocalDateTime fechaHoraFin;

    /**
     * Estado actual de la reserva.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoReserva estado;

    /**
     * Precio total de la reserva.
     */
    @Column(name = "precio_total", nullable = false)
    private BigDecimal precioTotal;

    /**
     * Monto de seña pagada (inicializa en ZERO).
     */
    @Column(name = "sena_pagada", nullable = false)
    private BigDecimal senaPagada = BigDecimal.ZERO;

    /**
     * Fecha de creación de la reserva (no actualizable).
     */
    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime fechaCreacion;

    /**
     * Version para Optimistic Locking.
     * Evita conflictos de concurrencia en actualizaciones simultáneas.
     */
    @Version
    private Long version;

    /**
     * Inicializa los valores por defecto antes de persistir.
     */
    @PrePersist
    public void prePersist() {
        if (fechaCreacion == null) {
            fechaCreacion = LocalDateTime.now();
            log.debug("Inicializando fechaCreacion a {}", fechaCreacion);
        }
        if (estado == null) {
            estado = EstadoReserva.PENDIENTE_SENA;
            log.debug("Inicializando estado a {}", estado);
        }
        if (senaPagada == null) {
            senaPagada = BigDecimal.ZERO;
            log.debug("Inicializando senaPagada a ZERO");
        }
    }
}
