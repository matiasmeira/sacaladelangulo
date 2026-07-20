package com.matiasmeira.sacaladelangulo.auth.model;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Entidad Usuario para persistir los datos de autenticación y perfil.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "usuarios", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String nombre;

    @Column(nullable = true)
    private String telefono;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role rol;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_suscripcion")
    private PlanSuscripcion planSuscripcion;

    @Column(name = "fecha_creacion", nullable = false)
    private LocalDateTime fechaCreacion;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "email_verified", nullable = false)
    private Boolean emailVerified;

    @Column(name = "fecha_fin_prueba")
    private LocalDateTime fechaFinPrueba;

    @Column(name = "telefono_verificado", nullable = false)
    private Boolean telefonoVerificado;

    /**
     * Incrementado en logout o al cambiar la contraseña/PIN: el JWT emitido antes de ese
     * incremento lleva la versión vieja como claim y JwtService lo invalida en la
     * siguiente petición, aunque no haya expirado (ver B3 en la auditoría).
     */
    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private Integer tokenVersion = 0;

    /**
     * Establecimiento al que pertenece este usuario cuando rol = EMPLOYEE.
     * Nulo para el resto de los roles.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "establecimiento_id")
    private Establecimiento establecimiento;

    /**
     * Acciones habilitadas para este usuario cuando rol = EMPLOYEE. Vacío/no
     * aplicable para el resto de los roles.
     */
    @ElementCollection
    @CollectionTable(name = "usuario_permisos", joinColumns = @JoinColumn(name = "usuario_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "permiso", nullable = false)
    @Builder.Default
    private Set<PermisoEmpleado> permisos = new HashSet<>();

    @PrePersist
    public void prePersist() {
        if (fechaCreacion == null) {
            fechaCreacion = LocalDateTime.now();
        }
        if (isActive == null) {
            isActive = false;
        }
        if (emailVerified == null) {
            emailVerified = false;
        }
        if (telefonoVerificado == null) {
            telefonoVerificado = false;
        }
        if (tokenVersion == null) {
            tokenVersion = 0;
        }
    }
}
