package com.matiasmeira.sacaladelangulo.auth.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * Entidad para almacenar códigos OTP de verificación de teléfono.
 *
 * <p>codigoHash guarda el hash SHA-256 (ver TokenHasher), nunca el código crudo (ver M-05
 * en la auditoría).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "codigos_verificacion",
        uniqueConstraints = @UniqueConstraint(columnNames = "email"),
        indexes = @Index(columnList = "email"))
public class CodigoVerificacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    @Column(name = "codigo_hash", nullable = false)
    private String codigoHash;

    @Column(nullable = false)
    private String telefonoPendiente;

    @Column(nullable = false)
    private LocalDateTime fechaExpiracion;

    /**
     * Cantidad de intentos fallidos de verificación. Permite limitar la fuerza bruta
     * sobre el código OTP de 6 dígitos.
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer intentos = 0;
}
