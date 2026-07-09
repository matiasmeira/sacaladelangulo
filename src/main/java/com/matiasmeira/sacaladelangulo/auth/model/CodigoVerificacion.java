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
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "codigos_verificacion", indexes = @Index(columnList = "email"))
public class CodigoVerificacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String codigo;

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
