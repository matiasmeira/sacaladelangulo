package com.matiasmeira.sacaladelangulo.auth.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Token opaco de un solo uso para recuperar la contraseña de un usuario (ver
 * RecuperacionPasswordService). Mismo criterio que TokenVerificacionEmail: no es un JWT
 * ni autentica nada por sí mismo, solo habilita el reset puntual de la contraseña.
 *
 * <p>tokenHash/codigoHash guardan el hash SHA-256 (ver TokenHasher), nunca el valor crudo
 * (ver M-05 en la auditoría).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tokens_recuperacion_password", indexes = @Index(columnList = "token_hash", unique = true))
public class TokenRecuperacionPassword {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "codigo_hash", nullable = false)
    private String codigoHash;

    @Column(nullable = false)
    private LocalDateTime fechaExpiracion;

    @Column(nullable = false)
    @Builder.Default
    private Integer intentos = 0;
}
