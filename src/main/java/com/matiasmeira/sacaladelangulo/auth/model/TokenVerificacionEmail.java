package com.matiasmeira.sacaladelangulo.auth.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Token opaco de un solo uso para verificar el email de un jugador antes de completar
 * su registro (ver RegistroVerificacionService). Es intencionalmente independiente de
 * JwtService: un JWT válido sería aceptado como sesión por JwtAuthenticationFilter antes
 * de que el usuario exista, mientras que este token no autentica nada por sí mismo.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tokens_verificacion_email", indexes = @Index(columnList = "token", unique = true))
public class TokenVerificacionEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(nullable = false)
    private LocalDateTime fechaExpiracion;

    @Column(nullable = false)
    private String codigo;

    @Column(nullable = false)
    @Builder.Default
    private Integer intentos = 0;
}
