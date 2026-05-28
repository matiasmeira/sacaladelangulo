package com.matiasmeira.sacaladelangulo.auth.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * Entidad para almacenar códigos OTP de verificación de teléfono.
 */
@Data
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
}
