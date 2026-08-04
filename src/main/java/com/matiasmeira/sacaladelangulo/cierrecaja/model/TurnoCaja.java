package com.matiasmeira.sacaladelangulo.cierrecaja.model;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.caja.model.DispositivoCaja;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Turno de caja: ventana de tiempo entre apertura y cierre de caja de un establecimiento,
 * durante la cual se registran los {@link MovimientoCaja} asociados.
 */
@Entity
@Table(name = "turno_caja")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TurnoCaja {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "establecimiento_id", nullable = false)
    private Establecimiento establecimiento;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dispositivo_caja_id")
    private DispositivoCaja dispositivoCaja;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_apertura_id", nullable = false)
    private Usuario usuarioApertura;

    @Column(name = "fecha_apertura", nullable = false)
    private LocalDateTime fechaApertura;

    @Column(name = "fondo_inicial", nullable = false)
    private BigDecimal fondoInicial;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoTurnoCaja estado;

    @Column(name = "fecha_cierre")
    private LocalDateTime fechaCierre;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_cierre_id")
    private Usuario usuarioCierre;

    @Column(name = "saldo_teorico_efectivo")
    private BigDecimal saldoTeoricoEfectivo;

    @Column(name = "saldo_real_contado")
    private BigDecimal saldoRealContado;

    @Column
    private BigDecimal diferencia;

    @Column(length = 1000)
    private String observaciones;

    @PrePersist
    public void prePersist() {
        if (fechaApertura == null) {
            fechaApertura = LocalDateTime.now();
        }
        if (estado == null) {
            estado = EstadoTurnoCaja.ABIERTO;
        }
    }
}
