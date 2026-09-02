package com.matiasmeira.sacaladelangulo.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Auditoría de la degradación automática de plan TRIAL -> FREE (ver ExpiracionPruebaService).
 * Entidad propia y desacoplada de RegistroAuditoria (empleado/model): esa tabla exige un
 * Establecimiento no-nulo por fila y no encaja acá, porque un OWNER en TRIAL puede no tener
 * ningún establecimiento todavía cuando se le vence la prueba (mismo motivo que llevó a crear
 * AuditoriaEliminacionUsuario en vez de reusar RegistroAuditoria).
 */
@Entity
@Table(name = "auditoria_degradacion_plan")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditoriaDegradacionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(name = "fecha_hora", nullable = false)
    private LocalDateTime fechaHora;

    @Column(length = 500)
    private String detalle;
}
