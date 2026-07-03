package com.matiasmeira.sacaladelangulo.establecimiento.model;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Entidad que representa un establecimiento deportivo.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "establecimientos")
public class Establecimiento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nombre;

    @Column(nullable = false)
    private String direccion;

    @Column(nullable = false)
    private Double latitud;

    @Column(nullable = false)
    private Double longitud;

    @Column(name = "requiere_sena", nullable = false)
    private Boolean requiereSena;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /**
     * Horas de anticipación mínimas requeridas para que un jugador pueda cancelar su reserva.
     */
    @jakarta.persistence.Column(name = "horas_cancelacion_antes_partido", nullable = false)
    @lombok.Builder.Default
    private Integer horasCancelacionAntesPartido = 24;

    /**
     * Minutos de gracia en los que un jugador puede cancelar libremente tras haber realizado la reserva (por si cometió un error).
     */
    @jakarta.persistence.Column(name = "minutos_gracia_cancelacion", nullable = false)
    @lombok.Builder.Default
    private Integer minutosGraciaCancelacion = 30;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dueno_id", nullable = false)
    private Usuario dueno;
}
