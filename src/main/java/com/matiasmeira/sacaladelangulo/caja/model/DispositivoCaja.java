package com.matiasmeira.sacaladelangulo.caja.model;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.time.LocalDateTime;

/**
 * PC de mostrador que el dueño/admin habilitó explícitamente ("caja emparejada"). El
 * token de dispositivo (ver DispositivoCajaService) es lo único que desbloquea el
 * listado de nombres y el login de empleado de {@link #establecimiento} para esa PC.
 */
@Entity
@Table(name = "dispositivo_caja")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DispositivoCaja {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "establecimiento_id", nullable = false)
    private Establecimiento establecimiento;

    @Column(nullable = false)
    private String label;

    /**
     * Hash SHA-256 del token opaco entregado como cookie: nunca se persiste el valor
     * crudo (ver requisitos de seguridad del emparejamiento de caja).
     */
    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(nullable = false)
    @Builder.Default
    private Boolean activo = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creado_por_id", nullable = false)
    private Usuario creadoPor;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @PrePersist
    public void prePersist() {
        if (label == null || label.isBlank()) {
            label = "Caja mostrador";
        }
        if (activo == null) {
            activo = true;
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
