package com.matiasmeira.sacaladelangulo.core.email.reintento;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Email que falló al enviarse y espera un reintento (ver V20 y EmailReintentoJob).
 *
 * <p>Guarda el cuerpo ya renderizado a propósito: el reintento manda exactamente lo que
 * falló, sin depender de que la entidad de origen siga existiendo. La contracara es que
 * un cuerpo viejo puede quedar obsoleto — por eso (destinatario, asunto) es único y un
 * reencolado pisa el anterior (ver el comentario de la migración).
 */
@Entity
@Table(name = "emails_pendientes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailPendiente {

    /** Tope de la columna ultimo_error en V20; el mensaje se recorta antes de persistir. */
    public static final int LARGO_MAXIMO_ERROR = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String destinatario;

    @Column(nullable = false)
    private String asunto;

    @Column(name = "cuerpo_html", nullable = false, columnDefinition = "TEXT")
    private String cuerpoHtml;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private EstadoEmailPendiente estado = EstadoEmailPendiente.PENDIENTE;

    @Column(nullable = false)
    @Builder.Default
    private Integer intentos = 0;

    @Column(name = "ultimo_error", length = LARGO_MAXIMO_ERROR)
    private String ultimoError;

    @Column(name = "fecha_creacion", nullable = false)
    private LocalDateTime fechaCreacion;

    @Column(name = "fecha_ultimo_intento")
    private LocalDateTime fechaUltimoIntento;

    @PrePersist
    void prePersist() {
        if (fechaCreacion == null) {
            fechaCreacion = LocalDateTime.now();
        }
        if (estado == null) {
            estado = EstadoEmailPendiente.PENDIENTE;
        }
        if (intentos == null) {
            intentos = 0;
        }
    }

    /** Recorta el mensaje al largo de la columna: un stacktrace largo no debe romper el insert. */
    public void registrarFallo(String mensajeError, int intentosMaximos) {
        this.intentos = this.intentos + 1;
        this.fechaUltimoIntento = LocalDateTime.now();
        this.ultimoError = mensajeError == null ? null
                : mensajeError.substring(0, Math.min(mensajeError.length(), LARGO_MAXIMO_ERROR));
        if (this.intentos >= intentosMaximos) {
            this.estado = EstadoEmailPendiente.ERROR;
        }
    }
}
