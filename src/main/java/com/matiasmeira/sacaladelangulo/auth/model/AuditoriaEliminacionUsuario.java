package com.matiasmeira.sacaladelangulo.auth.model;

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
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Auditoría de la baja de una cuenta (self-delete o admin-delete). Entidad propia y
 * desacoplada de RegistroAuditoria (empleado/model): esa tabla exige un Establecimiento
 * no-nulo por fila y no encaja para la baja de un PLAYER o de un OWNER sin
 * establecimientos (ver spec de eliminación de cuenta).
 */
@Entity
@Table(name = "auditoria_eliminacion_usuario")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditoriaEliminacionUsuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * La cuenta eliminada (fila ya anonimizada al momento de guardar este registro; el
     * FK sigue siendo válido porque el soft-delete nunca borra la fila).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    /**
     * Null en autoeliminación. Id del ADMIN que ejecutó la baja en eliminación admin.
     */
    @Column(name = "actor_id")
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoEliminacionCuenta tipo;

    /**
     * Nullable. Se usa, por ejemplo, para dejar trazado cuántos establecimientos activos
     * tenía un OWNER cuando un ADMIN fuerza la baja salteando ese guardrail.
     */
    @Column(length = 500)
    private String detalle;

    @Column(name = "fecha_hora", nullable = false)
    private LocalDateTime fechaHora;
}
