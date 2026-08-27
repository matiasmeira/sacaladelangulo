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
}
