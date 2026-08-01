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
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entidad que representa una reserva de cancha deportiva.
 * El campo @Version protege actualizaciones concurrentes sobre una reserva ya
 * persistida (confirmar/cancelar/finalizar/mover). NO protege la creación de reservas
 * solapadas: ese caso (dos inserts concurrentes para el mismo horario) se resuelve con
 * locking pesimista sobre la cancha en ReservaService (ver bloquearCanchasRelacionadas,
 * y C1 en la auditoría).
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
     * Deporte específico para el que se reservó la cancha en este turno. Una misma
     * cancha puede soportar varios deportes (ver Cancha.deportes); esto registra
     * cuál se usó para que el dueño prepare el equipamiento correspondiente
     * (redes, arcos, etc.).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "deporte_seleccionado", nullable = false)
    private Deporte deporteSeleccionado;

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
     * Momento en que vence la ventana de 10 minutos para confirmar/pagar la seña
     * (ver ReservaService.crearReserva y ReservaExpiracionService). Nulo en reservas
     * que no tienen ventana de expiración: manuales/semanales (nacen CONFIRMADA) y
     * cualquier reserva ya CONFIRMADA (se limpia al confirmar).
     */
    @Column(name = "expira_en")
    private LocalDateTime expiraEn;

    /**
     * Método de pago con el que se saldó la reserva. Nulo hasta que se finaliza (ver
     * ReservaService.finalizarReserva).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "metodo_pago")
    private MetodoPago metodoPago;

    /**
     * Optimistic locking sobre updates a esta fila ya persistida (ver comentario de
     * clase para el alcance real: no cubre la creación de reservas solapadas).
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
