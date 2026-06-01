package com.matiasmeira.sacaladelangulo.auth.model;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

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

    @PrePersist
    public void prePersist() {
        if (fechaCreacion == null) {
            fechaCreacion = LocalDateTime.now();
        }
        if (isActive == null) {
            isActive = true;
        }
        if (emailVerified == null) {
            emailVerified = false;
        }
        if (telefonoVerificado == null) {
            telefonoVerificado = false;
        }
    }
}
