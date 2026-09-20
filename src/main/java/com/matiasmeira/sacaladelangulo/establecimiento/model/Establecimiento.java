package com.matiasmeira.sacaladelangulo.establecimiento.model;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Entidad que representa un establecimiento deportivo.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "establecimientos")
public class Establecimiento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nombre;

    @Column(nullable = false)
    private String direccion;

    @Column(nullable = false)
    private Double latitud;

    @Column(nullable = false)
    private Double longitud;

    @Column(name = "requiere_sena", nullable = false)
    private Boolean requiereSena;

    @Column(name = "requiere_telefono_verificado", nullable = false)
    @lombok.Builder.Default
    private Boolean requiereTelefonoVerificado = false;

    @Column(name = "is_active", nullable = false)
    @lombok.Builder.Default
    private Boolean isActive = true;

    /**
     * Horas de anticipación mínimas requeridas para que un jugador pueda cancelar su reserva.
     */
    @jakarta.persistence.Column(name = "horas_cancelacion_antes_partido", nullable = false)
    @lombok.Builder.Default
    private Integer horasCancelacionAntesPartido = 24;

    /**
     * Minutos de gracia en los que un jugador puede cancelar libremente tras haber realizado la reserva (por si cometió un error).
     */
    @jakarta.persistence.Column(name = "minutos_gracia_cancelacion", nullable = false)
    @lombok.Builder.Default
    private Integer minutosGraciaCancelacion = 30;

    @OneToMany(mappedBy = "establecimiento", cascade = jakarta.persistence.CascadeType.ALL, orphanRemoval = true)
    @lombok.Builder.Default
    private java.util.List<HorarioAtencion> horariosAtencion = new java.util.ArrayList<>();

    @Column(nullable = false, unique = true)
    private String slug;

    /**
     * Servicios/comodidades del complejo (parrilla, vestuarios, etc.), mostrados en la
     * zona pública. Mismo patrón que Cancha.deportes: @ElementCollection en tabla propia.
     */
    @ElementCollection
    @CollectionTable(name = "establecimiento_servicios", joinColumns = @JoinColumn(name = "establecimiento_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "servicio", nullable = false)
    @lombok.Builder.Default
    private java.util.Set<Servicio> servicios = new java.util.HashSet<>();

    /**
     * Fotos del complejo, en el orden en que se muestran (la primera es la
     * "fotoPrincipal" de la card pública). @OrderColumn persiste ese orden explícitamente
     * (columna "orden"): sin ella Hibernate no garantiza qué foto es la primera al releer.
     * Se gestionan vía FotoEstablecimientoService (subida/borrado contra ImageKit).
     */
    @ElementCollection
    @CollectionTable(name = "establecimiento_fotos", joinColumns = @JoinColumn(name = "establecimiento_id"))
    @OrderColumn(name = "orden")
    @lombok.Builder.Default
    private java.util.List<FotoEstablecimiento> fotos = new java.util.ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dueno_id", nullable = false)
    private Usuario dueno;

    /**
     * Estado de la verificación manual del establecimiento (ver EstablecimientoOperativoGuard,
     * que la combina con isActive para decidir si el establecimiento puede operar de cara al
     * público). Nace en PENDIENTE: la obligatoriedad de los datos de contacto/CUIT se valida
     * recién al solicitar la verificación, no acá.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "estado_verificacion", nullable = false)
    @lombok.Builder.Default
    private EstadoVerificacion estadoVerificacion = EstadoVerificacion.PENDIENTE;

    /**
     * CUIT del titular o razón social, normalizado a 11 dígitos sin guiones (ver
     * core.util.CuitUtils). Nullable: recién se exige al solicitar la verificación. NO es
     * unique -- un mismo dueño puede tener varios complejos con el mismo CUIT.
     */
    @Column(length = 11)
    private String cuit;

    /**
     * Nombre del titular o razón social del establecimiento.
     */
    @Column(name = "razon_social")
    private String razonSocial;

    /**
     * Teléfono de contacto para la verificación manual (puede diferir del teléfono de la
     * cuenta del dueño).
     */
    @Column(name = "telefono_contacto")
    private String telefonoContacto;

    /**
     * URL de Instagram o Facebook del complejo, usada como parte de la verificación manual.
     */
    @Column(name = "url_red_social")
    private String urlRedSocial;

    /**
     * Momento en que el dueño envió (o reenvió) la solicitud de verificación.
     */
    @Column(name = "fecha_solicitud_verificacion")
    private java.time.LocalDateTime fechaSolicitudVerificacion;

    /**
     * Momento en que un admin aprobó la verificación.
     */
    @Column(name = "fecha_verificacion")
    private java.time.LocalDateTime fechaVerificacion;

    /**
     * Admin que aprobó la verificación.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verificado_por_id")
    private Usuario verificadoPor;

    /**
     * Motivo del rechazo, cargado por el admin que rechazó la verificación.
     */
    @Column(name = "motivo_rechazo")
    private String motivoRechazo;
}
