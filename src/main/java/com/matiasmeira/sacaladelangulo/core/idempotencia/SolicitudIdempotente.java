package com.matiasmeira.sacaladelangulo.core.idempotencia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Registro de una solicitud identificada con una clave de idempotencia (header
 * Idempotency-Key), para poder devolver la misma respuesta si el cliente
 * reintenta la misma operación (ej. timeout de red y reintento automático) en
 * vez de repetir el efecto de negocio (doble reserva, doble venta, etc.).
 * Mientras statusRespuesta es null, la solicitud original todavía se está
 * procesando.
 */
@Entity
@Table(name = "solicitudes_idempotentes",
        uniqueConstraints = @UniqueConstraint(columnNames = {"clave", "usuario_email"}),
        indexes = @Index(name = "idx_solicitudes_idempotentes_fecha_creacion", columnList = "fecha_creacion"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SolicitudIdempotente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Largo explícito (en vez de dejar el default de Hibernate) para que
     * IdempotencyFilter.LONGITUD_MAXIMA_CLAVE valide contra el mismo límite real de la
     * columna, en vez de dejar que una clave demasiado larga reviente en un
     * DataIntegrityViolationException indistinguible de una carrera de idempotencia
     * genuina (ver B15 en la auditoría).
     */
    @Column(nullable = false, length = 255)
    private String clave;

    @Column(name = "usuario_email", nullable = false)
    private String usuarioEmail;

    @Column(name = "metodo_http", nullable = false)
    private String metodoHttp;

    @Column(nullable = false)
    private String path;

    /**
     * Hash SHA-256 (hex) del cuerpo de la request original, para detectar si la misma
     * Idempotency-Key se reutiliza con un payload distinto (ver IdempotencyFilter).
     */
    @Column(name = "body_hash", nullable = false, length = 64)
    private String bodyHash;

    @Column(name = "status_respuesta")
    private Integer statusRespuesta;

    @Column(name = "content_type_respuesta")
    private String contentTypeRespuesta;

    // Mapea a Postgres `text` (coincide con V1__baseline.sql). NO @Lob: sobre Postgres,
    // @Lob en un String hace que Hibernate espere `oid` (large object / CLOB), no `text`,
    // y ddl-auto=validate falla contra una base creada por Flyway (que crea `text`).
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "cuerpo_respuesta")
    private String cuerpoRespuesta;

    @Column(name = "fecha_creacion", nullable = false)
    private LocalDateTime fechaCreacion;

    @Column(name = "fecha_completado")
    private LocalDateTime fechaCompletado;
}
