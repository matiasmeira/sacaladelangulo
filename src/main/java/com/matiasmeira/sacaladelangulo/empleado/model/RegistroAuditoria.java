package com.matiasmeira.sacaladelangulo.empleado.model;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
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
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Registro de una acción realizada por un empleado (exitosa o fallida), para que
 * el dueño pueda revisar la actividad de mostrador y detectar errores.
 */
@Entity
@Table(name = "registro_auditoria_empleados")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegistroAuditoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Nulo para eventos de dispositivo de caja (alta/emparejamiento/revocación): no hay
     * ningún empleado afectado, el sujeto de esas acciones es el propio dispositivo (ver
     * RegistroAuditoriaService.registrarDispositivo).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "empleado_id")
    private Usuario empleado;

    /**
     * ID del OWNER/ADMIN que ejecutó una acción administrativa sobre `empleado` (alta,
     * cambio de permisos/PIN, baja). Null para las acciones operativas, donde el propio
     * `empleado` es quien actuó (ver M31 en la auditoría).
     */
    @Column(name = "actor_id")
    private Long actorId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "establecimiento_id", nullable = false)
    private Establecimiento establecimiento;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccionAuditoria accion;

    @Column(name = "entidad_afectada_id")
    private Long entidadAfectadaId;

    @Column(nullable = false)
    private Boolean exitoso;

    @Column(length = 500)
    private String detalle;

    @Column(name = "fecha_hora", nullable = false)
    private LocalDateTime fechaHora;
}
